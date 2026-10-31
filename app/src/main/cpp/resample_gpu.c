// The GPU path of resample.h: the same two passes as the CPU's, as OpenGL ES 3.1 compute
// shaders on an offscreen context of our own, in fp32.
// - Output rows go in chunks: the source rows a chunk's taps read are copied into a
//   buffer, filtered across into an fp32 buffer as wide as the output, then down into
//   packed bytes, which are copied into the destination. Chunks are sized so the source
//   rows and the fp32 ones together stay under CHUNK_BYTES, whatever the scale.
// - Each axis' taps go up once per job: four ints per output (start, count, anti-ringing
//   range) followed by its weights.
// - One job at a time: gl_lock is held from rs_gpu_open to rs_gpu_close, on one thread.

#include "resample.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define CHUNK_BYTES (32u << 20)
#define GROUP 64

static pthread_mutex_t gl_lock = PTHREAD_MUTEX_INITIALIZER;
// 0 untried, 1 ready, -1 unusable.
static int gl_state = 0;
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
static GLuint across_program;
static GLuint down_program;
static GLuint lut_linear;
static GLuint lut_encode;

struct rs_gpu {
    const rs_job * job;
    GLuint axis_x;
    GLuint axis_y;
    GLuint src;
    GLuint mid;
    GLuint out;
    int chunk;
    int max_rows;
};

static const char * ACROSS =
    "#version 310 es\n"
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
    "uniform int stride;\n"
    "uniform float ring;\n"
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
    "void main() {\n"
    "  int x = int(gl_GlobalInvocationID.x);\n"
    "  int r = int(gl_GlobalInvocationID.y);\n"
    "  if (x >= dims.x || r >= dims.y) return;\n"
    "  int row = r * dims.z;\n"
    "  int s = int(ax[4 * x]) - first;\n"
    "  int n = int(ax[4 * x + 1]);\n"
    "  int wb = 4 * dims.x + x * stride;\n"
    "  vec4 acc = uintBitsToFloat(ax[wb]) * px(row + s);\n"
    "  for (int t = 1; t < n; t++) acc += uintBitsToFloat(ax[wb + t]) * px(row + s + t);\n"
    "  if (ring > 0.0) {\n"
    "    int ra = clamp(int(ax[4 * x + 2]) - first - s, 0, n - 1);\n"
    "    int rb = clamp(int(ax[4 * x + 3]) - first - s, ra, n - 1);\n"
    "    vec4 lo = px(row + s + ra);\n"
    "    vec4 hi = lo;\n"
    "    for (int t = ra + 1; t <= rb; t++) { vec4 v = px(row + s + t); lo = min(lo, v); hi = max(hi, v); }\n"
    "    acc += ring * (min(max(acc, lo), hi) - acc);\n"
    "  }\n"
    "  mid[r * dims.x + x] = acc;\n"
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
        across_program = compile(ACROSS);
        down_program = compile(DOWN);
        if (across_program == 0 || down_program == 0) return -1;
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

// An axis' taps as the shaders read them: start, count, anti-ringing range, then weights.
static GLuint axis_buffer(const rs_axis * a)
{
    const size_t words = 4 * (size_t)a->len + (size_t)a->len * a->stride;
    uint32_t * data = malloc(words * 4);
    if (data == NULL) return 0;
    for (int i = 0; i < a->len; i++) {
        data[4 * i] = (uint32_t)a->start[i];
        data[4 * i + 1] = (uint32_t)a->count[i];
        data[4 * i + 2] = (uint32_t)(a->ring > 0 ? a->ring_lo[i] : a->start[i]);
        data[4 * i + 3] = (uint32_t)(a->ring > 0 ? a->ring_hi[i] : a->start[i]);
    }
    memcpy(data + 4 * (size_t)a->len, a->weights, (size_t)a->len * a->stride * 4);
    const GLuint b = buffer((GLsizeiptr)(words * 4), data, GL_STATIC_DRAW);
    free(data);
    return b;
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
    pthread_mutex_lock(&gl_lock);
    rs_gpu * g = calloc(1, sizeof(*g));
    if (g == NULL || gl_begin() != 0) {
        free(g);
        gl_end();
        pthread_mutex_unlock(&gl_lock);
        return NULL;
    }
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
    g->axis_x = axis_buffer(ax);
    g->axis_y = axis_buffer(ay);
    g->src = buffer((GLsizeiptr)(src_w * g->max_rows * 4), NULL, GL_STREAM_DRAW);
    g->mid = buffer((GLsizeiptr)((size_t)ax->len * g->max_rows * 16), NULL, GL_DYNAMIC_COPY);
    g->out = buffer((GLsizeiptr)((size_t)ax->len * g->chunk * 4), NULL, GL_STREAM_READ);
    if (g->axis_x == 0 || g->axis_y == 0 || glGetError() != GL_NO_ERROR) {
        rs_gpu_close(g);
        return NULL;
    }
    return g;
}

