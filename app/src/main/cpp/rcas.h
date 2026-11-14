// RCAS, the sharpening pass of AMD FidelityFX Super Resolution 1 (MIT; Copyright (c) 2021
// Advanced Micro Devices, Inc.; the notice is under About), on an ARGB_8888 bitmap in
// place: the native twin of com.infinity.comicreader.reader.Rcas, bit for bit (rcas.c).

#ifndef RCAS_H
#define RCAS_H

#include <stddef.h>
#include <stdint.h>

// One sharpening of a w x h bitmap by `amount` (0..1) in `bands` bands of rows. Past each
// edge, `top` and `bottom` (rows of w pixels) and `left` and `right` (columns of h pixels,
// each row `*_stride` bytes on) are the neighbors where given; NULL repeats the edge pixels.
// `saved` holds rows y0 - 1 and y0 of every band but the first as they were (rcas_open).
typedef struct {
    uint8_t * px;
    size_t stride;
    int w;
    int h;
    float amount;
    const uint8_t * top;
    const uint8_t * bottom;
    const uint8_t * left;
    size_t left_stride;
    const uint8_t * right;
    size_t right_stride;
    int bands;
    uint8_t * saved;
} rcas_job;

// The first row of band b of `bands` over h rows, as Rcas.bandStart.
int rcas_band_start(int h, int bands, int b);

// Saves the rows beside each band's start; 0 on success, -1 when out of memory or the
// bands do not fit.
int rcas_open(rcas_job * j);

void rcas_close(rcas_job * j);

// Sharpens the rows of band b in place, with `scratch` of 8 * w bytes; bands may run on
// several threads at once, each with its own scratch.
void rcas_band(const rcas_job * j, int b, uint8_t * scratch);

// Output row `out` from rows `up`, `mid` and `down` of w pixels, `left` and `right` the
// pixels just past mid's ends (RGBA as memory holds them, read as one little-endian word).
// `out` must not be `mid`.
void rcas_row(uint8_t * out, const uint8_t * up, const uint8_t * mid, const uint8_t * down, int w, uint32_t left,
              uint32_t right, float amount);

#endif
