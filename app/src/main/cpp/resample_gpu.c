// The GPU path of resample.h: the same two passes as the CPU's, as OpenGL ES 3.1 compute
// shaders on an offscreen context of our own, in fp32.
// - Output rows go in chunks: the source rows a chunk's taps read are copied into a
//   buffer, filtered across into an fp32 buffer as wide as the output, then down into
//   packed bytes, which are copied into the destination. Chunks are sized so the source
//   rows and the fp32 ones together stay under CHUNK_BYTES, whatever the scale.
// - Each axis' taps go up once per job: four ints per output (start, count, anti-ringing
//   range) followed by its weights, the across axis' tap-major so lanes read them in a row.
// - The across pass runs one thread per output column and ROWS source rows, its taps in
//   registers and its tap loop unrolled to the most taps a column reads: a program per
//   tap count up to UNROLL and anti-ringing on or off, compiled on first use and kept with
//   the context; wider axes share a program per anti-ringing that loops over the taps.
// - One job at a time: gl_lock is held from rs_gpu_open to rs_gpu_close, on one thread.
// - While tracing (rs_gpu_trace), each job logs one line under TAG when it closes: the
//   milliseconds each step took over all its chunks (compile: the across program's, on its
//   first use), the GPU time of each pass where the driver has GL_EXT_disjoint_timer_query,
//   the across tap count, and the cores its copies ran on.

#define _GNU_SOURCE
#include "resample.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <android/log.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHUNK_BYTES (32u << 20)
#define GROUP 64
// Source rows each across thread filters.
#define ROWS 8
// Most taps the across pass unrolls; wider axes share one looping program.
#define UNROLL 32
#define TAG "ResampleGpu"

// GL_EXT_disjoint_timer_query's enums and the entry points tracing uses.
#define TIME_ELAPSED 0x88BF
#define QUERY_RESULT 0x8866
#define GPU_DISJOINT 0x8FBB
typedef void (GL_APIENTRY * gen_queries_fn)(GLsizei n, GLuint * ids);
typedef void (GL_APIENTRY * delete_queries_fn)(GLsizei n, const GLuint * ids);
typedef void (GL_APIENTRY * begin_query_fn)(GLenum target, GLuint id);
typedef void (GL_APIENTRY * end_query_fn)(GLenum target);
typedef void (GL_APIENTRY * query_u64_fn)(GLuint id, GLenum pname, GLuint64 * value);

static pthread_mutex_t gl_lock = PTHREAD_MUTEX_INITIALIZER;
// 0 untried, 1 ready, -1 unusable.
static int gl_state = 0;
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
// Across programs by tap count (1..UNROLL unrolled, UNROLL + 1 looping) and anti-ringing
// off or on, compiled on first use; program 0 where it did not compile.
static struct {
    int tried;
    GLuint program;
} across_kept[UNROLL + 2][2];
static GLuint down_program;
static GLuint lut_linear;
static GLuint lut_encode;
static atomic_int tracing;
// Timer queries: 0 untried, 1 loaded, -1 unavailable; loaded by the first traced job.
static int tq_state = 0;
static gen_queries_fn gen_queries;
static delete_queries_fn delete_queries;
static begin_query_fn begin_query;
static end_query_fn end_query;
static query_u64_fn query_u64;

// What one traced job's steps took, summed over its chunks; untraced, all stay 0.
typedef struct {
    int on;
    // With a timer query per pass (queries: across, down).
    int timed;
    // Some timer query result is unreliable: the GPU changed clocks or was reset meanwhile.
    int disjoint;
    GLuint queries[2];
    int64_t opened;
    int64_t lock_ns;
    int64_t begin_ns;
    // Compiling the across program, when the job is its first use.
    int64_t compile_ns;
    int64_t alloc_ns;
    int64_t upload_ns;
    int64_t submit_ns;
    int64_t wait_ns;
    int64_t copy_ns;
    uint64_t across_ns;
    uint64_t down_ns;
    // Cores the copies ran on, bit n = core n.
    uint64_t cores;
    int chunks;
    int rows;
    // The across program's tap loop length.
    int across_taps;
} rs_trace;

