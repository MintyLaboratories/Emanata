// JNI binding of resample.h for com.infinity.comicreader.reader.LibPixels: a job locks the
// pixels of a source and a destination ARGB_8888 bitmap until it ends, takes each axis'
// taps (checked to stay inside both bitmaps), then filters bands of output rows on the
// CPU, from several threads at once with a worker index each, or on the GPU. A band may
// run pinned to a set of cores.

#define _GNU_SOURCE
#include <android/bitmap.h>
#include <jni.h>
#include <sched.h>
#include <stdlib.h>
#include <string.h>

#include "core_ledger.h"
#include "jxl_runner.h"
#include "resample.h"

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

typedef struct {
    jobject src_ref;
    jobject dst_ref;
    int src_w;
    int src_h;
    int dst_w;
    int dst_h;
    rs_job job;
    rs_worker * workers;
    int worker_count;
    int gpu;
    rs_gpu * on_gpu;
    // Bit 0 set once the horizontal axis is in, bit 1 the vertical one.
    int axes;
} handle;

static void release(JNIEnv * env, handle * h)
{
    if (h->on_gpu != NULL) rs_gpu_close(h->on_gpu);
    for (int i = 0; i < h->worker_count; i++) rs_worker_free(&h->workers[i]);
    free(h->workers);
    rs_axis_free(&h->job.ax);
    rs_axis_free(&h->job.ay);
    if (h->src_ref != NULL) {
        if (h->job.src != NULL) AndroidBitmap_unlockPixels(env, h->src_ref);
        (*env)->DeleteGlobalRef(env, h->src_ref);
    }
    if (h->dst_ref != NULL) {
        if (h->job.dst != NULL) AndroidBitmap_unlockPixels(env, h->dst_ref);
        (*env)->DeleteGlobalRef(env, h->dst_ref);
    }
    free(h);
}

// A job resampling src into dst, both ARGB_8888, on `workers` CPU workers or on the GPU; 0 when the bitmaps will not do.
EXPORT jlong JNICALL Java_com_infinity_comicreader_reader_LibPixels_nBegin(
    JNIEnv * env, jclass clazz, jobject src, jobject dst, jboolean linear, jint workers, jboolean gpu)
{
    (void)clazz;
    AndroidBitmapInfo si, di;
    if (workers < 1 || AndroidBitmap_getInfo(env, src, &si) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dst, &di) != ANDROID_BITMAP_RESULT_SUCCESS || si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        di.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return 0;
    }
    handle * h = calloc(1, sizeof(*h));
    if (h == NULL) return 0;
    h->src_w = (int)si.width;
    h->src_h = (int)si.height;
    h->dst_w = (int)di.width;
    h->dst_h = (int)di.height;
    h->gpu = gpu;
    h->job.linear = linear;
    h->job.src_stride = si.stride;
    h->job.dst_stride = di.stride;
    h->worker_count = workers;
    h->workers = calloc((size_t)workers, sizeof(rs_worker));
    h->src_ref = (*env)->NewGlobalRef(env, src);
    h->dst_ref = (*env)->NewGlobalRef(env, dst);
    void * sp = NULL;
    void * dp = NULL;
    if (h->workers == NULL || h->src_ref == NULL || h->dst_ref == NULL ||
        AndroidBitmap_lockPixels(env, h->src_ref, &sp) != ANDROID_BITMAP_RESULT_SUCCESS) {
        release(env, h);
        return 0;
    }
    h->job.src = sp;
    if (AndroidBitmap_lockPixels(env, h->dst_ref, &dp) != ANDROID_BITMAP_RESULT_SUCCESS) {
        release(env, h);
        return 0;
    }
    h->job.dst = dp;
    return (jlong)(intptr_t)h;
}

// True when every tap of an axis of `len` outputs over `src_len` source pixels lies inside them.
static int taps_fit(const rs_axis * a, int src_len)
{
    if (a->stride < 1) return 0;
    for (int i = 0; i < a->len; i++) {
        const int s = a->start[i];
        const int n = a->count[i];
        if (s < 0 || n < 1 || n > a->stride || s + n > src_len) return 0;
        if (a->ring > 0 && (a->ring_lo[i] < s || a->ring_hi[i] < a->ring_lo[i] || a->ring_hi[i] >= s + n)) return 0;
    }
    return 1;
}

