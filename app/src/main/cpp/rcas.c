// RCAS on the CPU (rcas.h): Rcas.pixel's steps in fp32, in its order, so results match it
// bit for bit; built without contracting multiply-adds (CMakeLists.txt). On arm64 four
// pixels go at once through NEON, the edge columns and the rest one at a time.

#include "rcas.h"

#include <stdlib.h>
#include <string.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#if defined(__clang__)
#pragma clang fp contract(off)
#endif

#define LIMIT 0.1875f

static inline float min_f(float a, float b) { return a < b ? a : b; }

static inline float max_f(float a, float b) { return a > b ? a : b; }

static inline float luma(float r, float g, float b) { return b * 0.5f + (r * 0.5f + g); }

// The most negative lobe one channel allows (Rcas.lobe).
static inline float channel_lobe(float b, float d, float e, float f, float h)
{
    const float mn = min_f(min_f(b, d), min_f(f, h));
    const float mx = max_f(max_f(b, d), max_f(f, h));
    const float hit_min = mx > 0.0f ? min_f(mn, e) / (4.0f * mx) : 1.0f;
    const float hit_max = mn < 255.0f ? (255.0f - max_f(mx, e)) / (4.0f * mn - 1020.0f) : -1.0f;
    return max_f(-hit_min, hit_max);
}

static inline uint32_t code(float v) { return (uint32_t)(min_f(max_f(v, 0.0f), 255.0f) + 0.5f); }

#define R(p) ((float)((p) & 0xFF))
#define G(p) ((float)(((p) >> 8) & 0xFF))
#define B(p) ((float)(((p) >> 16) & 0xFF))

// Rcas.pixel on words read from memory: red in the low byte, alpha in the high one.
static uint32_t pixel(uint32_t b, uint32_t d, uint32_t e, uint32_t f, uint32_t h, float amount)
{
    if (((b & d & e & f & h) >> 24) != 0xFF) return e;
    const float bL = luma(R(b), G(b), B(b));
    const float dL = luma(R(d), G(d), B(d));
    const float eL = luma(R(e), G(e), B(e));
    const float fL = luma(R(f), G(f), B(f));
    const float hL = luma(R(h), G(h), B(h));
    const float pass = 0.25f * bL + 0.25f * dL + 0.25f * fL + 0.25f * hL - eL;
    const float range = max_f(max_f(max_f(bL, dL), max_f(eL, fL)), hL) - min_f(min_f(min_f(bL, dL), min_f(eL, fL)), hL);
    const float apass = pass < 0.0f ? -pass : pass;
    const float noise = range > 0.0f ? min_f(apass / range, 1.0f) : 0.0f;
    const float nz = 1.0f - 0.5f * noise;
    const float lr = channel_lobe(R(b), R(d), R(e), R(f), R(h));
    const float lg = channel_lobe(G(b), G(d), G(e), G(f), G(h));
    const float lb = channel_lobe(B(b), B(d), B(e), B(f), B(h));
    const float lobe = max_f(-LIMIT, min_f(max_f(lr, max_f(lg, lb)), 0.0f)) * amount * nz;
    const float div = 4.0f * lobe + 1.0f;
    const float r = (lobe * (R(b) + R(d) + R(f) + R(h)) + R(e)) / div;
    const float g = (lobe * (G(b) + G(d) + G(f) + G(h)) + G(e)) / div;
    const float bl = (lobe * (B(b) + B(d) + B(f) + B(h)) + B(e)) / div;
    return code(r) | code(g) << 8 | code(bl) << 16 | 0xFF000000u;
}

static inline uint32_t word(const uint8_t * p)
{
    uint32_t v;
    memcpy(&v, p, 4);
    return v;
}

static inline void put(uint8_t * p, uint32_t v) { memcpy(p, &v, 4); }

#if defined(__aarch64__)
static inline float32x4_t chan(uint32x4_t p, int shift)
{
    return vcvtq_f32_u32(vandq_u32(vshlq_u32(p, vdupq_n_s32(-shift)), vdupq_n_u32(0xFF)));
}

static inline float32x4_t luma4(float32x4_t r, float32x4_t g, float32x4_t b)
{
    const float32x4_t half = vdupq_n_f32(0.5f);
    return vaddq_f32(vmulq_f32(b, half), vaddq_f32(vmulq_f32(r, half), g));
}

