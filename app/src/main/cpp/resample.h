// Separable resampling of premultiplied RGBA8 pixels, as an Android ARGB_8888 bitmap
// holds them in memory: the native twin of com.infinity.comicreader.reader.Resampler run
// with premultiplied rows, on the CPU (resample.c: NEON on arm64, plain C elsewhere) or
// on the GPU (resample_gpu.c: OpenGL ES 3.1 compute).

#ifndef RESAMPLE_H
#define RESAMPLE_H

#include <stddef.h>
#include <stdint.h>

// Taps of one axis, as Resampler.Axis holds them: output i reads source pixels
// start[i] until start[i] + count[i] with weights from weights[i * stride]; every tap lies
// in first until end. With ring above 0, output i's anti-ringing range is source pixels
// ring_lo[i]..ring_hi[i].
typedef struct {
    int len;
    int stride;
    int first;
    int end;
    int32_t * start;
    int32_t * count;
    float * weights;
    float ring;
    int32_t * ring_lo;
    int32_t * ring_hi;
} rs_axis;

// One resample: source and destination pixels with their row strides in bytes, the taps
// of each axis (ax across, ay down), and whether colors are filtered in linear light.
typedef struct {
    const uint8_t * src;
    size_t src_stride;
    uint8_t * dst;
    size_t dst_stride;
    rs_axis ax;
    rs_axis ay;
    int linear;
} rs_job;

// One CPU worker's buffers: a ring of horizontally filtered source rows, as tall as the
// vertical kernel and as wide as the output, kept from band to band of one job.
typedef struct {
    float * converted;
    float * ring;
    int32_t * ring_row;
    const float ** rows;
} rs_worker;

// Fills a's tables from Java's arrays' contents; 0 on success, -1 when out of memory.
int rs_axis_init(rs_axis * a, int len, int stride, const int32_t * start, const int32_t * count, const float * weights,
                 float ring, const int32_t * ring_lo, const int32_t * ring_hi);

void rs_axis_free(rs_axis * a);

// Filters output rows y0 until y1 of job on the CPU into its destination; 0 on success,
// -1 when out of memory. Workers run at once on one job, each with its own w.
int rs_band(const rs_job * job, rs_worker * w, int y0, int y1);

void rs_worker_free(rs_worker * w);

// The 0..1 linear light of each sRGB code, and sRGB codes of 65536 steps of linear light,
// as Resampler's tables hold them.
const float * rs_to_linear(void);
const uint8_t * rs_from_linear(void);

// The GPU path (resample_gpu.c): one job at a time, on the thread that opened it.
typedef struct rs_gpu rs_gpu;

// Opens job on the GPU, its axes already filled; NULL when there is no usable GPU.
rs_gpu * rs_gpu_open(const rs_job * job);

// Asked with its opaque pointer between the chunks of a GPU band and while it waits for
// one; nonzero stops the band. Runs on the band's thread with the GPU lock held and the
// context current: it must only read a flag, never block, take locks, call into the GPU
// path or GL.
typedef int (*rs_stop)(void * opaque);

// Filters output rows y0 until y1, asking stop (NULL: never) before each chunk starts,
// before each of the last ones is copied out and every 100 ms of waiting for the GPU; 0 on
// success, -1 on a GPU error, 1 once stopped. Chunks overlap: the GPU filters one while the next one's rows go up; once
// stopped, rows of chunks already started may be missing.
int rs_gpu_band(rs_gpu * g, int y0, int y1, rs_stop stop, void * opaque);

void rs_gpu_close(rs_gpu * g);

// Frees what the GPU path keeps between jobs: its buffers, and with `programs` its across
// programs. Never blocks on the GPU lock: while a job holds it (pthread_mutex_trylock
// fails), it leaves a flag that the job's rs_gpu_close honors.
void rs_gpu_trim(int programs);

// Turns on or off one log line per GPU job, under "ResampleGpu": the time of each step.
void rs_gpu_trace(int on);

#endif
