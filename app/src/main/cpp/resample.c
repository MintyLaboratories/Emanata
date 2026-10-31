// The CPU path of resample.h: Resampler's algorithm step for step, in fp32 like it.
// - A source row is read once into 0..1 floats ([R G B A], premultiplied; in linear light
//   a translucent pixel's colors are divided by alpha to a code, decoded, multiplied again),
//   filtered horizontally into the worker's ring, then anti-ringed when the axis asks.
// - Each output row sums its ring rows, is anti-ringed, and is packed to premultiplied
//   bytes: alpha clamped to 0..1, colors to 0..alpha.
// - On arm64 each pixel is one NEON vector; four outputs are filtered at once where their
//   tap counts match, and the vertical pass works on four pixels a step.

#include "resample.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

static float to_unit[256];
static float to_linear[256];
static uint8_t from_linear[65536];
static pthread_once_t tables_once = PTHREAD_ONCE_INIT;

static void make_tables(void)
{
    for (int i = 0; i < 256; i++) {
        to_unit[i] = (float)i / 255.0f;
        const double v = i / 255.0;
        to_linear[i] = (float)(v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4));
    }
    for (int i = 0; i < 65536; i++) {
        const double v = i / 65535.0;
        const double s = v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1 / 2.4) - 0.055;
        const double code = floor(s * 255.0 + 0.5);
        from_linear[i] = (uint8_t)(code < 0 ? 0 : code > 255 ? 255 : code);
    }
}

const float * rs_to_linear(void)
{
    pthread_once(&tables_once, make_tables);
    return to_linear;
}

const uint8_t * rs_from_linear(void)
{
    pthread_once(&tables_once, make_tables);
    return from_linear;
}

int rs_axis_init(rs_axis * a, int len, int stride, const int32_t * start, const int32_t * count, const float * weights,
                 float ring, const int32_t * ring_lo, const int32_t * ring_hi)
{
    memset(a, 0, sizeof(*a));
    a->len = len;
    a->stride = stride;
    a->ring = ring;
    a->start = malloc(sizeof(int32_t) * (size_t)len);
    a->count = malloc(sizeof(int32_t) * (size_t)len);
    a->weights = malloc(sizeof(float) * (size_t)len * (size_t)stride);
    if (ring > 0) {
        a->ring_lo = malloc(sizeof(int32_t) * (size_t)len);
        a->ring_hi = malloc(sizeof(int32_t) * (size_t)len);
    }
    if (!a->start || !a->count || !a->weights || (ring > 0 && (!a->ring_lo || !a->ring_hi))) {
        rs_axis_free(a);
        return -1;
    }
    memcpy(a->start, start, sizeof(int32_t) * (size_t)len);
    memcpy(a->count, count, sizeof(int32_t) * (size_t)len);
    memcpy(a->weights, weights, sizeof(float) * (size_t)len * (size_t)stride);
    if (ring > 0) {
        memcpy(a->ring_lo, ring_lo, sizeof(int32_t) * (size_t)len);
        memcpy(a->ring_hi, ring_hi, sizeof(int32_t) * (size_t)len);
    }
    a->first = start[0];
    a->end = 0;
    for (int i = 0; i < len; i++) {
        if (start[i] < a->first) a->first = start[i];
        if (start[i] + count[i] > a->end) a->end = start[i] + count[i];
    }
    return 0;
}

void rs_axis_free(rs_axis * a)
{
    free(a->start);
    free(a->count);
    free(a->weights);
    free(a->ring_lo);
    free(a->ring_hi);
    memset(a, 0, sizeof(*a));
}

void rs_worker_free(rs_worker * w)
{
    free(w->converted);
    free(w->ring);
    free(w->ring_row);
    free(w->rows);
    memset(w, 0, sizeof(*w));
}

static inline float minf(float a, float b) { return a < b ? a : b; }
static inline float maxf(float a, float b) { return a > b ? a : b; }