static inline float32x4_t lobe4(float32x4_t b, float32x4_t d, float32x4_t e, float32x4_t f, float32x4_t h)
{
    const float32x4_t mn = vminq_f32(vminq_f32(b, d), vminq_f32(f, h));
    const float32x4_t mx = vmaxq_f32(vmaxq_f32(b, d), vmaxq_f32(f, h));
    const float32x4_t four = vdupq_n_f32(4.0f);
    const float32x4_t hit_min = vbslq_f32(vcgtq_f32(mx, vdupq_n_f32(0.0f)), vdivq_f32(vminq_f32(mn, e), vmulq_f32(four, mx)),
                                          vdupq_n_f32(1.0f));
    const float32x4_t hit_max = vbslq_f32(vcltq_f32(mn, vdupq_n_f32(255.0f)),
                                          vdivq_f32(vsubq_f32(vdupq_n_f32(255.0f), vmaxq_f32(mx, e)),
                                                    vsubq_f32(vmulq_f32(four, mn), vdupq_n_f32(1020.0f))),
                                          vdupq_n_f32(-1.0f));
    return vmaxq_f32(vnegq_f32(hit_min), hit_max);
}

static inline uint32x4_t code4(float32x4_t v)
{
    return vcvtq_u32_f32(vaddq_f32(vminq_f32(vmaxq_f32(v, vdupq_n_f32(0.0f)), vdupq_n_f32(255.0f)), vdupq_n_f32(0.5f)));
}

static inline float32x4_t out4(float32x4_t lobe, float32x4_t div, float32x4_t b, float32x4_t d, float32x4_t e, float32x4_t f,
                               float32x4_t h)
{
    const float32x4_t sum = vaddq_f32(vaddq_f32(vaddq_f32(b, d), f), h);
    return vdivq_f32(vaddq_f32(vmulq_f32(lobe, sum), e), div);
}

// pixel() on four pixels at once.
static uint32x4_t pixel4(uint32x4_t b, uint32x4_t d, uint32x4_t e, uint32x4_t f, uint32x4_t h, float amount)
{
    const uint32x4_t all = vandq_u32(vandq_u32(vandq_u32(b, d), vandq_u32(f, h)), e);
    const uint32x4_t opaque = vceqq_u32(vshrq_n_u32(all, 24), vdupq_n_u32(0xFF));
    const float32x4_t bR = chan(b, 0), bG = chan(b, 8), bB = chan(b, 16);
    const float32x4_t dR = chan(d, 0), dG = chan(d, 8), dB = chan(d, 16);
    const float32x4_t eR = chan(e, 0), eG = chan(e, 8), eB = chan(e, 16);
    const float32x4_t fR = chan(f, 0), fG = chan(f, 8), fB = chan(f, 16);
    const float32x4_t hR = chan(h, 0), hG = chan(h, 8), hB = chan(h, 16);
    const float32x4_t bL = luma4(bR, bG, bB);
    const float32x4_t dL = luma4(dR, dG, dB);
    const float32x4_t eL = luma4(eR, eG, eB);
    const float32x4_t fL = luma4(fR, fG, fB);
    const float32x4_t hL = luma4(hR, hG, hB);
    const float32x4_t quarter = vdupq_n_f32(0.25f);
    const float32x4_t pass = vsubq_f32(
        vaddq_f32(vaddq_f32(vaddq_f32(vmulq_f32(quarter, bL), vmulq_f32(quarter, dL)), vmulq_f32(quarter, fL)), vmulq_f32(quarter, hL)),
        eL);
    const float32x4_t range = vsubq_f32(vmaxq_f32(vmaxq_f32(vmaxq_f32(bL, dL), vmaxq_f32(eL, fL)), hL),
                                        vminq_f32(vminq_f32(vminq_f32(bL, dL), vminq_f32(eL, fL)), hL));
    const float32x4_t zero = vdupq_n_f32(0.0f);
    const float32x4_t noise = vbslq_f32(vcgtq_f32(range, zero), vminq_f32(vdivq_f32(vabsq_f32(pass), range), vdupq_n_f32(1.0f)), zero);
    const float32x4_t nz = vsubq_f32(vdupq_n_f32(1.0f), vmulq_f32(vdupq_n_f32(0.5f), noise));
    const float32x4_t lr = lobe4(bR, dR, eR, fR, hR);
    const float32x4_t lg = lobe4(bG, dG, eG, fG, hG);
    const float32x4_t lb = lobe4(bB, dB, eB, fB, hB);
    const float32x4_t clamped = vmaxq_f32(vdupq_n_f32(-LIMIT), vminq_f32(vmaxq_f32(lr, vmaxq_f32(lg, lb)), zero));
    const float32x4_t lobe = vmulq_f32(vmulq_f32(clamped, vdupq_n_f32(amount)), nz);
    const float32x4_t div = vaddq_f32(vmulq_f32(vdupq_n_f32(4.0f), lobe), vdupq_n_f32(1.0f));
    const uint32x4_t r = code4(out4(lobe, div, bR, dR, eR, fR, hR));
    const uint32x4_t g = code4(out4(lobe, div, bG, dG, eG, fG, hG));
    const uint32x4_t bl = code4(out4(lobe, div, bB, dB, eB, fB, hB));
    const uint32x4_t packed = vorrq_u32(vorrq_u32(r, vshlq_n_u32(g, 8)), vorrq_u32(vshlq_n_u32(bl, 16), vdupq_n_u32(0xFF000000u)));
    return vbslq_u32(opaque, packed, e);
}

