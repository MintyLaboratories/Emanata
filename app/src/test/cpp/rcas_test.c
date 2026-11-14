// Host tests of rcas.c (its plain C path): the digest RcasTest.matchesTheNativeHostTestsDigest
// checks, so both sides give the same pixels; strength 0 changes nothing; any band count
// gives the same pixels; a rim makes a region match the whole image.

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rcas.h"

// RcasTest.NATIVE_DIGEST.
#define DIGEST 0x883E28A7u

static int failures;

#define CHECK(c)                                                         \
    do {                                                                 \
        if (!(c)) {                                                      \
            fprintf(stderr, "%s:%d: failed: %s\n", __FILE__, __LINE__, #c); \
            failures++;                                                  \
        }                                                                \
    } while (0)

// RcasTest's image: an LCG's noise, translucent where its low bits are 0, every ninth row (from the fifth) flat gray.
static uint8_t * image(int w, int h)
{
    uint8_t * px = malloc((size_t)w * h * 4);
    uint32_t s = 12345;
    for (int i = 0; i < w * h; i++) {
        s = s * 1664525u + 1013904223u;
        uint8_t * p = px + (size_t)i * 4;
        if ((i / w) % 9 == 4) {
            p[0] = p[1] = p[2] = 0x80;
            p[3] = 0xFF;
            continue;
        }
        p[0] = (uint8_t)(s >> 8);
        p[1] = (uint8_t)(s >> 16);
        p[2] = (uint8_t)(s >> 24);
        p[3] = (s & 0x3F) == 0 ? 0x80 : 0xFF;
    }
    return px;
}

// Sharpens px (w x h) in `bands` bands, rim strips as given (NULL: none).
static void sharpen(uint8_t * px, int w, int h, float amount, int bands, const uint8_t * top, const uint8_t * bottom, const uint8_t * left,
                    const uint8_t * right)
{
    rcas_job j = { 0 };
    j.px = px;
    j.stride = (size_t)w * 4;
    j.w = w;
    j.h = h;
    j.amount = amount;
    j.top = top;
    j.bottom = bottom;
    j.left = left;
    j.left_stride = 4;
    j.right = right;
    j.right_stride = 4;
    j.bands = bands;
    CHECK(rcas_open(&j) == 0);
    uint8_t * scratch = malloc((size_t)w * 8);
    // Last band first: bands must not depend on their order.
    for (int b = bands - 1; b >= 0; b--) rcas_band(&j, b, scratch);
    free(scratch);
    rcas_close(&j);
}

static void digest_matches_kotlin(void)
{
    const int w = 61, h = 37;
    uint32_t hash = 0x811C9DC5u;
    const float amounts[] = { 0.35f, 1.0f };
    for (int a = 0; a < 2; a++) {
        uint8_t * px = image(w, h);
        sharpen(px, w, h, amounts[a], 3, NULL, NULL, NULL, NULL);
        for (size_t i = 0; i < (size_t)w * h * 4; i++) hash = (hash ^ px[i]) * 0x01000193u;
        free(px);
    }
    if (hash != DIGEST) fprintf(stderr, "digest %08x\n", hash);
    CHECK(hash == DIGEST);
}

static void strength_zero_changes_nothing(void)
{
    const int w = 33, h = 21;
    uint8_t * px = image(w, h);
    uint8_t * kept = malloc((size_t)w * h * 4);
    memcpy(kept, px, (size_t)w * h * 4);
    sharpen(px, w, h, 0.0f, 4, NULL, NULL, NULL, NULL);
    CHECK(memcmp(px, kept, (size_t)w * h * 4) == 0);
    free(px);
    free(kept);
}

static void any_band_count_gives_the_same_pixels(void)
{
    const int w = 29, h = 40;
    uint8_t * one = image(w, h);
    sharpen(one, w, h, 0.8f, 1, NULL, NULL, NULL, NULL);
    const int counts[] = { 2, 3, 7, 40 };
    for (int c = 0; c < 4; c++) {
        uint8_t * px = image(w, h);
        sharpen(px, w, h, 0.8f, counts[c], NULL, NULL, NULL, NULL);
        CHECK(memcmp(px, one, (size_t)w * h * 4) == 0);
        free(px);
    }
    free(one);
}

static void a_rim_joins_a_region_to_the_whole(void)
{
    const int w = 30, h = 24, x0 = 7, y0 = 5, rw = 12, rh = 12;
    uint8_t * whole = image(w, h);
    uint8_t * src = image(w, h);
    sharpen(whole, w, h, 1.0f, 2, NULL, NULL, NULL, NULL);
    uint8_t * region = malloc((size_t)rw * rh * 4);
    uint8_t top[12 * 4], bottom[12 * 4], left[12 * 4], right[12 * 4];
    for (int y = 0; y < rh; y++) memcpy(region + (size_t)y * rw * 4, src + ((size_t)(y0 + y) * w + x0) * 4, (size_t)rw * 4);
    memcpy(top, src + ((size_t)(y0 - 1) * w + x0) * 4, sizeof top);
    memcpy(bottom, src + ((size_t)(y0 + rh) * w + x0) * 4, sizeof bottom);
    for (int y = 0; y < rh; y++) {
        memcpy(left + y * 4, src + ((size_t)(y0 + y) * w + x0 - 1) * 4, 4);
        memcpy(right + y * 4, src + ((size_t)(y0 + y) * w + x0 + rw) * 4, 4);
    }
    sharpen(region, rw, rh, 1.0f, 3, top, bottom, left, right);
    for (int y = 0; y < rh; y++) CHECK(memcmp(region + (size_t)y * rw * 4, whole + ((size_t)(y0 + y) * w + x0) * 4, (size_t)rw * 4) == 0);
    free(whole);
    free(src);
    free(region);
}

int main(void)
{
    digest_matches_kotlin();
    strength_zero_changes_nothing();
    any_band_count_gives_the_same_pixels();
    a_rim_joins_a_region_to_the_whole();
    if (failures == 0) printf("rcas: all passed\n");
    return failures == 0 ? 0 : 1;
}