// Premultiplied code c of alpha a (1..254) as the color's own code, rounded.
static inline int unpremultiply(int c, int a)
{
    const int v = (c * 255 + a / 2) / a;
    return v > 255 ? 255 : v;
}

// Source row sy's columns ax.first until ax.end as 0..1 premultiplied floats into out;
// returns 1 when every one is opaque.
static int convert(const rs_job * job, int sy, float * out)
{
    const uint8_t * p = job->src + (size_t)sy * job->src_stride + (size_t)job->ax.first * 4;
    const int n = job->ax.end - job->ax.first;
    int all = 255;
    int i = 0;
    if (!job->linear) {
#if defined(__aarch64__)
        // Color bytes set, so only alpha bytes can clear the running AND.
        const uint8x16_t colors = vreinterpretq_u8_u32(vdupq_n_u32(0x00FFFFFFu));
        uint8x16_t alphas = vdupq_n_u8(255);
        const float32x4_t unit = vdupq_n_f32(1.0f / 255.0f);
        for (; i + 4 <= n; i += 4) {
            const uint8x16_t v = vld1q_u8(p + 4 * i);
            alphas = vandq_u8(alphas, vorrq_u8(v, colors));
            const uint16x8_t lo = vmovl_u8(vget_low_u8(v));
            const uint16x8_t hi = vmovl_high_u8(v);
            float * o = out + 4 * i;
            vst1q_f32(o, vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(lo))), unit));
            vst1q_f32(o + 4, vmulq_f32(vcvtq_f32_u32(vmovl_high_u16(lo)), unit));
            vst1q_f32(o + 8, vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(hi))), unit));
            vst1q_f32(o + 12, vmulq_f32(vcvtq_f32_u32(vmovl_high_u16(hi)), unit));
        }
        all = vminvq_u8(alphas);
#endif
        for (; i < n; i++) {
            const uint8_t * q = p + 4 * i;
            float * o = out + 4 * i;
            all &= q[3];
            o[0] = to_unit[q[0]];
            o[1] = to_unit[q[1]];
            o[2] = to_unit[q[2]];
            o[3] = to_unit[q[3]];
        }
        return all == 255;
    }
    for (; i < n; i++) {
        const uint8_t * q = p + 4 * i;
        float * o = out + 4 * i;
        const int a = q[3];
        all &= a;
        if (a == 255) {
            o[0] = to_linear[q[0]];
            o[1] = to_linear[q[1]];
            o[2] = to_linear[q[2]];
        } else if (a == 0) {
            o[0] = o[1] = o[2] = 0.0f;
        } else {
            const float af = to_unit[a];
            o[0] = to_linear[unpremultiply(q[0], a)] * af;
            o[1] = to_linear[unpremultiply(q[1], a)] * af;
            o[2] = to_linear[unpremultiply(q[2], a)] * af;
        }
        o[3] = to_unit[a];
    }
    return all == 255;
}

#if defined(__aarch64__)

// Output x of the horizontal pass from the converted row in, anti-ringed when ring > 0.
static inline float32x4_t tap_one(const rs_axis * ax, const float * in, int x)
{
    const int n = ax->count[x];
    const float * p = in + (size_t)(ax->start[x] - ax->first) * 4;
    const float * w = ax->weights + (size_t)x * ax->stride;
    float32x4_t acc = vmulq_n_f32(vld1q_f32(p), w[0]);
    for (int t = 1; t < n; t++) acc = vfmaq_n_f32(acc, vld1q_f32(p + 4 * t), w[t]);
    return acc;
}

