// sched_setaffinity and cpu_set_t.
#define _GNU_SOURCE

#include "jxl_page.h"

#include <dlfcn.h>
#include <pthread.h>
#include <string.h>

#include "jxl_runner.h"

static pthread_once_t coder_once = PTHREAD_ONCE_INIT;
static jp_api coder_api;
static const jp_api * coder_ready;

static void load_coder(void)
{
    void * lib = dlopen("libjxl.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) return;
#define JP_RESOLVE(name)                                            \
    coder_api.name = (__typeof__(&name))dlsym(lib, #name);          \
    if (coder_api.name == NULL) return;
    JP_FUNCTIONS(JP_RESOLVE)
#undef JP_RESOLVE
    coder_ready = &coder_api;
}

const jp_api * jp_coder(void)
{
    pthread_once(&coder_once, load_coder);
    return coder_ready;
}

// True for sRGB (gray or color): D65 white, sRGB primaries and transfer.
static int is_srgb(const JxlColorEncoding * c)
{
    if (c->white_point != JXL_WHITE_POINT_D65 || c->transfer_function != JXL_TRANSFER_FUNCTION_SRGB) return 0;
    if (c->color_space == JXL_COLOR_SPACE_GRAY) return 1;
    return c->color_space == JXL_COLOR_SPACE_RGB && c->primaries == JXL_PRIMARIES_SRGB;
}

// sRGB, gray or color, as the encoder's JxlColorEncodingSetToSRGB gives it (not in a decoder-only build).
static void set_srgb(JxlColorEncoding * c, int gray)
{
    memset(c, 0, sizeof(*c));
    c->color_space = gray ? JXL_COLOR_SPACE_GRAY : JXL_COLOR_SPACE_RGB;
    c->white_point = JXL_WHITE_POINT_D65;
    c->primaries = JXL_PRIMARIES_SRGB;
    c->transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
    c->rendering_intent = JXL_RENDERING_INTENT_RELATIVE;
}

// Premultiplies `height` rows of RGBA by their alpha, as a bitmap holds it.
static void premultiply(uint8_t * out, uint32_t width, uint32_t height, uint32_t stride)
{
    for (uint32_t y = 0; y < height; y++) {
        uint8_t * p = out + (size_t)y * stride;
        for (uint32_t x = 0; x < width; x++, p += 4) {
            const unsigned a = p[3];
            if (a == 255) continue;
            p[0] = (uint8_t)((p[0] * a + 127) / 255);
            p[1] = (uint8_t)((p[1] * a + 127) / 255);
            p[2] = (uint8_t)((p[2] * a + 127) / 255);
        }
    }
}

int jp_decode(const jp_api * api, const uint8_t * data, size_t size, uint8_t * out, uint32_t width, uint32_t height, uint32_t stride,
              int threads, uint64_t cores, int (*stop)(void *), void * stop_opaque)
{
    if (api == NULL || width == 0 || height == 0 || stride < width * 4) return JP_UNSUPPORTED;
    JxlDecoder * dec = api->JxlDecoderCreate(NULL);
    if (dec == NULL) return JP_FAILED;
    const int events = JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE;
    jr_pool * pool = NULL;
    cpu_set_t kept;
    const int pinned = jr_pin(cores, &kept);
    int result = JP_FAILED;
    int alpha = 0;
    JxlBasicInfo info;
    memset(&info, 0, sizeof(info));
    // A scanline of `stride` bytes: the smallest multiple of `stride` that holds a row.
    const JxlPixelFormat format = { 4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, stride == width * 4 ? 0 : stride };
    if (api->JxlDecoderSubscribeEvents(dec, events) != JXL_DEC_SUCCESS ||
        api->JxlDecoderSetInput(dec, data, size) != JXL_DEC_SUCCESS) {
        goto done;
    }
    api->JxlDecoderCloseInput(dec);
    // A pool even for one thread: its runner asks `stop`.
    pool = jr_create(threads > 1 ? threads - 1 : 0, cores);
    if (pool == NULL || api->JxlDecoderSetParallelRunner(dec, jr_run, pool) != JXL_DEC_SUCCESS) goto done;
    if (stop != NULL) jr_stop_when(pool, stop, stop_opaque);
    for (;;) {
        const JxlDecoderStatus status = api->JxlDecoderProcessInput(dec);
        if (status == JXL_DEC_BASIC_INFO) {
            if (api->JxlDecoderGetBasicInfo(dec, &info) != JXL_DEC_SUCCESS) goto done;
            const int turned = info.orientation >= JXL_ORIENT_TRANSPOSE;
            if ((turned ? info.ysize : info.xsize) != width || (turned ? info.xsize : info.ysize) != height ||
                info.intensity_target > 255.0f) {
                result = JP_UNSUPPORTED;
                goto done;
            }
            alpha = info.alpha_bits > 0;
        } else if (status == JXL_DEC_COLOR_ENCODING) {
            JxlColorEncoding color;
            const int srgb = api->JxlDecoderGetColorAsEncodedProfile(dec, JXL_COLOR_PROFILE_TARGET_ORIGINAL, &color) == JXL_DEC_SUCCESS &&
                             is_srgb(&color);
            if (!srgb) {
                // Only XYB (lossy) images convert without a CMS; libjxl refuses the rest.
                set_srgb(&color, info.num_color_channels == 1);
                if (api->JxlDecoderSetPreferredColorProfile(dec, &color) != JXL_DEC_SUCCESS) {
                    result = JP_UNSUPPORTED;
                    goto done;
                }
            }
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            size_t need = 0;
            if (api->JxlDecoderImageOutBufferSize(dec, &format, &need) != JXL_DEC_SUCCESS || need > (size_t)stride * height) goto done;
            if (api->JxlDecoderSetImageOutBuffer(dec, &format, out, (size_t)stride * height) != JXL_DEC_SUCCESS) goto done;
        } else if (status == JXL_DEC_FULL_IMAGE) {
            // The first frame only; alpha stored premultiplied comes out as stored.
            if (alpha && !info.alpha_premultiplied) premultiply(out, width, height, stride);
            result = alpha ? JP_ALPHA : JP_OPAQUE;
            goto done;
        } else {
            goto done;
        }
    }
done:
    if (pool != NULL && jr_canceled(pool)) result = JP_CANCELED;
    api->JxlDecoderDestroy(dec);
    jr_destroy(pool);
    if (pinned) sched_setaffinity(0, sizeof(kept), &kept);
    return result;
}