static inline uint32x4_t load4(const uint8_t * p) { return vreinterpretq_u32_u8(vld1q_u8(p)); }
#endif

void rcas_row(uint8_t * out, const uint8_t * up, const uint8_t * mid, const uint8_t * down, int w, uint32_t left, uint32_t right,
              float amount)
{
    if (w == 1) {
        put(out, pixel(word(up), left, word(mid), right, word(down), amount));
        return;
    }
    put(out, pixel(word(up), left, word(mid), word(mid + 4), word(down), amount));
    int x = 1;
#if defined(__aarch64__)
    for (; x + 4 <= w - 1; x += 4) {
        const size_t o = (size_t)x * 4;
        vst1q_u8(out + o, vreinterpretq_u8_u32(pixel4(load4(up + o), load4(mid + o - 4), load4(mid + o), load4(mid + o + 4), load4(down + o), amount)));
    }
#endif
    for (; x < w - 1; x++) {
        const size_t o = (size_t)x * 4;
        put(out + o, pixel(word(up + o), word(mid + o - 4), word(mid + o), word(mid + o + 4), word(down + o), amount));
    }
    const size_t o = (size_t)(w - 1) * 4;
    put(out + o, pixel(word(up + o), word(mid + o - 4), word(mid + o), right, word(down + o), amount));
}

int rcas_band_start(int h, int bands, int b) { return (int)((int64_t)h * b / bands); }

int rcas_open(rcas_job * j)
{
    j->saved = NULL;
    if (j->w < 1 || j->h < 1 || j->bands < 1 || j->bands > j->h) return -1;
    const size_t row = (size_t)j->w * 4;
    if (j->bands > 1) {
        j->saved = malloc(row * 2 * (size_t)j->bands);
        if (j->saved == NULL) return -1;
    }
    for (int b = 1; b < j->bands; b++) {
        const int y0 = rcas_band_start(j->h, j->bands, b);
        memcpy(j->saved + row * (2 * (size_t)b), j->px + (size_t)(y0 - 1) * j->stride, row);
        memcpy(j->saved + row * (2 * (size_t)b + 1), j->px + (size_t)y0 * j->stride, row);
    }
    return 0;
}

void rcas_close(rcas_job * j)
{
    free(j->saved);
    j->saved = NULL;
}

void rcas_band(const rcas_job * j, int b, uint8_t * scratch)
{
    const int w = j->w;
    const int h = j->h;
    const size_t row = (size_t)w * 4;
    const int y0 = rcas_band_start(h, j->bands, b);
    const int y1 = rcas_band_start(h, j->bands, b + 1);
    uint8_t * prev = scratch;
    uint8_t * cur = scratch + row;
    // Row y - 1 as it was: the band above's last, saved; or what lies past the top edge.
    const uint8_t * up = y0 > 0 ? j->saved + row * (2 * (size_t)b) : j->top;
    for (int y = y0; y < y1; y++) {
        uint8_t * at = j->px + (size_t)y * j->stride;
        memcpy(cur, at, row);
        const uint8_t * down;
        if (y + 1 < y1) down = at + j->stride;
        else if (y + 1 < h) down = j->saved + row * (2 * (size_t)b + 3);
        else down = j->bottom != NULL ? j->bottom : cur;
        const uint32_t left = j->left != NULL ? word(j->left + (size_t)y * j->left_stride) : word(cur);
        const uint32_t right = j->right != NULL ? word(j->right + (size_t)y * j->right_stride) : word(cur + row - 4);
        rcas_row(at, up != NULL ? up : cur, cur, down, w, left, right, j->amount);
        uint8_t * t = prev;
        prev = cur;
        cur = t;
        up = prev;
    }
}