// acc blended by ring toward itself clamped to the range of output x's nearest pixels.
static inline float32x4_t ring_one(const rs_axis * ax, const float * in, int x, float32x4_t acc)
{
    const int n = ax->count[x];
    const int start = ax->start[x];
    int ra = ax->ring_lo[x] - start;
    ra = ra < 0 ? 0 : ra > n - 1 ? n - 1 : ra;
    int rb = ax->ring_hi[x] - start;
    rb = rb < ra ? ra : rb > n - 1 ? n - 1 : rb;
    const float * p = in + (size_t)(start - ax->first) * 4;
    float32x4_t lo = vld1q_f32(p + 4 * ra);
    float32x4_t hi = lo;
    for (int t = ra + 1; t <= rb; t++) {
        const float32x4_t v = vld1q_f32(p + 4 * t);
        lo = vminq_f32(lo, v);
        hi = vmaxq_f32(hi, v);
    }
    const float32x4_t clamped = vminq_f32(vmaxq_f32(acc, lo), hi);
    return vaddq_f32(acc, vmulq_n_f32(vsubq_f32(clamped, acc), ax->ring));
}

static void filter_row(const rs_axis * ax, const float * in, float * out, int opaque)
{
    const int ringed = ax->ring > 0;
    const int len = ax->len;
    int x = 0;
    for (; x + 4 <= len; x += 4) {
        float32x4_t acc[4];
        const int n = ax->count[x];
        if (ax->count[x + 1] == n && ax->count[x + 2] == n && ax->count[x + 3] == n) {
            // Four independent sums at once, so the FMA latency overlaps.
            const float * p0 = in + (size_t)(ax->start[x] - ax->first) * 4;
            const float * p1 = in + (size_t)(ax->start[x + 1] - ax->first) * 4;
            const float * p2 = in + (size_t)(ax->start[x + 2] - ax->first) * 4;
            const float * p3 = in + (size_t)(ax->start[x + 3] - ax->first) * 4;
            const float * w0 = ax->weights + (size_t)x * ax->stride;
            const float * w1 = w0 + ax->stride;
            const float * w2 = w1 + ax->stride;
            const float * w3 = w2 + ax->stride;
            float32x4_t a0 = vmulq_n_f32(vld1q_f32(p0), w0[0]);
            float32x4_t a1 = vmulq_n_f32(vld1q_f32(p1), w1[0]);
            float32x4_t a2 = vmulq_n_f32(vld1q_f32(p2), w2[0]);
            float32x4_t a3 = vmulq_n_f32(vld1q_f32(p3), w3[0]);
            for (int t = 1; t < n; t++) {
                a0 = vfmaq_n_f32(a0, vld1q_f32(p0 + 4 * t), w0[t]);
                a1 = vfmaq_n_f32(a1, vld1q_f32(p1 + 4 * t), w1[t]);
                a2 = vfmaq_n_f32(a2, vld1q_f32(p2 + 4 * t), w2[t]);
                a3 = vfmaq_n_f32(a3, vld1q_f32(p3 + 4 * t), w3[t]);
            }
            acc[0] = a0;
            acc[1] = a1;
            acc[2] = a2;
            acc[3] = a3;
        } else {
            for (int k = 0; k < 4; k++) acc[k] = tap_one(ax, in, x + k);
        }
        for (int k = 0; k < 4; k++) {
            float32x4_t v = ringed ? ring_one(ax, in, x + k, acc[k]) : acc[k];
            if (opaque) v = vsetq_lane_f32(1.0f, v, 3);
            vst1q_f32(out + (size_t)(x + k) * 4, v);
        }
    }
    for (; x < len; x++) {
        float32x4_t v = tap_one(ax, in, x);
        if (ringed) v = ring_one(ax, in, x, v);
        if (opaque) v = vsetq_lane_f32(1.0f, v, 3);
        vst1q_f32(out + (size_t)x * 4, v);
    }
}

#else

