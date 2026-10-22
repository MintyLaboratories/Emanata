// JNI binding over libavif for com.infinity.comicreader.reader.LibAvif: parse an
// AVIF file, step through its images (the layers of a layered file one by one
// when asked), and convert the current one to RGBA_8888 bitmap pixels.

#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "avif/avif.h"

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

// Largest frame decoded, in pixels (Avif.MAX_PIXELS) and per side.
#define MAX_PIXELS 40000000u
#define MAX_DIMENSION 32768u

// Bytes of a file, all of it or only its start. Reads past a start return
// AVIF_RESULT_WAITING_ON_IO, which callers take as "more bytes needed".
typedef struct {
    avifIO io;
    uint8_t * data;
    size_t length;
    avifBool complete;
} Input;

typedef struct {
    avifDecoder * decoder;
    Input input;
} Handle;

static avifResult inputRead(struct avifIO * io, uint32_t readFlags, uint64_t offset, size_t size, avifROData * out)
{
    const Input * input = (const Input *)io;
    if (readFlags != 0) {
        return AVIF_RESULT_IO_ERROR;
    }
    if (offset > input->length) {
        return input->complete ? AVIF_RESULT_IO_ERROR : AVIF_RESULT_WAITING_ON_IO;
    }
    const uint64_t left = input->length - offset;
    if (size > left) {
        if (!input->complete) {
            return AVIF_RESULT_WAITING_ON_IO;
        }
        size = (size_t)left;
    }
    out->data = input->data + offset;
    out->size = size;
    return AVIF_RESULT_OK;
}

static void inputDestroy(struct avifIO * io)
{
    (void)io;
}

static void throwIo(JNIEnv * env, const char * what, avifResult result, const avifDecoder * decoder)
{
    char message[320];
    const char * detail = decoder != NULL ? decoder->diag.error : "";
    if (detail[0] != '\0') {
        snprintf(message, sizeof(message), "%s: %s (%s)", what, avifResultToString(result), detail);
    } else {
        snprintf(message, sizeof(message), "%s: %s", what, avifResultToString(result));
    }
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
}

static void throwMemory(JNIEnv * env)
{
    jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, "AVIF decoder");
    }
}

static void destroy(Handle * handle)
{
    if (handle->decoder != NULL) {
        avifDecoderDestroy(handle->decoder);
    }
    free(handle->input.data);
    free(handle);
}

// Opens the first [length] bytes of [data] (the whole file when [complete]) and
// parses its container. Returns 0 when its start holds too little to parse.
EXPORT jlong JNICALL Java_com_infinity_comicreader_reader_LibAvif_nOpen(JNIEnv * env,
                                                                     jclass type,
                                                                     jbyteArray data,
                                                                     jint length,
                                                                     jboolean complete,
                                                                     jint threads,
                                                                     jboolean layers)
{
    (void)type;
    if (length < 0 || length > (*env)->GetArrayLength(env, data)) {
        throwIo(env, "Bad length", AVIF_RESULT_INVALID_ARGUMENT, NULL);
        return 0;
    }
    Handle * handle = (Handle *)calloc(1, sizeof(Handle));
    if (handle == NULL) {
        throwMemory(env);
        return 0;
    }
    handle->input.data = (uint8_t *)malloc(length > 0 ? (size_t)length : 1);
    handle->decoder = avifDecoderCreate();
    if (handle->input.data == NULL || handle->decoder == NULL) {
        destroy(handle);
        throwMemory(env);
        return 0;
    }
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)handle->input.data);
    handle->input.length = (size_t)length;
    handle->input.complete = complete ? AVIF_TRUE : AVIF_FALSE;
    handle->input.io.read = inputRead;
    handle->input.io.destroy = inputDestroy;
    handle->input.io.sizeHint = complete ? (uint64_t)length : 0;
    handle->input.io.persistent = AVIF_TRUE;

    avifDecoder * decoder = handle->decoder;
    decoder->maxThreads = threads > 0 ? threads : 1;
    decoder->allowProgressive = layers ? AVIF_TRUE : AVIF_FALSE;
    decoder->ignoreExif = AVIF_TRUE;
    decoder->ignoreXMP = AVIF_TRUE;
    // Like other image viewers, shows files that bend the rules but still decode.
    decoder->strictFlags = AVIF_STRICT_DISABLED;
    avifDecoderSetIO(decoder, &handle->input.io);

    const avifResult result = avifDecoderParse(decoder);
    if (result == AVIF_RESULT_WAITING_ON_IO && !complete) {
        destroy(handle);
        return 0;
    }
    if (result != AVIF_RESULT_OK) {
        throwIo(env, "Could not read AVIF", result, decoder);
        destroy(handle);
        return 0;
    }
    // Bounds every decoded frame, which may be larger than the size the file declares (an
    // inner layer keeps its own size). Set after parsing, so a declared size past it is
    // refused by Avif with its own message; the AV1 decoder takes it as it starts.
    decoder->imageSizeLimit = MAX_PIXELS;
    decoder->imageDimensionLimit = MAX_DIMENSION;
    return (jlong)(intptr_t)handle;
}