struct rs_gpu {
    const rs_job * job;
    GLuint across;
    // The most taps a column reads.
    int taps;
    GLuint axis_x;
    GLuint axis_y;
    GLuint src;
    GLuint mid;
    GLuint out;
    int chunk;
    int max_rows;
    rs_trace trace;
};

// Prefixed with "#version 310 es" and defines of WIDE (1: the tap loop runs to the uniform
// taps; 0: it is unrolled to TAPS), TAPS (the most taps a column reads, while not WIDE),
// ROWS and RING (1 with anti-ringing).
static const char * ACROSS =
    "precision highp float;\n"
    "precision highp int;\n"
    "layout(local_size_x = 64) in;\n"
    "layout(std430, binding = 0) readonly buffer Src { uint src[]; };\n"
    "layout(std430, binding = 1) readonly buffer Axis { uint ax[]; };\n"
    "layout(std430, binding = 2) writeonly buffer Mid { vec4 mid[]; };\n"
    "layout(std430, binding = 3) readonly buffer Lut { float toLinear[]; };\n"
    // x: output width, y: rows, z: source pixels per row, w: linear
    "uniform ivec4 dims;\n"
    "uniform int first;\n"
    "uniform float ring;\n"
    "#if WIDE\n"
    "uniform int taps;\n"
    "#define TAPS taps\n"
    "#endif\n"
    "vec4 px(int i) {\n"
    "  uint p = src[i];\n"
    "  uvec4 b = uvec4(p & 255u, (p >> 8) & 255u, (p >> 16) & 255u, p >> 24);\n"
    "  if (dims.w == 0) return vec4(b) / 255.0;\n"
    "  float a = float(b.a) / 255.0;\n"
    "  if (b.a == 255u) return vec4(toLinear[int(b.r)], toLinear[int(b.g)], toLinear[int(b.b)], 1.0);\n"
    "  if (b.a == 0u) return vec4(0.0);\n"
    "  uvec3 own = min(uvec3(255u), (b.rgb * 255u + b.a / 2u) / b.a);\n"
    "  return vec4(toLinear[int(own.r)] * a, toLinear[int(own.g)] * a, toLinear[int(own.b)] * a, a);\n"
    "}\n"
    // Column x of source rows ROWS * y on: taps past the column's count weigh 0 and read
    // at most the row's last pixel; the anti-ringing range comes from the taps' own pixels.
    "void main() {\n"
    "  int x = int(gl_GlobalInvocationID.x);\n"
    "  int r0 = int(gl_GlobalInvocationID.y) * ROWS;\n"
    "  if (x >= dims.x || r0 >= dims.y) return;\n"
    "  int r1 = min(r0 + ROWS, dims.y);\n"
    "  int s = int(ax[4 * x]) - first;\n"
    "  int last = dims.z - 1;\n"
    "  int wb = 4 * dims.x + x;\n"
    // Unrolled, the weights stay in registers; wide, they are read per tap.
    "#if WIDE\n"
    "#define W(t) uintBitsToFloat(ax[wb + (t) * dims.x])\n"
    "#else\n"
    "  float w[TAPS];\n"
    "  for (int t = 0; t < TAPS; t++) w[t] = uintBitsToFloat(ax[wb + t * dims.x]);\n"
    "#define W(t) w[t]\n"
    "#endif\n"
    "#if RING\n"
    "  int n = int(ax[4 * x + 1]);\n"
    "  int ra = clamp(int(ax[4 * x + 2]) - first - s, 0, n - 1);\n"
    "  int rb = clamp(int(ax[4 * x + 3]) - first - s, ra, n - 1);\n"
    "#endif\n"
    "  for (int r = r0; r < r1; r++) {\n"
    "    int row = r * dims.z;\n"
    "    vec4 v = px(row + s);\n"
    "    vec4 acc = W(0) * v;\n"
    "#if RING\n"
    "    vec4 lo = ra == 0 ? v : vec4(3.4e38);\n"
    "    vec4 hi = ra == 0 ? v : vec4(-3.4e38);\n"
    "#endif\n"
    "    for (int t = 1; t < TAPS; t++) {\n"
    "      v = px(row + min(s + t, last));\n"
    "      acc += W(t) * v;\n"
    "#if RING\n"
    "      bool in_range = t >= ra && t <= rb;\n"
    "      lo = in_range ? min(lo, v) : lo;\n"
    "      hi = in_range ? max(hi, v) : hi;\n"
    "#endif\n"
    "    }\n"
    "#if RING\n"
    "    acc += ring * (min(max(acc, lo), hi) - acc);\n"
    "#endif\n"
    "    mid[r * dims.x + x] = acc;\n"
    "  }\n"
    "}\n";