static void filter_row(const rs_axis * ax, const float * in, float * out, int opaque)
{
    const int ringed = ax->ring > 0;
    for (int x = 0; x < ax->len; x++) {
        const int n = ax->count[x];
        const int start = ax->start[x];
        const float * p = in + (size_t)(start - ax->first) * 4;
        const float * w = ax->weights + (size_t)x * ax->stride;
        float acc[4] = { w[0] * p[0], w[0] * p[1], w[0] * p[2], w[0] * p[3] };
        for (int t = 1; t < n; t++) {
            for (int c = 0; c < 4; c++) acc[c] += w[t] * p[4 * t + c];
        }
        if (ringed) {
            int ra = ax->ring_lo[x] - start;
            ra = ra < 0 ? 0 : ra > n - 1 ? n - 1 : ra;
            int rb = ax->ring_hi[x] - start;
            rb = rb < ra ? ra : rb > n - 1 ? n - 1 : rb;
            for (int c = 0; c < 4; c++) {
                float lo = p[4 * ra + c];
                float hi = lo;
                for (int t = ra + 1; t <= rb; t++) {
                    lo = minf(lo, p[4 * t + c]);
                    hi = maxf(hi, p[4 * t + c]);
                }
                acc[c] += ax->ring * (minf(maxf(acc[c], lo), hi) - acc[c]);
            }
        }
        if (opaque) acc[3] = 1.0f;
        memcpy(out + (size_t)x * 4, acc, sizeof(acc));
    }
}

#endif

// Offset in the ring of source row sy filtered horizontally; kept from earlier or made now.
static const float * ring_row(const rs_job * job, rs_worker * w, int sy)
{
    const int slot = sy % job->ay.stride;
    float * base = w->ring + (size_t)slot * job->ax.len * 4;
    if (w->ring_row[slot] == sy) return base;
    const int opaque = convert(job, sy, w->converted);
    filter_row(&job->ax, w->converted, base, opaque);
    w->ring_row[slot] = sy;
    return base;
}

static inline int code(float v)
{
    return (int)(v * 255.0f + 0.5f);
}

static inline int encode(float v)
{
    if (v <= 0.0f) return 0;
    if (v >= 1.0f) return 255;
    return from_linear[(int)(v * 65535.0f + 0.5f)];
}

// One premultiplied pixel from 0..1 premultiplied channels v into out.
static inline void pack_one(const float * v, int linear, uint8_t * out)
{
    const float alpha = minf(maxf(v[3], 0.0f), 1.0f);
    const int ai = code(alpha);
    if (ai <= 0) {
        memset(out, 0, 4);
        return;
    }
    out[3] = (uint8_t)ai;
    if (!linear) {
        for (int c = 0; c < 3; c++) out[c] = (uint8_t)code(minf(maxf(v[c], 0.0f), alpha));
        return;
    }
    const float inv = 1.0f / alpha;
    for (int c = 0; c < 3; c++) {
        const int e = encode(v[c] * inv);
        out[c] = (uint8_t)(ai == 255 ? e : (e * ai + 127) / 255);
    }
}