// What is known of the image: see LibAvif.info for the order.
EXPORT jintArray JNICALL Java_com_infinity_comicreader_reader_LibAvif_nInfo(JNIEnv * env, jclass type, jlong ptr)
{
    (void)type;
    const Handle * handle = (const Handle *)(intptr_t)ptr;
    const avifDecoder * decoder = handle->decoder;
    const avifImage * image = decoder->image;
    jint crop[4] = { -1, -1, -1, -1 };
    if (image->transformFlags & AVIF_TRANSFORM_CLAP) {
        avifCropRect rect;
        avifDiagnostics diag;
        if (avifCropRectFromCleanApertureBox(&rect, &image->clap, image->width, image->height, &diag)) {
            crop[0] = (jint)rect.x;
            crop[1] = (jint)rect.y;
            crop[2] = (jint)rect.width;
            crop[3] = (jint)rect.height;
        }
    }
    const jint values[] = {
        (jint)image->width,
        (jint)image->height,
        (jint)image->depth,
        (jint)image->yuvFormat,
        image->yuvRange == AVIF_RANGE_FULL ? 1 : 0,
        decoder->alphaPresent ? 1 : 0,
        decoder->imageCount,
        (jint)decoder->progressiveState,
        (jint)image->colorPrimaries,
        (jint)image->transferCharacteristics,
        (jint)image->matrixCoefficients,
        (jint)image->transformFlags,
        (jint)image->irot.angle,
        (jint)image->imir.axis,
        crop[0],
        crop[1],
        crop[2],
        crop[3],
        (jint)image->icc.size,
        decoder->imageIndex,
    };
    const jsize count = (jsize)(sizeof(values) / sizeof(values[0]));
    jintArray out = (*env)->NewIntArray(env, count);
    if (out != NULL) {
        (*env)->SetIntArrayRegion(env, out, 0, count, values);
    }
    return out;
}

// Decodes the next image (the next layer of a layered file opened with layers).
// False when the bytes given so far end before it.
EXPORT jboolean JNICALL Java_com_infinity_comicreader_reader_LibAvif_nNext(JNIEnv * env, jclass type, jlong ptr)
{
    (void)type;
    Handle * handle = (Handle *)(intptr_t)ptr;
    const avifResult result = avifDecoderNextImage(handle->decoder);
    if (result == AVIF_RESULT_OK) {
        return JNI_TRUE;
    }
    if (result == AVIF_RESULT_WAITING_ON_IO && !handle->input.complete) {
        return JNI_FALSE;
    }
    throwIo(env, "Could not decode AVIF", result, handle->decoder);
    return JNI_FALSE;
}

// Rows converted at once when sampling: a multiple of every sample and of the
// chroma subsampling.
#define BAND_SAMPLES 16