static int chunk(rs_gpu * g, int y0, int y1)
{
    const rs_job * job = g->job;
    const rs_axis * ax = &job->ax;
    const rs_axis * ay = &job->ay;
    int r0;
    const int rows = rows_of(ay, y0, y1, &r0);
    const size_t src_w = (size_t)(ax->end - ax->first);

    glBindBuffer(GL_SHADER_STORAGE_BUFFER, g->src);
    uint8_t * up = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, (GLsizeiptr)(src_w * rows * 4),
                                    GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
    if (up == NULL) return -1;
    for (int r = 0; r < rows; r++) {
        memcpy(up + (size_t)r * src_w * 4, job->src + (size_t)(r0 + r) * job->src_stride + (size_t)ax->first * 4, src_w * 4);
    }
    if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) return -1;

    glUseProgram(across_program);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, g->src);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, g->axis_x);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, g->mid);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, lut_linear);
    glUniform4i(glGetUniformLocation(across_program, "dims"), ax->len, rows, (GLint)src_w, job->linear);
    glUniform1i(glGetUniformLocation(across_program, "first"), ax->first);
    glUniform1i(glGetUniformLocation(across_program, "stride"), ax->stride);
    glUniform1f(glGetUniformLocation(across_program, "ring"), ax->ring);
    glDispatchCompute((GLuint)((ax->len + GROUP - 1) / GROUP), (GLuint)rows, 1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

    glUseProgram(down_program);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, g->mid);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, g->axis_y);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, g->out);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, lut_encode);
    glUniform4i(glGetUniformLocation(down_program, "dims"), ax->len, y1 - y0, y0, job->linear);
    glUniform1i(glGetUniformLocation(down_program, "firstRow"), r0);
    glUniform1i(glGetUniformLocation(down_program, "stride"), ay->stride);
    glUniform1f(glGetUniformLocation(down_program, "ring"), ay->ring);
    glUniform1i(glGetUniformLocation(down_program, "len"), ay->len);
    glDispatchCompute((GLuint)((ax->len + GROUP - 1) / GROUP), (GLuint)(y1 - y0), 1);
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);

    glBindBuffer(GL_SHADER_STORAGE_BUFFER, g->out);
    const size_t row_bytes = (size_t)ax->len * 4;
    const uint8_t * down = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, (GLsizeiptr)(row_bytes * (y1 - y0)), GL_MAP_READ_BIT);
    if (down == NULL) return -1;
    for (int y = y0; y < y1; y++) {
        memcpy(job->dst + (size_t)y * job->dst_stride, down + (size_t)(y - y0) * row_bytes, row_bytes);
    }
    if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) return -1;
    return glGetError() == GL_NO_ERROR ? 0 : -1;
}

int rs_gpu_band(rs_gpu * g, int y0, int y1)
{
    // Chunks end on multiples of the chunk size, each within one rs_gpu_open sized its buffers for.
    for (int y = y0; y < y1;) {
        const int next = (y / g->chunk + 1) * g->chunk;
        const int end = next < y1 ? next : y1;
        if (chunk(g, y, end) != 0) return -1;
        y = end;
    }
    return 0;
}

void rs_gpu_close(rs_gpu * g)
{
    const GLuint buffers[] = { g->axis_x, g->axis_y, g->src, g->mid, g->out };
    for (size_t i = 0; i < sizeof(buffers) / sizeof(buffers[0]); i++) {
        if (buffers[i] != 0) glDeleteBuffers(1, &buffers[i]);
    }
    free(g);
    gl_end();
    pthread_mutex_unlock(&gl_lock);
}
