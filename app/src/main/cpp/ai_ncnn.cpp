// Runs ncnn models on tiles of ARGB_8888 bitmaps, on the GPU (Vulkan) or the CPU, for
// AiRuntime.kt's LibNcnn. A model is opened from its .param/.bin files; each run reads a
// tile of a bitmap (pixels outside it repeat the nearest edge), runs the model, and writes a
// window of the output into another bitmap; or reads a whole bitmap and returns the output's
// first channel as numbers (a score map). The caller makes every call from one thread that
// lives as long as the process: the OpenMP runtime fails to start again after the thread that
// started it ends.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <malloc.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cmath>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>

#include "allocator.h"
#include "cpu.h"
#include "gpu.h"
#include "net.h"

#define TAG "AiNcnn"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// The Vulkan instance lives while a model on the GPU is open, so the driver's memory goes with the last one.
std::mutex gpu_lock;
int gpu_users = 0;

bool gpu_acquire() {
    std::lock_guard<std::mutex> hold(gpu_lock);
    if (gpu_users == 0 && ncnn::create_gpu_instance() != 0) return false;
    if (ncnn::get_gpu_count() == 0) {
        if (gpu_users == 0) ncnn::destroy_gpu_instance();
        return false;
    }
    gpu_users++;
    return true;
}

void gpu_release() {
    std::lock_guard<std::mutex> hold(gpu_lock);
    if (--gpu_users == 0) ncnn::destroy_gpu_instance();
}

/** Bytes a model's allocators hold, and the most they held at once since [take]. */
struct Usage {
    std::atomic<int64_t> held{0};
    std::atomic<int64_t> peak{0};

    void add(int64_t n) {
        const int64_t now = held += n;
        int64_t most = peak.load();
        while (now > most && !peak.compare_exchange_weak(most, now)) {
        }
    }

    void sub(int64_t n) { held -= n; }

    /** The most held since the last call; counting starts again from what is held now. */
    int64_t take() { return peak.exchange(held.load()); }
};

/**
 * Host memory for feature maps and scratch, pooled: a freed chunk is kept and handed out again
 * for the smallest request it fits (as ncnn's own pools, which take the first that fits);
 * at most [KEEP] chunks are kept: a request none fits drops the smallest, a freed chunk past
 * [KEEP] the one kept longest. Every chunk it holds, handed out or kept, counts in [usage].
 */
class CountingPool : public ncnn::Allocator {
public:
    explicit CountingPool(Usage* usage) : usage(usage) {}

    ~CountingPool() override { clear(); }

    void* fastMalloc(size_t size) override {
        std::lock_guard<std::mutex> hold(lock);
        auto best = kept.end();
        for (auto it = kept.begin(); it != kept.end(); ++it) {
            if (it->first >= size && (best == kept.end() || it->first < best->first)) best = it;
        }
        if (best != kept.end()) {
            void* ptr = best->second;
            out[ptr] = best->first;
            kept.erase(best);
            return ptr;
        }
        // None fits: all kept are smaller.
        if (kept.size() >= KEEP) drop(std::min_element(kept.begin(), kept.end()));
        void* ptr = ncnn::fastMalloc(size);
        if (!ptr) return nullptr;
        out[ptr] = size;
        usage->add((int64_t) size);
        return ptr;
    }

    void fastFree(void* ptr) override {
        std::lock_guard<std::mutex> hold(lock);
        auto it = out.find(ptr);
        if (it == out.end()) return;
        kept.emplace_back(it->second, ptr);
        out.erase(it);
        while (kept.size() > KEEP) drop(kept.begin());
    }

    /** Frees the kept chunks. */
    void clear() {
        std::lock_guard<std::mutex> hold(lock);
        while (!kept.empty()) drop(kept.begin());
    }

private:
    static constexpr size_t KEEP = 10;

    void drop(std::list<std::pair<size_t, void*>>::iterator it) {
        usage->sub((int64_t) it->first);
        ncnn::fastFree(it->second);
        kept.erase(it);
    }

    Usage* usage;
    std::mutex lock;
    std::list<std::pair<size_t, void*>> kept;
    std::unordered_map<void*, size_t> out;
};