// Writes the current image into [bitmap], an RGBA_8888 bitmap of its size, or of
// its size divided by [sample] (rounded up), keeping every sample-th pixel.
EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibAvif_nRender(JNIEnv * env,
                                                                      jclass type,
                                                                      jlong ptr,
                                                                      jobject bitmap,
                                                                      jint sample,
                                                                      jint threads)
{
    (void)type;
    const Handle * handle = (const Handle *)(intptr_t)ptr;
    const avifImage * image = handle->decoder->image;
    if (image->yuvPlanes[AVIF_CHAN_Y] == NULL || sample < 1) {
        throwIo(env, "No AVIF image to render", AVIF_RESULT_INVALID_ARGUMENT, NULL);
        return;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != (image->width + (uint32_t)sample - 1) / (uint32_t)sample ||
        info.height != (image->height + (uint32_t)sample - 1) / (uint32_t)sample) {
        throwIo(env, "Bitmap does not match the AVIF image", AVIF_RESULT_INVALID_ARGUMENT, NULL);
        return;
    }
    void * pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) {
        throwIo(env, "Could not write the bitmap", AVIF_RESULT_UNKNOWN_ERROR, NULL);
        return;
    }

    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 8;
    // Android's bitmaps hold premultiplied alpha.
    rgb.alphaPremultiplied = AVIF_TRUE;
    rgb.maxThreads = threads > 0 ? threads : 1;

    avifResult result = AVIF_RESULT_OK;
    if (sample == 1) {
        rgb.pixels = (uint8_t *)pixels;
        rgb.rowBytes = info.stride;
        result = avifImageYUVToRGB(image, &rgb);
    } else {
        // Bands of whole rows converted in turn, every sample-th pixel of every
        // sample-th row kept: the full image never needs RGBA memory of its own.
        const uint32_t band = (uint32_t)sample * BAND_SAMPLES;
        const size_t rowBytes = (size_t)image->width * 4;
        uint8_t * buffer = (uint8_t *)malloc(rowBytes * band);
        avifImage * view = avifImageCreateEmpty();
        if (buffer == NULL || view == NULL) {
            result = AVIF_RESULT_OUT_OF_MEMORY;
        }
        for (uint32_t top = 0; result == AVIF_RESULT_OK && top < image->height; top += band) {
            const avifCropRect rect = { 0, top, image->width, image->height - top < band ? image->height - top : band };
            result = avifImageSetViewRect(view, image, &rect);
            if (result != AVIF_RESULT_OK) {
                break;
            }
            rgb.width = rect.width;
            rgb.height = rect.height;
            rgb.pixels = buffer;
            rgb.rowBytes = (uint32_t)rowBytes;
            result = avifImageYUVToRGB(view, &rgb);
            for (uint32_t row = 0; result == AVIF_RESULT_OK && row < rect.height; row += (uint32_t)sample) {
                const uint32_t * from = (const uint32_t *)(buffer + (size_t)row * rowBytes);
                uint32_t * to = (uint32_t *)((uint8_t *)pixels + (size_t)((top + row) / (uint32_t)sample) * info.stride);
                for (uint32_t x = 0; x < info.width; ++x) {
                    to[x] = from[(size_t)x * (uint32_t)sample];
                }
            }
        }
        if (view != NULL) {
            avifImageDestroy(view);
        }
        free(buffer);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (result == AVIF_RESULT_OUT_OF_MEMORY) {
        throwMemory(env);
    } else if (result != AVIF_RESULT_OK) {
        throwIo(env, "Could not convert AVIF", result, NULL);
    }
}

EXPORT void JNICALL Java_com_infinity_comicreader_reader_LibAvif_nClose(JNIEnv * env, jclass type, jlong ptr)
{
    (void)env;
    (void)type;
    if (ptr != 0) {
        destroy((Handle *)(intptr_t)ptr);
    }
}