// Takes the taps of the horizontal axis, or the vertical one; false when they do not fit the bitmaps or the GPU is unusable.
EXPORT jboolean JNICALL Java_com_infinity_comicreader_reader_LibPixels_nAxis(
    JNIEnv * env, jclass clazz, jlong job, jboolean vertical, jintArray start, jintArray count, jfloatArray weights,
    jint stride, jfloat ring, jintArray ring_lo, jintArray ring_hi)
{
    (void)clazz;
    handle * h = (handle *)(intptr_t)job;
    const int len = vertical ? h->dst_h : h->dst_w;
    const int src_len = vertical ? h->src_h : h->src_w;
    const int bit = vertical ? 2 : 1;
    if ((h->axes & bit) || len < 1 || stride < 1 || (*env)->GetArrayLength(env, start) != len ||
        (*env)->GetArrayLength(env, count) != len || (*env)->GetArrayLength(env, weights) < (jsize)((size_t)len * stride) ||
        (ring > 0 && ((*env)->GetArrayLength(env, ring_lo) != len || (*env)->GetArrayLength(env, ring_hi) != len))) {
        return JNI_FALSE;
    }
    jint * s = (*env)->GetIntArrayElements(env, start, NULL);
    jint * c = (*env)->GetIntArrayElements(env, count, NULL);
    jfloat * w = (*env)->GetFloatArrayElements(env, weights, NULL);
    jint * lo = ring > 0 ? (*env)->GetIntArrayElements(env, ring_lo, NULL) : NULL;
    jint * hi = ring > 0 ? (*env)->GetIntArrayElements(env, ring_hi, NULL) : NULL;
    rs_axis * a = vertical ? &h->job.ay : &h->job.ax;
    int ok = s != NULL && c != NULL && w != NULL && (ring <= 0 || (lo != NULL && hi != NULL)) &&
             rs_axis_init(a, len, stride, s, c, w, ring > 0 ? ring : 0, lo, hi) == 0;
    if (s != NULL) (*env)->ReleaseIntArrayElements(env, start, s, JNI_ABORT);
    if (c != NULL) (*env)->ReleaseIntArrayElements(env, count, c, JNI_ABORT);
    if (w != NULL) (*env)->ReleaseFloatArrayElements(env, weights, w, JNI_ABORT);
    if (lo != NULL) (*env)->ReleaseIntArrayElements(env, ring_lo, lo, JNI_ABORT);
    if (hi != NULL) (*env)->ReleaseIntArrayElements(env, ring_hi, hi, JNI_ABORT);
    if (!ok) return JNI_FALSE;
    h->axes |= bit;
    if (!taps_fit(a, src_len)) {
        h->axes |= 4;
        return JNI_FALSE;
    }
    if (h->axes == 3 && h->gpu) {
        h->on_gpu = rs_gpu_open(&h->job);
        if (h->on_gpu == NULL) return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Calls a PixelKernels.Stop's stop() between the chunks of a GPU band, on the band's thread.
typedef struct {
    JNIEnv * env;
    jobject callback;
    jmethodID stop;
    // What stop() threw, cleared so the band can end before rethrowing it.
    jthrowable thrown;
} stop_call;

static int stop_now(void * opaque)
{
    stop_call * c = opaque;
    const jboolean stop = (*c->env)->CallBooleanMethod(c->env, c->callback, c->stop);
    if ((*c->env)->ExceptionCheck(c->env)) {
        // An exception stops the band too.
        c->thrown = (*c->env)->ExceptionOccurred(c->env);
        (*c->env)->ExceptionClear(c->env);
        return 1;
    }
    return stop;
}

// Filters output rows y0 until y1 of a GPU job, asking stop (NULL: never) before each
// chunk, on the calling thread pinned to `cores` (0 = any) and counted by the core ledger
// while it runs, its row copies included; rs_gpu_band's result. Opening and closing the
// job stay where the thread was: threads the GPU driver starts while its context is made
// inherit the starter's cores for good.
static int gpu_band(handle * h, int y0, int y1, uint64_t cores, rs_stop stop, void * opaque)
{
    if (h->on_gpu == NULL) return -1;
    cpu_set_t kept;
    const int pinned = jr_pin(cores, &kept);
    cl_begin();
    const int r = rs_gpu_band(h->on_gpu, y0, y1, stop, opaque);
    cl_end();
    if (pinned) jr_unpin();
    return r;
}

// Moves the calling thread onto `cores` (bit n = core n), or every core for -1, until moved
// again (jr_pin_mask); the cores it could use before, 0 when it stays where it was.
EXPORT jlong JNICALL Java_com_infinity_comicreader_reader_LibPixels_nPin(JNIEnv * env, jclass clazz, jlong cores)
{
    (void)env;
    (void)clazz;
    return (jlong)jr_pin_mask((uint64_t)cores);
}

// Filters output rows y0 until y1 as `worker`, on `cores` (0 = any) while it runs; false
// on a bad request, out of memory or a GPU error.
EXPORT jboolean JNICALL Java_com_infinity_comicreader_reader_LibPixels_nBand(
    JNIEnv * env, jclass clazz, jlong job, jint worker, jint y0, jint y1, jlong cores)
{
    (void)env;
    (void)clazz;
    handle * h = (handle *)(intptr_t)job;
    if (h->axes != 3 || worker < 0 || worker >= h->worker_count || y0 < 0 || y1 > h->dst_h || y0 >= y1) return JNI_FALSE;
    if (h->gpu) return gpu_band(h, y0, y1, (uint64_t)cores, NULL, NULL) == 0;
    cpu_set_t kept;
    const int pinned = jr_pin((uint64_t)cores, &kept);
    cl_begin();
    const int r = rs_band(&h->job, &h->workers[worker], y0, y1);
    cl_end();
    if (pinned) jr_unpin();
    return r == 0;
}

// Turns on or off one log line per GPU job with the time of each step (rs_gpu_trace).
EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibPixels_nTrace(JNIEnv * env, jclass clazz, jboolean on)
{
    (void)env;
    (void)clazz;
    rs_gpu_trace(on == JNI_TRUE);
}

// Filters output rows y0 until y1 of a GPU job on the calling thread, `cores` as nBand
// says, asking `stop` (null: never) before each chunk: 1 when done, -2 once stopped
// (rethrowing what stop() threw), 0 on a bad request or a GPU error.
EXPORT jint JNICALL Java_com_infinity_comicreader_reader_LibPixels_nGpuBand(
    JNIEnv * env, jclass clazz, jlong job, jint y0, jint y1, jlong cores, jobject stop)
{
    (void)clazz;
    handle * h = (handle *)(intptr_t)job;
    if (h->axes != 3 || !h->gpu || y0 < 0 || y1 > h->dst_h || y0 >= y1) return 0;
    stop_call call = { env, stop, NULL, NULL };
    if (stop != NULL) {
        const jclass type = (*env)->GetObjectClass(env, stop);
        call.stop = (*env)->GetMethodID(env, type, "stop", "()Z");
        (*env)->DeleteLocalRef(env, type);
        if (call.stop == NULL) return 0;
    }
    const int r = gpu_band(h, y0, y1, (uint64_t)cores, stop != NULL ? stop_now : NULL, &call);
    if (call.thrown != NULL) {
        (*env)->Throw(env, call.thrown);
        (*env)->DeleteLocalRef(env, call.thrown);
    }
    return r == 0 ? 1 : r > 0 ? -2 : 0;
}

// Frees what the GPU path keeps between jobs (rs_gpu_trim).
EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibPixels_nTrim(JNIEnv * env, jclass clazz)
{
    (void)env;
    (void)clazz;
    rs_gpu_trim();
}

// Opens the core ledger (core_ledger.h), resetting it, or closes it.
EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibPixels_nLedger(JNIEnv * env, jclass clazz, jboolean open)
{
    (void)env;
    (void)clazz;
    cl_open(open == JNI_TRUE);
}

// Moves the ledger's nanoseconds per core into `out`, zeroing them; the count of cores.
EXPORT jint JNICALL Java_com_infinity_comicreader_reader_LibPixels_nLedgerNs(JNIEnv * env, jclass clazz, jlongArray out)
{
    (void)clazz;
    int64_t ns[CL_MAX_CORES];
    const jsize size = (*env)->GetArrayLength(env, out);
    const int n = cl_take_ns(ns, size < CL_MAX_CORES ? size : CL_MAX_CORES);
    (*env)->SetLongArrayRegion(env, out, 0, n, (const jlong *)ns);
    return n;
}

// Moves the ids of threads that counted in the ledger since the last call into `out`, the
// nanoseconds each counted into `ns`; their count.
EXPORT jint JNICALL Java_com_infinity_comicreader_reader_LibPixels_nLedgerThreads(JNIEnv * env, jclass clazz, jintArray out, jlongArray ns)
{
    (void)clazz;
    int32_t tids[CL_MAX_TIDS];
    int64_t spent_ns[CL_MAX_TIDS];
    jsize size = (*env)->GetArrayLength(env, out);
    const jsize size_ns = (*env)->GetArrayLength(env, ns);
    if (size_ns < size) size = size_ns;
    const int n = cl_take_threads(tids, spent_ns, size < CL_MAX_TIDS ? size : CL_MAX_TIDS);
    (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *)tids);
    (*env)->SetLongArrayRegion(env, ns, 0, n, (const jlong *)spent_ns);
    return n;
}

// Ends a job: unlocks both bitmaps and frees everything it holds.
EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibPixels_nEnd(JNIEnv * env, jclass clazz, jlong job)
{
    (void)clazz;
    release(env, (handle *)(intptr_t)job);
}