/** ncnn's GPU blob allocator, counting the buffers and images it hands out in [usage]. */
class CountingVkAllocator : public ncnn::VkBlobAllocator {
public:
    CountingVkAllocator(const ncnn::VulkanDevice* vkdev, Usage* usage) : ncnn::VkBlobAllocator(vkdev), usage(usage) {}

    ncnn::VkBufferMemory* fastMalloc(size_t size) override {
        ncnn::VkBufferMemory* m = ncnn::VkBlobAllocator::fastMalloc(size);
        if (m) usage->add((int64_t) m->capacity);
        return m;
    }

    void fastFree(ncnn::VkBufferMemory* m) override {
        if (m) usage->sub((int64_t) m->capacity);
        ncnn::VkBlobAllocator::fastFree(m);
    }

    ncnn::VkImageMemory* fastMalloc(int w, int h, int c, size_t elemsize, int elempack) override {
        ncnn::VkImageMemory* m = ncnn::VkBlobAllocator::fastMalloc(w, h, c, elemsize, elempack);
        if (m) usage->add((int64_t) m->bind_capacity);
        return m;
    }

    void fastFree(ncnn::VkImageMemory* m) override {
        if (m) usage->sub((int64_t) m->bind_capacity);
        ncnn::VkBlobAllocator::fastFree(m);
    }

private:
    Usage* usage;
};

/** Bytes this process holds on the native heap. */
int64_t heapBytes() { return (int64_t) mallinfo().uordblks; }

/** Bytes this process holds in the GPU's memory heaps; 0 where the driver does not tell (no VK_EXT_memory_budget). */
int64_t gpuBytes() {
    const ncnn::GpuInfo& info = ncnn::get_gpu_info();
    if (!info.support_VK_EXT_memory_budget() || !ncnn::vkGetPhysicalDeviceMemoryProperties2KHR) return 0;
    VkPhysicalDeviceMemoryBudgetPropertiesEXT budget{};
    budget.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT;
    VkPhysicalDeviceMemoryProperties2KHR p{};
    p.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2_KHR;
    p.pNext = &budget;
    ncnn::vkGetPhysicalDeviceMemoryProperties2KHR(info.physicalDevice(), &p);
    int64_t sum = 0;
    for (uint32_t i = 0; i < p.memoryProperties.memoryHeapCount; i++) sum += (int64_t) budget.heapUsage[i];
    return sum;
}

struct Model {
    // First, so the allocators counting in it go before it.
    Usage usage;
    /** What loading the weights took: the native heap's growth, and on the GPU its heaps' growth. */
    int64_t weights = 0;
    ncnn::Net net;
    bool gpu = false;
    ncnn::VulkanDevice* vkdev = nullptr;
    CountingVkAllocator* blob_vk = nullptr;
    ncnn::VkStagingAllocator* staging_vk = nullptr;
    CountingPool blob{&usage};
    CountingPool workspace{&usage};
    std::string input, output;

    ~Model() {
        net.clear();
        delete blob_vk;
        delete staging_vk;
        blob.clear();
        workspace.clear();
        if (gpu) gpu_release();
    }

    /** Frees every buffer kept between runs, on the GPU and the CPU. */
    void trim() {
        if (blob_vk) blob_vk->clear();
        if (staging_vk) staging_vk->clear();
        blob.clear();
        workspace.clear();
    }
};