static void vertical_row(const rs_job * job, rs_worker * w, int y)
{
    const rs_axis * ay = &job->ay;
    const int n = ay->count[y];
    const int s = ay->start[y];
    const float * wv = ay->weights + (size_t)y * ay->stride;
    const float ** rows = w->rows;
    for (int t = 0; t < n; t++) rows[t] = ring_row(job, w, s + t);
    // Rows the anti-ringing clamps to, after the taps, all within them.
    const int ringed = ay->ring > 0;
    int rn = 0;
    if (ringed) {
        rn = ay->ring_hi[y] - ay->ring_lo[y] + 1;
        for (int t = 0; t < rn; t++) rows[n + t] = ring_row(job, w, ay->ring_lo[y] + t);
    }
    const float ** rr = rows + n;
    const float strength = ay->ring;
    const int len = job->ax.len * 4;
    uint8_t * out = job->dst + (size_t)y * job->dst_stride;
    int j = 0;
#if defined(__aarch64__)
    if (!job->linear) {
        const float32x4_t zero = vdupq_n_f32(0.0f);
        const float32x4_t one = vdupq_n_f32(1.0f);
        const float32x4_t k255 = vdupq_n_f32(255.0f);
        const float32x4_t half = vdupq_n_f32(0.5f);
        for (; j + 16 <= len; j += 16) {
            float32x4_t a[4];
            for (int k = 0; k < 4; k++) a[k] = vmulq_n_f32(vld1q_f32(rows[0] + j + 4 * k), wv[0]);
            for (int t = 1; t < n; t++) {
                const float * r = rows[t] + j;
                const float wt = wv[t];
                a[0] = vfmaq_n_f32(a[0], vld1q_f32(r), wt);
                a[1] = vfmaq_n_f32(a[1], vld1q_f32(r + 4), wt);
                a[2] = vfmaq_n_f32(a[2], vld1q_f32(r + 8), wt);
                a[3] = vfmaq_n_f32(a[3], vld1q_f32(r + 12), wt);
            }
            uint32x4_t u[4];
            for (int k = 0; k < 4; k++) {
                float32x4_t v = a[k];
                if (ringed) {
                    float32x4_t lo = vld1q_f32(rr[0] + j + 4 * k);
                    float32x4_t hi = lo;
                    for (int t = 1; t < rn; t++) {
                        const float32x4_t q = vld1q_f32(rr[t] + j + 4 * k);
                        lo = vminq_f32(lo, q);
                        hi = vmaxq_f32(hi, q);
                    }
                    v = vaddq_f32(v, vmulq_n_f32(vsubq_f32(vminq_f32(vmaxq_f32(v, lo), hi), v), strength));
                }
                // Alpha to 0..1, colors to 0..alpha: alpha's lane bounded by 1, the others by alpha.
                const float32x4_t alpha = vminq_f32(vmaxq_f32(vdupq_laneq_f32(v, 3), zero), one);
                const float32x4_t c = vminq_f32(vmaxq_f32(v, zero), vsetq_lane_f32(1.0f, alpha, 3));
                u[k] = vcvtq_u32_f32(vaddq_f32(vmulq_f32(c, k255), half));
            }
            const uint16x8_t lo16 = vcombine_u16(vmovn_u32(u[0]), vmovn_u32(u[1]));
            const uint16x8_t hi16 = vcombine_u16(vmovn_u32(u[2]), vmovn_u32(u[3]));
            vst1q_u8(out + j, vcombine_u8(vmovn_u16(lo16), vmovn_u16(hi16)));
        }
    }
#endif
    for (; j < len; j += 4) {
        float v[4];
        for (int c = 0; c < 4; c++) {
            float acc = wv[0] * rows[0][j + c];
            for (int t = 1; t < n; t++) acc += wv[t] * rows[t][j + c];
            if (ringed) {
                float lo = rr[0][j + c];
                float hi = lo;
                for (int t = 1; t < rn; t++) {
                    lo = minf(lo, rr[t][j + c]);
                    hi = maxf(hi, rr[t][j + c]);
                }
                acc += strength * (minf(maxf(acc, lo), hi) - acc);
            }
            v[c] = acc;
        }
        pack_one(v, job->linear, out + j);
    }
}

int rs_band(const rs_job * job, rs_worker * w, int y0, int y1)
{
    pthread_once(&tables_once, make_tables);
    const int stride = job->ay.stride;
    if (w->ring == NULL) {
        w->converted = malloc(sizeof(float) * 4 * (size_t)(job->ax.end - job->ax.first));
        w->ring = malloc(sizeof(float) * 4 * (size_t)job->ax.len * (size_t)stride);
        w->ring_row = malloc(sizeof(int32_t) * (size_t)stride);
        // The taps and the anti-ringing rows after them, each at most a stride.
        w->rows = malloc(sizeof(float *) * 2 * (size_t)stride);
        if (!w->converted || !w->ring || !w->ring_row || !w->rows) {
            rs_worker_free(w);
            return -1;
        }
        for (int i = 0; i < stride; i++) w->ring_row[i] = -1;
    }
    for (int y = y0; y < y1; y++) vertical_row(job, w, y);
    return 0;
}