static const char * DOWN =
    "#version 310 es\n"
    "precision highp float;\n"
    "precision highp int;\n"
    "layout(local_size_x = 64) in;\n"
    "layout(std430, binding = 0) readonly buffer Mid { vec4 mid[]; };\n"
    "layout(std430, binding = 1) readonly buffer Axis { uint ay[]; };\n"
    "layout(std430, binding = 2) writeonly buffer Out { uint outp[]; };\n"
    "layout(std430, binding = 3) readonly buffer Lut { uint fromLinear[]; };\n"
    // x: output width, y: rows, z: first output row, w: linear
    "uniform ivec4 dims;\n"
    "uniform int firstRow;\n"
    "uniform int stride;\n"
    "uniform float ring;\n"
    "uniform int len;\n"
    "int code(float v) { return int(v * 255.0 + 0.5); }\n"
    "int encode(float v) {\n"
    "  if (v <= 0.0) return 0;\n"
    "  if (v >= 1.0) return 255;\n"
    "  int i = int(v * 65535.0 + 0.5);\n"
    "  return int((fromLinear[i >> 2] >> uint(8 * (i & 3))) & 255u);\n"
    "}\n"
    "void main() {\n"
    "  int x = int(gl_GlobalInvocationID.x);\n"
    "  int j = int(gl_GlobalInvocationID.y);\n"
    "  if (x >= dims.x || j >= dims.y) return;\n"
    "  int y = dims.z + j;\n"
    "  int s = int(ay[4 * y]) - firstRow;\n"
    "  int n = int(ay[4 * y + 1]);\n"
    "  int wb = 4 * len + y * stride;\n"
    "  vec4 acc = uintBitsToFloat(ay[wb]) * mid[s * dims.x + x];\n"
    "  for (int t = 1; t < n; t++) acc += uintBitsToFloat(ay[wb + t]) * mid[(s + t) * dims.x + x];\n"
    "  if (ring > 0.0) {\n"
    "    int lo = int(ay[4 * y + 2]) - firstRow;\n"
    "    int hi = int(ay[4 * y + 3]) - firstRow;\n"
    "    vec4 mn = mid[lo * dims.x + x];\n"
    "    vec4 mx = mn;\n"
    "    for (int t = lo + 1; t <= hi; t++) { vec4 v = mid[t * dims.x + x]; mn = min(mn, v); mx = max(mx, v); }\n"
    "    acc += ring * (min(max(acc, mn), mx) - acc);\n"
    "  }\n"
    "  float alpha = clamp(acc.a, 0.0, 1.0);\n"
    "  int ai = code(alpha);\n"
    "  uint p = 0u;\n"
    "  if (ai > 0) {\n"
    "    ivec3 c;\n"
    "    if (dims.w == 0) {\n"
    "      vec3 v = min(max(acc.rgb, vec3(0.0)), vec3(alpha));\n"
    "      c = ivec3(code(v.r), code(v.g), code(v.b));\n"
    "    } else {\n"
    "      vec3 v = acc.rgb / alpha;\n"
    "      c = ivec3(encode(v.r), encode(v.g), encode(v.b));\n"
    "      if (ai < 255) c = (c * ai + 127) / 255;\n"
    "    }\n"
    "    p = uint(c.r) | (uint(c.g) << 8) | (uint(c.b) << 16) | (uint(ai) << 24);\n"
    "  }\n"
    "  outp[j * dims.x + x] = p;\n"
    "}\n";

