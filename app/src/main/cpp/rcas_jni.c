// JNI binding of rcas.h for com.infinity.comicreader.reader.LibPixels: a job locks an
// ARGB_8888 bitmap, and the strips of pixels past its edges, until it ends; bands of rows
// are then sharpened in place from several threads at once, each with a worker index and
// optionally pinned to a set of cores.

#define _GNU_SOURCE
#include <android/bitmap.h>
#include <jni.h>
#include <sched.h>
#include <stdlib.h>

#include "core_ledger.h"
#include "jxl_runner.h"
#include "rcas.h"

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

// The bitmap and its top, bottom, left and right strips, by index.
#define SLOTS 5

typedef struct {
    jobject refs[SLOTS];
    int locked[SLOTS];
    rcas_job job;
    int opened;
    uint8_t * scratch;
    int workers;
} sharpen;

static void release(JNIEnv * env, sharpen * s)
{
    if (s->opened) rcas_close(&s->job);
    free(s->scratch);
    for (int i = 0; i < SLOTS; i++) {
        if (s->refs[i] == NULL) continue;
        if (s->locked[i]) AndroidBitmap_unlockPixels(env, s->refs[i]);
        (*env)->DeleteGlobalRef(env, s->refs[i]);
    }
    free(s);
}

// Locks bitmap into slot i when it is RGBA_8888 and w x h; its pixels and row stride, or NULL.
static uint8_t * lock(JNIEnv * env, sharpen * s, int i, jobject bitmap, int w, int h, size_t * stride)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (w > 0 && (int)info.width != w) || (h > 0 && (int)info.height != h)) {
        return NULL;
    }
    s->refs[i] = (*env)->NewGlobalRef(env, bitmap);
    void * p = NULL;
    if (s->refs[i] == NULL || AndroidBitmap_lockPixels(env, s->refs[i], &p) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    s->locked[i] = 1;
    *stride = info.stride;
    if (i == 0) {
        s->job.w = (int)info.width;
        s->job.h = (int)info.height;
    }
    return p;
}

// A job sharpening dst in place by `amount` in `bands` bands on `workers` workers, the given
// strips (null: none) past its edges; 0 when the bitmaps will not do or memory runs out.
EXPORT jlong JNICALL Java_com_infinity_comicreader_reader_LibPixels_nSharpenBegin(
    JNIEnv * env, jclass clazz, jobject dst, jobject top, jobject bottom, jobject left, jobject right, jfloat amount, jint bands,
    jint workers)
{
    (void)clazz;
    if (workers < 1 || bands < 1) return 0;
    sharpen * s = calloc(1, sizeof(*s));
    if (s == NULL) return 0;
    rcas_job * j = &s->job;
    size_t unused;
    j->px = lock(env, s, 0, dst, 0, 0, &j->stride);
    int ok = j->px != NULL;
    if (ok && top != NULL) ok = (j->top = lock(env, s, 1, top, j->w, 1, &unused)) != NULL;
    if (ok && bottom != NULL) ok = (j->bottom = lock(env, s, 2, bottom, j->w, 1, &unused)) != NULL;
    if (ok && left != NULL) ok = (j->left = lock(env, s, 3, left, 1, j->h, &j->left_stride)) != NULL;
    if (ok && right != NULL) ok = (j->right = lock(env, s, 4, right, 1, j->h, &j->right_stride)) != NULL;
    j->amount = amount;
    j->bands = bands;
    s->workers = workers;
    if (ok) s->scratch = malloc((size_t)workers * 8 * (size_t)j->w);
    if (!ok || s->scratch == NULL || rcas_open(j) != 0) {
        release(env, s);
        return 0;
    }
    s->opened = 1;
    return (jlong)(intptr_t)s;
}

// Sharpens band `band` as `worker`, on `cores` (0 = any) while it runs, counted by the core
// ledger; false on a bad request.
EXPORT jboolean JNICALL Java_com_infinity_comicreader_reader_LibPixels_nSharpenBand(
    JNIEnv * env, jclass clazz, jlong job, jint worker, jint band, jlong cores)
{
    (void)env;
    (void)clazz;
    sharpen * s = (sharpen *)(intptr_t)job;
    if (worker < 0 || worker >= s->workers || band < 0 || band >= s->job.bands) return JNI_FALSE;
    cpu_set_t kept;
    const int pinned = jr_pin((uint64_t)cores, &kept);
    cl_begin();
    rcas_band(&s->job, band, s->scratch + (size_t)worker * 8 * (size_t)s->job.w);
    cl_end();
    if (pinned) jr_unpin();
    return JNI_TRUE;
}

// Ends a job: unlocks its bitmaps and frees what it holds.
EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibPixels_nSharpenEnd(JNIEnv * env, jclass clazz, jlong job)
{
    (void)clazz;
    release(env, (sharpen *)(intptr_t)job);
}