std::string utf(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

/** An ARGB_8888 bitmap's pixels, locked for the life of this object. */
struct Pixels {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    uint8_t* data = nullptr;

    Pixels(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        void* p = nullptr;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        if (AndroidBitmap_lockPixels(env, bitmap, &p) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        data = static_cast<uint8_t*>(p);
    }

    ~Pixels() {
        if (data) AndroidBitmap_unlockPixels(env, bitmap);
    }
};

// Result codes, as AiRuntime.kt's TileModel reads them.
constexpr jint OK = 0;
constexpr jint FAILED_INPUT = -1;
constexpr jint FAILED_RUN = -2;
constexpr jint FAILED_OUTPUT = -3;

// fp16 modes, as bits (GpuInfo's Fp16Modes on the Kotlin side).
constexpr jint FP16_STORAGE = 1;
constexpr jint FP16_PACKED = 2;
constexpr jint FP16_ARITHMETIC = 4;

/**
 * Places the calling thread and its OpenMP workers for [m]: on the large cores for a model on
 * the CPU, anywhere for one on the GPU. ncnn places only the calling thread's team, so what
 * each thread holds is kept per thread and set before every run rather than once when a model
 * opens: one thread may run models of both kinds.
 */
void place(const Model* m) {
    thread_local int placed = -1;
    const int powersave = m->gpu ? 0 : 2;
    if (placed != powersave && ncnn::set_cpu_powersave(powersave) == 0) placed = powersave;
}

}  // namespace

extern "C" {

/** Turns off the OpenMP runtime's own thread placement before its first use: ncnn places its threads itself. */
JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    setenv("KMP_AFFINITY", "disabled", 1);
    return JNI_VERSION_1_6;
}

/**
 * GPU name, heap budget in MB, the fp16 modes it supports (bits: FP16_STORAGE, FP16_PACKED,
 * FP16_ARITHMETIC) and the largest buffer one shader may address in bytes (the lesser of
 * maxStorageBufferRange and maxMemoryAllocationSize), as "name\tbudget\tfp16\tbuffer"; null
 * without a usable Vulkan device.
 */
JNIEXPORT jstring JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nGpu(JNIEnv* env, jclass) {
    if (!gpu_acquire()) return nullptr;
    std::string s;
    {
        const ncnn::GpuInfo& info = ncnn::get_gpu_info();
        ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device();
        uint64_t buffer = info.physicalDeviceProperties().limits.maxStorageBufferRange;
        if (info.support_VK_KHR_maintenance3() && ncnn::vkGetPhysicalDeviceProperties2KHR) {
            VkPhysicalDeviceMaintenance3PropertiesKHR m3{};
            m3.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_3_PROPERTIES;
            VkPhysicalDeviceProperties2KHR p2{};
            p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2_KHR;
            p2.pNext = &m3;
            ncnn::vkGetPhysicalDeviceProperties2KHR(info.physicalDevice(), &p2);
            if (m3.maxMemoryAllocationSize > 0) buffer = std::min<uint64_t>(buffer, m3.maxMemoryAllocationSize);
        }
        s = std::string(info.device_name()) + "\t" + std::to_string(vkdev ? vkdev->get_heap_budget() : 0) + "\t" +
            std::to_string((info.support_fp16_storage() ? FP16_STORAGE : 0) | (info.support_fp16_packed() ? FP16_PACKED : 0) |
                           (info.support_fp16_arithmetic() ? FP16_ARITHMETIC : 0)) +
            "\t" + std::to_string(buffer);
    }
    gpu_release();
    return env->NewStringUTF(s.c_str());
}

/**
 * Opens a model on the GPU when [gpu] and one is usable, else on [threads] CPU threads of the
 * large cores; on the GPU, the fp16 modes in bits [fp16] (as nGpu reports them), fp32 for 0.
 * The CPU always computes in fp32: its fp16 kernels differ by tile shape enough to show as
 * seams. 0 when the files do not load.
 */
JNIEXPORT jlong JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nOpen(JNIEnv* env, jclass, jstring param, jstring bin, jboolean gpu, jint threads,
                                               jint fp16) {
    auto* m = new Model();
    if (gpu && gpu_acquire()) {
        m->gpu = true;
        m->vkdev = ncnn::get_gpu_device();
        m->blob_vk = new CountingVkAllocator(m->vkdev, &m->usage);
        m->staging_vk = new ncnn::VkStagingAllocator(m->vkdev);
        m->net.opt.use_vulkan_compute = true;
        m->net.set_vulkan_device(m->vkdev);
    } else {
        m->net.opt.use_vulkan_compute = false;
    }
    m->net.opt.num_threads = std::max(1, (int) threads);
    // Worker threads sleep at once between parallel loops rather than spin.
    m->net.opt.openmp_blocktime = 0;
    m->net.opt.blob_allocator = &m->blob;
    m->net.opt.workspace_allocator = &m->workspace;
    const jint modes = m->gpu ? fp16 : 0;
    m->net.opt.use_fp16_storage = (modes & FP16_STORAGE) != 0;
    m->net.opt.use_fp16_packed = (modes & FP16_PACKED) != 0;
    m->net.opt.use_fp16_arithmetic = (modes & FP16_ARITHMETIC) != 0;
    m->net.opt.use_bf16_packed = false;
    m->net.opt.use_bf16_storage = false;
    std::string p = utf(env, param), b = utf(env, bin);
    // Other threads' allocations meanwhile count too: an upper bound.
    const int64_t heap0 = heapBytes();
    const int64_t gpu0 = m->gpu ? gpuBytes() : 0;
    if (m->net.load_param(p.c_str()) != 0 || m->net.load_model(b.c_str()) != 0 || m->net.input_names().empty() ||
        m->net.output_names().empty()) {
        LOGW("cannot load model %s", p.c_str());
        delete m;
        return 0;
    }
    m->weights = std::max<int64_t>(0, heapBytes() - heap0) + (m->gpu ? std::max<int64_t>(0, gpuBytes() - gpu0) : 0);
    m->input = m->net.input_names()[0];
    m->output = m->net.output_names()[0];
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT jboolean JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nOnGpu(JNIEnv*, jclass, jlong handle) {
    return reinterpret_cast<Model*>(handle)->gpu;
}

/**
 * Runs the model on the [iw] x [ih] tile at ([ix], [iy]) of [src], whose pixels outside it repeat
 * the nearest edge, as RGB scaled by [inScale]. Writes the output's size to [size]; with [dst],
 * also writes output window ([cx], [cy], [cw], [ch]), scaled by [outScale], opaque, to [dst] at
 * ([dx], [dy]). 0 on success, else a negative code.
 */
JNIEXPORT jint JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nRun(JNIEnv* env, jclass, jlong handle, jobject src, jint ix, jint iy, jint iw,
                                              jint ih, jfloat inScale, jobject dst, jint cx, jint cy, jint cw, jint ch,
                                              jint dx, jint dy, jfloat outScale, jintArray size) {
    auto* m = reinterpret_cast<Model*>(handle);
    if (iw <= 0 || ih <= 0) return FAILED_INPUT;
    ncnn::Mat in(iw, ih, 3, (size_t) 4u, &m->blob);
    if (in.empty()) return FAILED_INPUT;
    {
        Pixels s(env, src);
        if (!s.data) return FAILED_INPUT;
        const int w = (int) s.info.width, h = (int) s.info.height;
        float* r = in.channel(0);
        float* g = in.channel(1);
        float* b = in.channel(2);
        for (int y = 0; y < ih; y++) {
            const uint8_t* row = s.data + (size_t) std::clamp(iy + y, 0, h - 1) * s.info.stride;
            for (int x = 0; x < iw; x++) {
                const uint8_t* p = row + (size_t) std::clamp(ix + x, 0, w - 1) * 4;
                r[x] = p[0] * inScale;
                g[x] = p[1] * inScale;
                b[x] = p[2] * inScale;
            }
            r += iw;
            g += iw;
            b += iw;
        }
    }

    ncnn::Mat out;
    {
        place(m);
        ncnn::Extractor ex = m->net.create_extractor();
        ex.set_blob_allocator(&m->blob);
        ex.set_workspace_allocator(&m->workspace);
        if (m->gpu) {
            ex.set_blob_vkallocator(m->blob_vk);
            ex.set_workspace_vkallocator(m->blob_vk);
            ex.set_staging_vkallocator(m->staging_vk);
        }
        if (ex.input(m->input.c_str(), in) != 0 || ex.extract(m->output.c_str(), out) != 0) return FAILED_RUN;
    }
    if (out.empty() || out.dims != 3 || out.c < 3 || out.elempack != 1) return FAILED_RUN;
    jint dims[2] = {out.w, out.h};
    env->SetIntArrayRegion(size, 0, 2, dims);
    if (!dst) return OK;

    Pixels d(env, dst);
    if (!d.data) return FAILED_OUTPUT;
    if (cx < 0 || cy < 0 || cw <= 0 || ch <= 0 || cx + cw > out.w || cy + ch > out.h || dx < 0 || dy < 0 ||
        dx + cw > (int) d.info.width || dy + ch > (int) d.info.height)
        return FAILED_OUTPUT;
    const float* r = out.channel(0);
    const float* g = out.channel(1);
    const float* b = out.channel(2);
    for (int y = 0; y < ch; y++) {
        uint8_t* row = d.data + (size_t) (dy + y) * d.info.stride + (size_t) dx * 4;
        const size_t at = (size_t) (cy + y) * out.w + cx;
        for (int x = 0; x < cw; x++) {
            row[x * 4 + 0] = (uint8_t) std::clamp((int) std::lround(r[at + x] * outScale), 0, 255);
            row[x * 4 + 1] = (uint8_t) std::clamp((int) std::lround(g[at + x] * outScale), 0, 255);
            row[x * 4 + 2] = (uint8_t) std::clamp((int) std::lround(b[at + x] * outScale), 0, 255);
            row[x * 4 + 3] = 255;
        }
    }
    return OK;
}

/**
 * Runs the model on all of [src], each channel as (8-bit value - [mean]) * [norm], channels in
 * B, G, R order when [bgr], else R, G, B. Returns the output's first channel, row by row, and
 * writes its size to [size]; null on failure.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nMap(JNIEnv* env, jclass, jlong handle, jobject src, jfloatArray mean,
                                              jfloatArray norm, jboolean bgr, jintArray size) {
    auto* m = reinterpret_cast<Model*>(handle);
    float mv[3], nv[3];
    env->GetFloatArrayRegion(mean, 0, 3, mv);
    env->GetFloatArrayRegion(norm, 0, 3, nv);
    ncnn::Mat in;
    {
        Pixels s(env, src);
        if (!s.data) return nullptr;
        const int w = (int) s.info.width, h = (int) s.info.height;
        in.create(w, h, 3, (size_t) 4u, &m->blob);
        if (in.empty()) return nullptr;
        // Input channel c reads pixel component order[c].
        const int order[3] = {bgr ? 2 : 0, 1, bgr ? 0 : 2};
        for (int c = 0; c < 3; c++) {
            float* out = in.channel(c);
            const int k = order[c];
            for (int y = 0; y < h; y++) {
                const uint8_t* row = s.data + (size_t) y * s.info.stride;
                for (int x = 0; x < w; x++) *out++ = (row[x * 4 + k] - mv[c]) * nv[c];
            }
        }
    }
    ncnn::Mat out;
    {
        place(m);
        ncnn::Extractor ex = m->net.create_extractor();
        ex.set_blob_allocator(&m->blob);
        ex.set_workspace_allocator(&m->workspace);
        if (m->gpu) {
            ex.set_blob_vkallocator(m->blob_vk);
            ex.set_workspace_vkallocator(m->blob_vk);
            ex.set_staging_vkallocator(m->staging_vk);
        }
        if (ex.input(m->input.c_str(), in) != 0 || ex.extract(m->output.c_str(), out) != 0) return nullptr;
    }
    if (out.empty() || out.elempack != 1 || out.w <= 0 || out.h <= 0) return nullptr;
    const jsize n = (jsize) out.w * out.h;
    jfloatArray values = env->NewFloatArray(n);
    if (!values) return nullptr;
    env->SetFloatArrayRegion(values, 0, n, (const float*) out.channel(0));
    jint dims[2] = {out.w, out.h};
    env->SetIntArrayRegion(size, 0, 2, dims);
    return values;
}

/**
 * The most bytes of feature maps and scratch the model held at once, on the GPU and the CPU,
 * since the last call; counting starts again from what it holds now.
 */
JNIEXPORT jlong JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nPeak(JNIEnv*, jclass, jlong handle) {
    return (jlong) reinterpret_cast<Model*>(handle)->usage.take();
}

/**
 * The bytes loading the model's weights took: the native heap's growth (weights as ncnn
 * transforms them for its kernels) and, on the GPU, its memory heaps' growth.
 */
JNIEXPORT jlong JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nWeights(JNIEnv*, jclass, jlong handle) {
    return (jlong) reinterpret_cast<Model*>(handle)->weights;
}

/** Frees the buffers a model keeps between runs; its weights stay. */
JNIEXPORT void JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nTrim(JNIEnv*, jclass, jlong handle) {
    reinterpret_cast<Model*>(handle)->trim();
}

JNIEXPORT void JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Model*>(handle);
}

}  // extern "C"