static GLuint compile(const char * source)
{
    const GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glDeleteShader(shader);
        return 0;
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, shader);
    glLinkProgram(program);
    glDeleteShader(shader);
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

static GLuint buffer(GLsizeiptr size, const void * data, GLenum usage)
{
    GLuint b = 0;
    glGenBuffers(1, &b);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
    glBufferData(GL_SHADER_STORAGE_BUFFER, size, data, usage);
    return b;
}

// Makes the context current, creating it and the programs the first time; 0 when usable.
static int gl_begin(void)
{
    if (gl_state == 0) {
        gl_state = -1;
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL)) return -1;
        const EGLint config_attrs[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE };
        EGLConfig config;
        EGLint found = 0;
        if (!eglChooseConfig(display, config_attrs, &config, 1, &found) || found < 1) return -1;
        const EGLint context_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
        if (context == EGL_NO_CONTEXT) return -1;
        const char * extensions = eglQueryString(display, EGL_EXTENSIONS);
        if (extensions == NULL || strstr(extensions, "EGL_KHR_surfaceless_context") == NULL) {
            const EGLint surface_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
            surface = eglCreatePbufferSurface(display, config, surface_attrs);
            if (surface == EGL_NO_SURFACE) return -1;
        }
        if (!eglMakeCurrent(display, surface, surface, context)) return -1;
        GLint major = 0, minor = 0, blocks = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        glGetIntegerv(GL_MINOR_VERSION, &minor);
        glGetIntegerv(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, &blocks);
        if (major < 3 || (major == 3 && minor < 1) || blocks < 4) return -1;
        down_program = compile(DOWN);
        if (down_program == 0) return -1;
        lut_linear = buffer(256 * sizeof(float), rs_to_linear(), GL_STATIC_DRAW);
        lut_encode = buffer(65536, rs_from_linear(), GL_STATIC_DRAW);
        if (glGetError() != GL_NO_ERROR) return -1;
        gl_state = 1;
        return 0;
    }
    if (gl_state < 0) return -1;
    return eglMakeCurrent(display, surface, surface, context) ? 0 : -1;
}

static void gl_end(void)
{
    if (display != EGL_NO_DISPLAY) eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

void rs_gpu_trace(int on)
{
    atomic_store(&tracing, on != 0);
}

static int64_t now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}

// Now, while g traces; else 0, so sums of differences stay 0.
static int64_t stamp(const rs_gpu * g)
{
    return g->trace.on ? now_ns() : 0;
}

// Adds the calling thread's core to g's trace.
static void note_core(rs_gpu * g)
{
    if (!g->trace.on) return;
    const int cpu = sched_getcpu();
    if (cpu >= 0 && cpu < 64) g->trace.cores |= 1ull << cpu;
}

// Loads the timer query entry points once, the context current; 1 when the driver has them.
static int tq_load(void)
{
    if (tq_state == 0) {
        tq_state = -1;
        GLint count = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        for (GLint i = 0; i < count; i++) {
            const char * name = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
            if (name == NULL || strcmp(name, "GL_EXT_disjoint_timer_query") != 0) continue;
            gen_queries = (gen_queries_fn)eglGetProcAddress("glGenQueriesEXT");
            delete_queries = (delete_queries_fn)eglGetProcAddress("glDeleteQueriesEXT");
            begin_query = (begin_query_fn)eglGetProcAddress("glBeginQueryEXT");
            end_query = (end_query_fn)eglGetProcAddress("glEndQueryEXT");
            query_u64 = (query_u64_fn)eglGetProcAddress("glGetQueryObjectui64vEXT");
            if (gen_queries != NULL && delete_queries != NULL && begin_query != NULL && end_query != NULL && query_u64 != NULL) {
                tq_state = 1;
            }
            break;
        }
    }
    return tq_state == 1;
}

