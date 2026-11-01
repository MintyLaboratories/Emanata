// Runs ncnn models on tiles of ARGB_8888 bitmaps, on the GPU (Vulkan) or the CPU, for
// AiRuntime.kt's LibNcnn. A model is opened from its .param/.bin files; each run reads a
// tile of a bitmap (pixels outside it repeat the nearest edge), runs the model, and writes a
// window of the output into another bitmap. The caller makes every call from one thread that
// lives as long as the process: the OpenMP runtime fails to start again after the thread that
// started it ends.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstdlib>
#include <cmath>
#include <mutex>
#include <string>

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

struct Model {
    ncnn::Net net;
    bool gpu = false;
    ncnn::VulkanDevice* vkdev = nullptr;
    ncnn::VkBlobAllocator* blob_vk = nullptr;
    ncnn::VkStagingAllocator* staging_vk = nullptr;
    ncnn::UnlockedPoolAllocator blob;
    ncnn::PoolAllocator workspace;
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

}  // namespace

extern "C" {

/** Turns off the OpenMP runtime's own thread placement before its first use: ncnn places its threads itself. */
JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    setenv("KMP_AFFINITY", "disabled", 1);
    return JNI_VERSION_1_6;
}

/** GPU name and heap budget in MB, as "name\tbudget\tfp16"; null without a usable Vulkan device. */
JNIEXPORT jstring JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nGpu(JNIEnv* env, jclass) {
    if (!gpu_acquire()) return nullptr;
    std::string s;
    {
        const ncnn::GpuInfo& info = ncnn::get_gpu_info();
        ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device();
        s = std::string(info.device_name()) + "\t" + std::to_string(vkdev ? vkdev->get_heap_budget() : 0) + "\t" +
            (info.support_fp16_storage() && info.support_fp16_arithmetic() ? "1" : "0");
    }
    gpu_release();
    return env->NewStringUTF(s.c_str());
}

/**
 * Opens a model on the GPU when [gpu] and one is usable, else on [threads] CPU threads of the
 * large cores; on the GPU, fp16 storage and arithmetic only when [fp16]. The CPU always computes
 * in fp32: its fp16 kernels differ by tile shape enough to show as seams. 0 when the files do not load.
 */
JNIEXPORT jlong JNICALL
Java_com_infinity_comicreader_ai_LibNcnn_nOpen(JNIEnv* env, jclass, jstring param, jstring bin, jboolean gpu, jint threads,
                                               jboolean fp16) {
    auto* m = new Model();
    if (gpu && gpu_acquire()) {
        m->gpu = true;
        m->vkdev = ncnn::get_gpu_device();
        m->blob_vk = new ncnn::VkBlobAllocator(m->vkdev);
        m->staging_vk = new ncnn::VkStagingAllocator(m->vkdev);
        m->net.opt.use_vulkan_compute = true;
        m->net.set_vulkan_device(m->vkdev);
    } else {
        m->net.opt.use_vulkan_compute = false;
        ncnn::set_cpu_powersave(2);
    }
    m->net.opt.num_threads = std::max(1, (int) threads);
    // Worker threads sleep at once between parallel loops rather than spin.
    m->net.opt.openmp_blocktime = 0;
    m->net.opt.blob_allocator = &m->blob;
    m->net.opt.workspace_allocator = &m->workspace;
    if (!fp16 || !m->gpu) {
        m->net.opt.use_fp16_packed = false;
        m->net.opt.use_fp16_storage = false;
        m->net.opt.use_fp16_arithmetic = false;
        m->net.opt.use_bf16_packed = false;
        m->net.opt.use_bf16_storage = false;
    }
    std::string p = utf(env, param), b = utf(env, bin);
    if (m->net.load_param(p.c_str()) != 0 || m->net.load_model(b.c_str()) != 0 || m->net.input_names().empty() ||
        m->net.output_names().empty()) {
        LOGW("cannot load model %s", p.c_str());
        delete m;
        return 0;
    }
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
