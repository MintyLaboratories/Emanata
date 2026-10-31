// Decoding a JPEG XL page straight into a bitmap's pixels, on a pool of threads pinned to
// chosen cores (jxl_runner.h), through either libjxl build (jp_api).
#pragma once

#include <stddef.h>
#include <stdint.h>

#include <jxl/decode.h>

// Every libjxl decoder function a page decode calls.
#define JP_FUNCTIONS(X)                                                                                         \
    X(JxlDecoderCreate) X(JxlDecoderDestroy) X(JxlDecoderSubscribeEvents) X(JxlDecoderSetInput)                \
    X(JxlDecoderCloseInput) X(JxlDecoderProcessInput) X(JxlDecoderGetBasicInfo)                                \
    X(JxlDecoderGetColorAsEncodedProfile) X(JxlDecoderSetPreferredColorProfile) X(JxlDecoderSetImageOutBuffer) \
    X(JxlDecoderImageOutBufferSize) X(JxlDecoderSetParallelRunner)                                              \
    X(JxlDecoderSetProgressiveDetail) X(JxlDecoderFlushImage) X(JxlDecoderVersion)

// A libjxl build's decoder: jxl-coder's libjxl.so, or the one built here.
typedef struct {
#define JP_FIELD(name) __typeof__(&name) name;
    JP_FUNCTIONS(JP_FIELD)
#undef JP_FIELD
} jp_api;

// jxl-coder's libjxl.so, opened once; null when it is missing or lacks a function.
const jp_api * jp_coder(void);

// The decoder built from source here (jxl_own.c); null in builds without it.
const jp_api * jp_own(void);

// What a page decode returned.
enum {
    // Decoded, every pixel opaque.
    JP_OPAQUE = 1,
    // Decoded, with alpha, premultiplied as a bitmap holds it.
    JP_ALPHA = 2,
    // Not decoded: the image is not of this kind (another size, no sRGB rendering, HDR);
    // another decoder may take it.
    JP_UNSUPPORTED = -1,
    // Not decoded: the bytes did not decode, or the decode was stopped.
    JP_FAILED = 0,
};

// Where a decode sends its preview: the frame as its 1:8 (DC) pass gives it, once,
// written into the page's pixels; `ready` returns nonzero to keep decoding.
typedef struct {
    int (*ready)(void * opaque);
    void * opaque;
} jp_preview;

// Decodes the first frame of `data` in sRGB as RGBA, 8 bits a sample, into `out`: rows of
// `stride` bytes for an image shown `width` x `height` (its orientation applied), on
// `threads` threads (the caller and threads - 1 more) pinned to `cores` (0 = any). With
// `preview`, hands it the DC pass first. A JP_* result.
int jp_decode(const jp_api * api, const uint8_t * data, size_t size, uint8_t * out, uint32_t width, uint32_t height, uint32_t stride,
              int threads, uint64_t cores, const jp_preview * preview);