// Starts timing pass `which` (0 across, 1 down) on the GPU, when g has timer queries.
static void query_begin(const rs_gpu * g, int which)
{
    if (g->trace.timed) begin_query(TIME_ELAPSED, g->trace.queries[which]);
}

static void query_end(const rs_gpu * g)
{
    if (g->trace.timed) end_query(TIME_ELAPSED);
}

// Adds both passes' GPU time to g's trace, waiting for the GPU to finish them; the wait
// counts in wait_ns. Called after readback, so it blocks only on what the map waited for.
// With chunks in flight, collect a slot's results only after its fence signaled (or poll
// QUERY_RESULT_AVAILABLE and collect the rest at close), else tracing serializes them.
static void query_collect(rs_gpu * g)
{
    if (!g->trace.timed) return;
    const int64_t t = stamp(g);
    GLuint64 across = 0, down = 0;
    query_u64(g->trace.queries[0], QUERY_RESULT, &across);
    query_u64(g->trace.queries[1], QUERY_RESULT, &down);
    GLint disjoint = 0;
    glGetIntegerv(GPU_DISJOINT, &disjoint);
    if (disjoint) g->trace.disjoint = 1;
    g->trace.across_ns += across;
    g->trace.down_ns += down;
    g->trace.wait_ns += stamp(g) - t;
}

static double ms(int64_t ns)
{
    return (double)ns / 1e6;
}

// Logs one line for a traced job of job's geometry, total_ns long; chunk 0 when it did not open.
static void trace_log(const rs_trace * t, const rs_job * job, int chunk, int64_t free_ns, int64_t total_ns)
{
    const rs_axis * ax = &job->ax;
    const rs_axis * ay = &job->ay;
    char gpu[64];
    if (t->timed) {
        snprintf(gpu, sizeof(gpu), "across=%.2f down=%.2f%s", ms((int64_t)t->across_ns), ms((int64_t)t->down_ns),
                 t->disjoint ? " disjoint" : "");
    } else {
        snprintf(gpu, sizeof(gpu), "across=- down=-");
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "src=%dx%d dst=%dx%d taps=%d/%d across_taps=%d ring=%.2f/%.2f linear=%d chunk=%d chunks=%d rows=%d%s "
                        "lock=%.2f begin=%.2f compile=%.2f alloc=%.2f upload=%.2f submit=%.2f wait=%.2f copy=%.2f free=%.2f "
                        "total=%.2f %s cores=0x%llx",
                        ax->end - ax->first, ay->end - ay->first, ax->len, ay->len, ax->stride, ay->stride, t->across_taps,
                        ax->ring, ay->ring, job->linear, chunk, t->chunks, t->rows, chunk == 0 ? " failed" : "", ms(t->lock_ns),
                        ms(t->begin_ns), ms(t->compile_ns), ms(t->alloc_ns), ms(t->upload_ns), ms(t->submit_ns), ms(t->wait_ns), ms(t->copy_ns), ms(free_ns),
                        ms(total_ns), gpu, (unsigned long long)t->cores);
}

// The most taps any output of a reads.
static int taps_of(const rs_axis * a)
{
    int taps = 1;
    for (int i = 0; i < a->len; i++) {
        if (a->count[i] > taps) taps = a->count[i];
    }
    return taps;
}

// An axis' taps as the shaders read them: start, count, anti-ringing range, then the
// weights: with `taps` 0, `stride` per output, output-major (output i's tap t at
// i * stride + t); else `taps` per output, tap-major (at t * len + i). Taps past an
// output's count weigh 0.
static GLuint axis_buffer(const rs_axis * a, int taps)
{
    const size_t len = (size_t)a->len;
    const size_t stride = taps > 0 ? (size_t)taps : (size_t)a->stride;
    const int tap_major = taps > 0;
    const size_t words = 4 * len + len * stride;
    uint32_t * data = calloc(words, 4);
    if (data == NULL) return 0;
    for (size_t i = 0; i < len; i++) {
        data[4 * i] = (uint32_t)a->start[i];
        data[4 * i + 1] = (uint32_t)a->count[i];
        data[4 * i + 2] = (uint32_t)(a->ring > 0 ? a->ring_lo[i] : a->start[i]);
        data[4 * i + 3] = (uint32_t)(a->ring > 0 ? a->ring_hi[i] : a->start[i]);
    }
    float * weights = (float *)(data + 4 * len);
    for (size_t i = 0; i < len; i++) {
        const size_t n = (size_t)a->count[i] < stride ? (size_t)a->count[i] : stride;
        for (size_t t = 0; t < n; t++) {
            weights[tap_major ? t * len + i : i * stride + t] = a->weights[i * (size_t)a->stride + t];
        }
    }
    const GLuint b = buffer((GLsizeiptr)(words * 4), data, GL_STATIC_DRAW);
    free(data);
    return b;
}

// The across program for `taps` taps per output (past UNROLL, the looping one), anti-ringed
// or not, compiled on first use with the context current; 0 when it does not compile
// (tried once).
static GLuint across_program(int taps, int ringed)
{
    const int wide = taps > UNROLL;
    const int key = wide ? UNROLL + 1 : taps;
    if (across_kept[key][ringed].tried) return across_kept[key][ringed].program;
    char head[112];
    const int head_len = wide ? snprintf(head, sizeof(head), "#version 310 es\n#define WIDE 1\n#define ROWS %d\n#define RING %d\n",
                                         ROWS, ringed)
                              : snprintf(head, sizeof(head),
                                         "#version 310 es\n#define WIDE 0\n#define TAPS %d\n#define ROWS %d\n#define RING %d\n",
                                         taps, ROWS, ringed);
    const size_t body_len = strlen(ACROSS);
    char * source = malloc((size_t)head_len + body_len + 1);
    if (source == NULL) return 0;
    memcpy(source, head, (size_t)head_len);
    memcpy(source + head_len, ACROSS, body_len + 1);
    across_kept[key][ringed].program = compile(source);
    across_kept[key][ringed].tried = 1;
    free(source);
    return across_kept[key][ringed].program;
}

// Source rows output rows y0 until y1 read: first in *r0, the count returned.
static int rows_of(const rs_axis * ay, int y0, int y1, int * r0)
{
    int lo = ay->start[y0];
    int hi = lo;
    for (int y = y0; y < y1; y++) {
        if (ay->start[y] < lo) lo = ay->start[y];
        if (ay->start[y] + ay->count[y] > hi) hi = ay->start[y] + ay->count[y];
    }
    *r0 = lo;
    return hi - lo;
}

rs_gpu * rs_gpu_open(const rs_job * job)
{
    rs_trace trace = { 0 };
    trace.on = atomic_load(&tracing);
    if (trace.on) trace.opened = now_ns();
    pthread_mutex_lock(&gl_lock);
    int64_t t = trace.on ? now_ns() : 0;
    trace.lock_ns = t - trace.opened;
    rs_gpu * g = calloc(1, sizeof(*g));
    if (g == NULL || gl_begin() != 0) {
        free(g);
        gl_end();
        pthread_mutex_unlock(&gl_lock);
        if (trace.on) trace_log(&trace, job, 0, 0, now_ns() - trace.opened);
        return NULL;
    }
    g->trace = trace;
    g->trace.begin_ns = stamp(g) - t;
    t = stamp(g);
    g->job = job;
    const rs_axis * ax = &job->ax;
    const rs_axis * ay = &job->ay;
    // Output rows per chunk: as many as keep their source and fp32 rows under CHUNK_BYTES.
    const double per_row = (double)(ay->end - ay->first) / ay->len;
    const double budget = (double)CHUNK_BYTES / ((double)ax->len * 16 + (double)(ax->end - ax->first) * 4);
    int chunk = (int)((budget - ay->stride) / (per_row > 1 ? per_row : 1));
    g->chunk = chunk < 8 ? 8 : chunk > 1024 ? 1024 : chunk;
    for (int y = 0; y < ay->len; y += g->chunk) {
        int r0;
        const int y1 = y + g->chunk < ay->len ? y + g->chunk : ay->len;
        const int rows = rows_of(ay, y, y1, &r0);
        if (rows > g->max_rows) g->max_rows = rows;
    }
    const size_t src_w = (size_t)(ax->end - ax->first);
    g->taps = taps_of(ax);
    g->trace.across_taps = g->taps;
    const int64_t compiling = stamp(g);
    g->across = across_program(g->taps, ax->ring > 0);
    g->trace.compile_ns = stamp(g) - compiling;
    g->axis_x = axis_buffer(ax, g->taps);
    g->axis_y = axis_buffer(ay, 0);
    g->src = buffer((GLsizeiptr)(src_w * g->max_rows * 4), NULL, GL_STREAM_DRAW);
    g->mid = buffer((GLsizeiptr)((size_t)ax->len * g->max_rows * 16), NULL, GL_DYNAMIC_COPY);
    g->out = buffer((GLsizeiptr)((size_t)ax->len * g->chunk * 4), NULL, GL_STREAM_READ);
    if (g->across == 0 || g->axis_x == 0 || g->axis_y == 0 || glGetError() != GL_NO_ERROR) {
        g->chunk = 0;
        rs_gpu_close(g);
        return NULL;
    }
    g->trace.alloc_ns = stamp(g) - t - g->trace.compile_ns;
    if (g->trace.on && tq_load()) {
        // Clears errors left over, so the probe below sees only its own.
        for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) {
        }
        gen_queries(2, g->trace.queries);
        // Reading the flag clears it.
        GLint disjoint;
        glGetIntegerv(GPU_DISJOINT, &disjoint);
        g->trace.timed = glGetError() == GL_NO_ERROR;
        if (!g->trace.timed) {
            delete_queries(2, g->trace.queries);
            memset(g->trace.queries, 0, sizeof(g->trace.queries));
        }
    }
    return g;
}

// A uniform's location in program.
static GLint uniform(GLuint program, const char * name)
{
    return glGetUniformLocation(program, name);
}

// Copies source rows r0 until r0 + rows, the part the across taps read, into src; 0 on success.
static int upload(rs_gpu * g, GLuint src, int r0, int rows)
{
    const int64_t t = stamp(g);
    const rs_job * job = g->job;
    const rs_axis * ax = &job->ax;
    const size_t src_w = (size_t)(ax->end - ax->first);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, src);
    uint8_t * up = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, (GLsizeiptr)(src_w * rows * 4),
                                    GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
    if (up == NULL) return -1;
    note_core(g);
    for (int r = 0; r < rows; r++) {
        memcpy(up + (size_t)r * src_w * 4, job->src + (size_t)(r0 + r) * job->src_stride + (size_t)ax->first * 4, src_w * 4);
    }
    const int ok = glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    g->trace.upload_ns += stamp(g) - t;
    g->trace.rows += rows;
    return ok ? 0 : -1;
}

// Filters the `rows` source rows in src across into mid, fp32 rows as wide as the output.
static void run_across(const rs_gpu * g, GLuint src, GLuint mid, int rows)
{
    const rs_job * job = g->job;
    const rs_axis * ax = &job->ax;
    glUseProgram(g->across);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, src);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, g->axis_x);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, mid);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, lut_linear);
    glUniform4i(uniform(g->across, "dims"), ax->len, rows, ax->end - ax->first, job->linear);
    glUniform1i(uniform(g->across, "first"), ax->first);
    glUniform1f(uniform(g->across, "ring"), ax->ring);
    // Only the looping program has it; elsewhere location -1 is ignored.
    glUniform1i(uniform(g->across, "taps"), g->taps);
    glDispatchCompute((GLuint)((ax->len + GROUP - 1) / GROUP), (GLuint)((rows + ROWS - 1) / ROWS), 1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
}

// Filters mid down into out as packed bytes: output rows y0 until y1, mid's first row being source row r0.
static void run_down(const rs_gpu * g, GLuint mid, GLuint out, int y0, int y1, int r0)
{
    const rs_job * job = g->job;
    const rs_axis * ax = &job->ax;
    const rs_axis * ay = &job->ay;
    glUseProgram(down_program);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, mid);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, g->axis_y);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, out);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, lut_encode);
    glUniform4i(uniform(down_program, "dims"), ax->len, y1 - y0, y0, job->linear);
    glUniform1i(uniform(down_program, "firstRow"), r0);
    glUniform1i(uniform(down_program, "stride"), ay->stride);
    glUniform1f(uniform(down_program, "ring"), ay->ring);
    glUniform1i(uniform(down_program, "len"), ay->len);
    glDispatchCompute((GLuint)((ax->len + GROUP - 1) / GROUP), (GLuint)(y1 - y0), 1);
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
}

// Copies output rows y0 until y1 from out into the destination, waiting for the GPU; 0 on success.
static int readback(rs_gpu * g, GLuint out, int y0, int y1)
{
    const rs_job * job = g->job;
    const size_t row_bytes = (size_t)job->ax.len * 4;
    const int64_t t = stamp(g);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, out);
    const uint8_t * down = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, (GLsizeiptr)(row_bytes * (y1 - y0)), GL_MAP_READ_BIT);
    if (down == NULL) return -1;
    const int64_t mapped = stamp(g);
    g->trace.wait_ns += mapped - t;
    note_core(g);
    for (int y = y0; y < y1; y++) {
        memcpy(job->dst + (size_t)y * job->dst_stride, down + (size_t)(y - y0) * row_bytes, row_bytes);
    }
    const int ok = glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    g->trace.copy_ns += stamp(g) - mapped;
    return ok ? 0 : -1;
}

// Output rows y0 until y1, within one chunk: upload, across, down, readback.
static int chunk(rs_gpu * g, int y0, int y1)
{
    int r0;
    const int rows = rows_of(&g->job->ay, y0, y1, &r0);
    if (upload(g, g->src, r0, rows) != 0) return -1;
    const int64_t t = stamp(g);
    query_begin(g, 0);
    run_across(g, g->src, g->mid, rows);
    query_end(g);
    query_begin(g, 1);
    run_down(g, g->mid, g->out, y0, y1, r0);
    query_end(g);
    g->trace.submit_ns += stamp(g) - t;
    if (readback(g, g->out, y0, y1) != 0) return -1;
    query_collect(g);
    g->trace.chunks++;
    return glGetError() == GL_NO_ERROR ? 0 : -1;
}

int rs_gpu_band(rs_gpu * g, int y0, int y1, rs_stop stop, void * opaque)
{
    // Chunks end on multiples of the chunk size, each within one rs_gpu_open sized its buffers for.
    for (int y = y0; y < y1;) {
        if (stop != NULL && stop(opaque)) return 1;
        const int next = (y / g->chunk + 1) * g->chunk;
        const int end = next < y1 ? next : y1;
        if (chunk(g, y, end) != 0) return -1;
        y = end;
    }
    return 0;
}

void rs_gpu_trim(void)
{
}

void rs_gpu_close(rs_gpu * g)
{
    const int64_t t = stamp(g);
    const GLuint buffers[] = { g->axis_x, g->axis_y, g->src, g->mid, g->out };
    for (size_t i = 0; i < sizeof(buffers) / sizeof(buffers[0]); i++) {
        if (buffers[i] != 0) glDeleteBuffers(1, &buffers[i]);
    }
    if (g->trace.timed) delete_queries(2, g->trace.queries);
    const rs_trace trace = g->trace;
    const rs_job * job = g->job;
    const int chunk = g->chunk;
    const int64_t done = stamp(g);
    free(g);
    gl_end();
    pthread_mutex_unlock(&gl_lock);
    if (trace.on) trace_log(&trace, job, chunk, done - t, done - trace.opened);
}
