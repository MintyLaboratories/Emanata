// GLES runner of mpv-style hook shaders for AI page processing (GlesShaders in Kotlin): each
// pass a fragment shader drawn as one triangle into a float texture, so the signed values
// networks keep between passes survive.
// - An offscreen OpenGL ES 3 context of its own, at low priority where EGL_IMG_context_priority
//   allows: the reader's frames go first. Intermediate textures are RGBA16F (bilinear, edges
//   clamped), else RGBA32F where 16-bit float targets are not renderable and 32-bit ones are
//   (with linear filtering); neither, and the runner is unusable.
// - A chain's programs compile when it opens (nOpen), or load from the program binaries an
//   earlier open wrote to its cache file under the same driver (GL_VERSION). Its plan
//   (Kotlin's ShaderPlan.encode) comes with every run: texture slots by size, then the draws.
//   Slot 0 is the input tile, 8-bit; the result is drawn once more into an 8-bit texture
//   (rounded, clamped) and read back.
// - Slot textures stay from run to run while their sizes hold, until nTrim frees them;
//   programs stay until nClose.
// - One call at a time: sh_lock is held for each, the context current only meanwhile (a
//   context the calling thread had current gets it back).
// - A run the GPU has not done within WAIT_LIMIT_NS, or FAILURES_OFF runs failed in a row,
//   turn the runner off for the process (nUsable says so), as resample_gpu.c does.

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define EXPORT JNIEXPORT __attribute__((visibility("default")))
#define TAG "ShaderGles"
#define WAIT_LIMIT_NS 10000000000ull
#define WAIT_SLICE_NS 100000000ull
#define FAILURES_OFF 3
// Most textures one pass binds; the GLES 3.0 minimum of fragment texture units.
#define MAX_BINDS 16

#ifndef EGL_CONTEXT_PRIORITY_LEVEL_IMG
#define EGL_CONTEXT_PRIORITY_LEVEL_IMG 0x3100
#define EGL_CONTEXT_PRIORITY_LOW_IMG 0x3103
#endif

static pthread_mutex_t sh_lock = PTHREAD_MUTEX_INITIALIZER;
// 0 untried, 1 ready, -1 unusable; written under sh_lock, read anywhere.
static atomic_int sh_state = 0;
static int failed_in_row;
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
static GLenum float_format;
static int float_bytes;
static GLint max_side;
static GLint max_units;
static GLuint vao;
static GLuint vertex_shader;
static GLuint encode_program;
static GLint encode_src;
static char renderer[128];
static char gl_version[128];

// What the calling thread had current, given back at sh_end.
typedef struct {
    EGLDisplay display;
    EGLContext context;
    EGLSurface draw;
    EGLSurface read;
} was_current;

typedef struct {
    GLuint program;
    int binds;
    GLint out;
    GLint target;
    GLint input;
    GLint size[MAX_BINDS];
    GLint pt[MAX_BINDS];
} pass;

typedef struct {
    GLuint tex;
    int w;
    int h;
} slot;

typedef struct {
    pass * passes;
    int count;
    slot * slots;
    int slot_count;
    // The result's 8-bit copy, read back.
    slot bytes;
    GLuint fbo;
    uint8_t * scratch;
    size_t scratch_size;
    int64_t held;
    int64_t peak;
} chain;

static int64_t now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}

static void clear_errors(void)
{
    for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) {
    }
}

static GLuint compile_shader(GLenum type, const char * source, const char * label)
{
    const GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &source, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char reason[1024] = "";
        glGetShaderInfoLog(s, sizeof(reason), NULL, reason);
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s: compile failed: %s", label, reason);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

// A program of the shared vertex shader and fragment source; 0, logged under label, when it fails.
static GLuint link_program(const char * fragment, const char * label)
{
    const GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fragment, label);
    if (fs == 0) return 0;
    const GLuint p = glCreateProgram();
    glAttachShader(p, vertex_shader);
    glAttachShader(p, fs);
    glProgramParameteri(p, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
    glLinkProgram(p);
    glDetachShader(p, fs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char reason[1024] = "";
        glGetProgramInfoLog(p, sizeof(reason), NULL, reason);
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s: link failed: %s", label, reason);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

// True when a texture of format can be drawn into here.
static int renderable(GLenum format)
{
    GLuint tex = 0, fbo = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, format, 4, 4);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    const int ok = glGetError() == GL_NO_ERROR && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    clear_errors();
    return ok;
}

static int has_extension(const char * name)
{
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; i++) {
        const char * e = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
        if (e != NULL && strcmp(e, name) == 0) return 1;
    }
    return 0;
}

// Changing VERTEX or ENCODE, or how passes draw, raises ShaderSource.RUNNER in Kotlin, so
// cached programs and stored pages of the old runner are not used.
static const char * VERTEX =
    "#version 300 es\n"
    "void main() {\n"
    "  vec2 p = vec2(float((gl_VertexID & 1) << 2), float((gl_VertexID & 2) << 1));\n"
    "  gl_Position = vec4(p - 1.0, 0.0, 1.0);\n"
    "}\n";

// The result as 8-bit values, rounded to the nearest code so the conversion is exact.
static const char * ENCODE =
    "#version 300 es\n"
    "precision highp float;\n"
    "uniform highp sampler2D src;\n"
    "out vec4 color;\n"
    "void main() {\n"
    "  vec3 v = clamp(texelFetch(src, ivec2(gl_FragCoord.xy), 0).rgb, 0.0, 1.0);\n"
    "  color = vec4(floor(v * 255.0 + 0.5) / 255.0, 1.0);\n"
    "}\n";

static void save_current(was_current * w)
{
    w->display = eglGetCurrentDisplay();
    w->context = eglGetCurrentContext();
    w->draw = eglGetCurrentSurface(EGL_DRAW);
    w->read = eglGetCurrentSurface(EGL_READ);
}

// Makes the context current, creating it the first time; 0 when usable. sh_lock held.
static int sh_begin(was_current * w)
{
    save_current(w);
    if (sh_state == 0) {
        sh_state = -1;
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL)) return -1;
        const EGLint config_attrs[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE };
        EGLConfig config;
        EGLint found = 0;
        if (!eglChooseConfig(display, config_attrs, &config, 1, &found) || found < 1) return -1;
        const char * extensions = eglQueryString(display, EGL_EXTENSIONS);
        const int low = extensions != NULL && strstr(extensions, "EGL_IMG_context_priority") != NULL;
        const EGLint low_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG, EGL_NONE };
        const EGLint plain_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, low ? low_attrs : plain_attrs);
        if (context == EGL_NO_CONTEXT && low) context = eglCreateContext(display, config, EGL_NO_CONTEXT, plain_attrs);
        if (context == EGL_NO_CONTEXT) return -1;
        if (extensions == NULL || strstr(extensions, "EGL_KHR_surfaceless_context") == NULL) {
            const EGLint surface_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
            surface = eglCreatePbufferSurface(display, config, surface_attrs);
            if (surface == EGL_NO_SURFACE) return -1;
        }
        if (!eglMakeCurrent(display, surface, surface, context)) return -1;
        GLint major = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        if (major < 3) return -1;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_side);
        glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &max_units);
        if (renderable(GL_RGBA16F)) {
            float_format = GL_RGBA16F;
            float_bytes = 8;
        } else if (renderable(GL_RGBA32F) && has_extension("GL_OES_texture_float_linear")) {
            float_format = GL_RGBA32F;
            float_bytes = 16;
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG, "no renderable float textures: shader path off");
            return -1;
        }
        vertex_shader = compile_shader(GL_VERTEX_SHADER, VERTEX, "vertex");
        if (vertex_shader == 0) return -1;
        encode_program = link_program(ENCODE, "encode");
        if (encode_program == 0) return -1;
        encode_src = glGetUniformLocation(encode_program, "src");
        glGenVertexArrays(1, &vao);
        const char * r = (const char *)glGetString(GL_RENDERER);
        snprintf(renderer, sizeof(renderer), "%s", r != NULL ? r : "?");
        const char * v = (const char *)glGetString(GL_VERSION);
        snprintf(gl_version, sizeof(gl_version), "%s", v != NULL ? v : "?");
        if (glGetError() != GL_NO_ERROR) return -1;
        __android_log_print(ANDROID_LOG_INFO, TAG, "ready: %s, %s, max side %d, %d units%s", renderer,
                            float_format == GL_RGBA16F ? "RGBA16F" : "RGBA32F", max_side, max_units, low ? ", low priority" : "");
        sh_state = 1;
        return 0;
    }
    if (sh_state < 0) return -1;
    return eglMakeCurrent(display, surface, surface, context) ? 0 : -1;
}

// Makes the context current whatever the runner's state, to free what it holds; 0 when there is one. sh_lock held.
static int sh_attach(was_current * w)
{
    save_current(w);
    if (context == EGL_NO_CONTEXT) return -1;
    return eglMakeCurrent(display, surface, surface, context) ? 0 : -1;
}

static void sh_end(const was_current * w)
{
    if (w->context != EGL_NO_CONTEXT && w->context != context) {
        eglMakeCurrent(w->display, w->draw, w->read, w->context);
    } else if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

static void free_slot(chain * c, slot * s, int bytes_per_pixel)
{
    if (s->tex != 0) {
        glDeleteTextures(1, &s->tex);
        c->held -= (int64_t)s->w * s->h * bytes_per_pixel;
    }
    s->tex = 0;
    s->w = 0;
    s->h = 0;
}

// Frees every slot texture, the context current. The framebuffer lets go of the one it was
// last drawn into first: GLES detaches a deleted texture only from a bound framebuffer, and
// an attached one keeps its storage.
static void free_slots(chain * c)
{
    if (c->fbo != 0) {
        glBindFramebuffer(GL_FRAMEBUFFER, c->fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    for (int i = 0; i < c->slot_count; i++) free_slot(c, &c->slots[i], i == 0 ? 4 : float_bytes);
    free_slot(c, &c->bytes, 4);
    free(c->slots);
    c->slots = NULL;
    c->slot_count = 0;
    free(c->scratch);
    c->scratch = NULL;
    c->scratch_size = 0;
}

// s as a w x h texture of format, kept when it already is one; 0 on success.
static int ensure(chain * c, slot * s, int w, int h, GLenum format, int bytes_per_pixel)
{
    if (s->tex != 0 && s->w == w && s->h == h) return 0;
    free_slot(c, s, bytes_per_pixel);
    if (w < 1 || h < 1 || w > max_side || h > max_side) return -1;
    glGenTextures(1, &s->tex);
    glBindTexture(GL_TEXTURE_2D, s->tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    s->w = w;
    s->h = h;
    c->held += (int64_t)w * h * bytes_per_pixel;
    if (c->held > c->peak) c->peak = c->held;
    return glGetError() == GL_NO_ERROR ? 0 : -1;
}

static int grow_scratch(chain * c, size_t bytes)
{
    if (c->scratch_size >= bytes) return 0;
    uint8_t * p = realloc(c->scratch, bytes);
    if (p == NULL) return -1;
    c->scratch = p;
    c->scratch_size = bytes;
    return 0;
}

// Counts a run's end: failures in a row turn the runner off, as does a hang. sh_lock held.
static void count(int failed, int hung)
{
    failed_in_row = failed ? failed_in_row + 1 : 0;
    if (hung) {
        sh_state = -1;
        __android_log_print(ANDROID_LOG_WARN, TAG, "a run took over %llu s: shader path off", WAIT_LIMIT_NS / 1000000000ull);
    } else if (failed_in_row >= FAILURES_OFF && sh_state == 1) {
        sh_state = -1;
        __android_log_print(ANDROID_LOG_WARN, TAG, "%d runs failed in a row: shader path off", failed_in_row);
    }
}

// "renderer\tfloat bytes\tmax side\tunits\tGL version", starting the context; NULL when unusable.
EXPORT jstring JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nInit(JNIEnv * env, jclass clazz)
{
    (void)clazz;
    pthread_mutex_lock(&sh_lock);
    was_current w;
    const int ok = sh_begin(&w) == 0;
    char info[340];
    if (ok) snprintf(info, sizeof(info), "%s\t%d\t%d\t%d\t%s", renderer, float_bytes, max_side, max_units, gl_version);
    sh_end(&w);
    pthread_mutex_unlock(&sh_lock);
    return ok ? (*env)->NewStringUTF(env, info) : NULL;
}

EXPORT jboolean JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nUsable(JNIEnv * env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return sh_state >= 0;
}

// Turns the runner off for the process, as failures would.
EXPORT void JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nTurnOff(JNIEnv * env, jclass clazz)
{
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&sh_lock);
    if (sh_state == 1) sh_state = -1;
    pthread_mutex_unlock(&sh_lock);
}

static void close_chain(chain * c)
{
    free_slots(c);
    for (int i = 0; i < c->count; i++) {
        if (c->passes[i].program != 0) glDeleteProgram(c->passes[i].program);
    }
    if (c->fbo != 0) glDeleteFramebuffers(1, &c->fbo);
    free(c->passes);
    free(c);
}

// Looks up p's uniforms and points its samplers at units 0 on; p->program in use.
static void locate(pass * p)
{
    p->out = glGetUniformLocation(p->program, "emanata_out");
    p->target = glGetUniformLocation(p->program, "emanata_target");
    p->input = glGetUniformLocation(p->program, "emanata_input");
    for (int b = 0; b < p->binds; b++) {
        char name[24];
        snprintf(name, sizeof(name), "emanata_t%d", b);
        glUniform1i(glGetUniformLocation(p->program, name), b);
        snprintf(name, sizeof(name), "emanata_s%d", b);
        p->size[b] = glGetUniformLocation(p->program, name);
        snprintf(name, sizeof(name), "emanata_p%d", b);
        p->pt[b] = glGetUniformLocation(p->program, name);
    }
}

// A program cache file: CACHE_MAGIC, the driver's GL_VERSION (length, then bytes), the
// program count, then per program its binary format, length and bytes.
#define CACHE_MAGIC 0x48534d45u

static int read_u32(FILE * f, uint32_t * v)
{
    return fread(v, 4, 1, f) == 1 ? 0 : -1;
}

// Loads c's n programs from the cache at path, made by this driver; 0 when all link.
static int load_cache(chain * c, int n, const char * path)
{
    FILE * f = fopen(path, "rb");
    if (f == NULL) return -1;
    const char * version = (const char *)glGetString(GL_VERSION);
    uint32_t magic = 0, len = 0, count = 0;
    int ok = read_u32(f, &magic) == 0 && magic == CACHE_MAGIC && read_u32(f, &len) == 0 && version != NULL && len == strlen(version);
    if (ok) {
        char * v = malloc(len + 1);
        ok = v != NULL && fread(v, 1, len, f) == len && memcmp(v, version, len) == 0;
        free(v);
    }
    ok = ok && read_u32(f, &count) == 0 && count == (uint32_t)n;
    for (int i = 0; ok && i < n; i++) {
        uint32_t format = 0, size = 0;
        ok = read_u32(f, &format) == 0 && read_u32(f, &size) == 0 && size > 0 && size < (64u << 20);
        void * data = ok ? malloc(size) : NULL;
        ok = data != NULL && fread(data, 1, size, f) == size;
        if (ok) {
            pass * p = &c->passes[i];
            p->program = glCreateProgram();
            glProgramBinary(p->program, format, data, (GLsizei)size);
            GLint linked = 0;
            glGetProgramiv(p->program, GL_LINK_STATUS, &linked);
            ok = linked != 0;
        }
        free(data);
    }
    fclose(f);
    if (!ok) {
        for (int i = 0; i < n; i++) {
            if (c->passes[i].program != 0) glDeleteProgram(c->passes[i].program);
            c->passes[i].program = 0;
        }
        clear_errors();
        return -1;
    }
    return 0;
}

// Writes c's n programs to the cache at path, through a temporary file; best effort.
static void save_cache(const chain * c, int n, const char * path)
{
    const char * version = (const char *)glGetString(GL_VERSION);
    if (version == NULL) return;
    char tmp[1024];
    if (snprintf(tmp, sizeof(tmp), "%s.part", path) >= (int)sizeof(tmp)) return;
    FILE * f = fopen(tmp, "wb");
    if (f == NULL) return;
    const uint32_t head[2] = { CACHE_MAGIC, (uint32_t)strlen(version) };
    int ok = fwrite(head, 4, 2, f) == 2 && fwrite(version, 1, head[1], f) == head[1];
    const uint32_t count = (uint32_t)n;
    ok = ok && fwrite(&count, 4, 1, f) == 1;
    for (int i = 0; ok && i < n; i++) {
        GLint size = 0;
        glGetProgramiv(c->passes[i].program, GL_PROGRAM_BINARY_LENGTH, &size);
        void * data = size > 0 ? malloc((size_t)size) : NULL;
        GLenum format = 0;
        GLsizei got = 0;
        if (data != NULL) glGetProgramBinary(c->passes[i].program, size, &got, &format, data);
        const uint32_t entry[2] = { format, (uint32_t)got };
        ok = data != NULL && got > 0 && glGetError() == GL_NO_ERROR && fwrite(entry, 4, 2, f) == 2 && fwrite(data, 1, (size_t)got, f) == (size_t)got;
        free(data);
    }
    ok = fclose(f) == 0 && ok;
    if (!ok || rename(tmp, path) != 0) remove(tmp);
    clear_errors();
}

// A chain of the fragment shaders `sources`, one program per pass binding binds[i] textures;
// 0 when one does not compile. With `cache` (a file path), the programs load from it when this
// driver made it, else compile and are written to it. A JNI call that fails (out of memory)
// leaves its exception pending: nothing more is asked of JNI but releases, and 0 returns.
EXPORT jlong JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nOpen(JNIEnv * env, jclass clazz, jobjectArray sources, jintArray binds, jstring cache)
{
    (void)clazz;
    const int n = (*env)->GetArrayLength(env, sources);
    if (n < 1 || (*env)->GetArrayLength(env, binds) != n) return 0;
    jint * bound = (*env)->GetIntArrayElements(env, binds, NULL);
    if (bound == NULL) return 0;
    const char * path = cache != NULL ? (*env)->GetStringUTFChars(env, cache, NULL) : NULL;
    if (cache != NULL && path == NULL) {
        (*env)->ReleaseIntArrayElements(env, binds, bound, JNI_ABORT);
        return 0;
    }
    chain * c = calloc(1, sizeof(*c));
    if (c != NULL) c->passes = calloc((size_t)n, sizeof(pass));
    pthread_mutex_lock(&sh_lock);
    was_current w;
    save_current(&w);
    const int64_t t0 = now_ns();
    // GL calls only while our context is current: none at all when it could not be made so.
    const int current = sh_begin(&w) == 0;
    int ok = c != NULL && c->passes != NULL && current;
    if (ok) {
        c->count = n;
        glGenFramebuffers(1, &c->fbo);
        for (int i = 0; i < n; i++) {
            c->passes[i].binds = bound[i];
            if (bound[i] < 0 || bound[i] > MAX_BINDS || bound[i] > max_units) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "pass %d binds %d textures, over %d", i, bound[i], max_units < MAX_BINDS ? max_units : MAX_BINDS);
                ok = 0;
            }
        }
    }
    const int cached = ok && path != NULL && load_cache(c, n, path) == 0;
    for (int i = 0; ok && !cached && i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, sources, i);
        const char * text = s != NULL && !(*env)->ExceptionCheck(env) ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        char label[32];
        snprintf(label, sizeof(label), "pass %d", i);
        c->passes[i].program = text != NULL ? link_program(text, label) : 0;
        if (text != NULL) (*env)->ReleaseStringUTFChars(env, s, text);
        if (s != NULL) (*env)->DeleteLocalRef(env, s);
        if (c->passes[i].program == 0) ok = 0;
    }
    for (int i = 0; ok && i < n; i++) {
        glUseProgram(c->passes[i].program);
        locate(&c->passes[i]);
    }
    if (ok && glGetError() != GL_NO_ERROR) ok = 0;
    if (current) glUseProgram(0);
    if (ok && !cached && path != NULL) save_cache(c, n, path);
    if (!ok && c != NULL && c->passes != NULL && current) {
        close_chain(c);
        c = NULL;
    } else if (!ok) {
        if (c != NULL) {
            free(c->passes);
            free(c);
        }
        c = NULL;
    }
    if (c != NULL) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s %d passes in %.1f ms", cached ? "loaded" : "compiled", n, (double)(now_ns() - t0) / 1e6);
    }
    sh_end(&w);
    pthread_mutex_unlock(&sh_lock);
    if (path != NULL) (*env)->ReleaseStringUTFChars(env, cache, path);
    (*env)->ReleaseIntArrayElements(env, binds, bound, JNI_ABORT);
    return (jlong)(intptr_t)c;
}

// Copies the input tile (x, y, w, h) of src, pixels past its edge repeating the nearest, into slot 0.
static int upload(chain * c, const uint8_t * px, int stride, int sw, int sh, int x, int y, int w, int h)
{
    const size_t row = (size_t)w * 4;
    if (grow_scratch(c, row * (size_t)h) != 0) return -1;
    for (int j = 0; j < h; j++) {
        int sy = y + j;
        sy = sy < 0 ? 0 : sy >= sh ? sh - 1 : sy;
        const uint32_t * from = (const uint32_t *)(px + (size_t)sy * stride);
        uint32_t * to = (uint32_t *)(c->scratch + row * j);
        // The part inside the source in one copy, its sides repeating the edge pixels.
        const int x0 = x < 0 ? -x : 0;
        const int x1 = x + w > sw ? sw - x : w;
        for (int i = 0; i < x0 && i < w; i++) to[i] = from[0];
        if (x1 > x0) memcpy(to + x0, from + x + x0, (size_t)(x1 - x0) * 4);
        for (int i = x1 > x0 ? x1 : x0; i < w; i++) to[i] = from[sw - 1];
    }
    glBindTexture(GL_TEXTURE_2D, c->slots[0].tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, c->scratch);
    return glGetError() == GL_NO_ERROR ? 0 : -1;
}

static void draw_into(const chain * c, GLuint tex, int w, int h)
{
    glBindFramebuffer(GL_FRAMEBUFFER, c->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glViewport(0, 0, w, h);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

// Waits for the GPU to finish what was queued; 0 done, -1 failed, 1 hung.
static int wait_gpu(void)
{
    const GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (fence == NULL) return -1;
    const int64_t began = now_ns();
    GLenum waited = GL_TIMEOUT_EXPIRED;
    for (GLbitfield flags = GL_SYNC_FLUSH_COMMANDS_BIT; (uint64_t)(now_ns() - began) < WAIT_LIMIT_NS; flags = 0) {
        waited = glClientWaitSync(fence, flags, WAIT_SLICE_NS);
        if (waited != GL_TIMEOUT_EXPIRED) break;
    }
    glDeleteSync(fence);
    if (waited == GL_TIMEOUT_EXPIRED) return 1;
    return waited == GL_ALREADY_SIGNALED || waited == GL_CONDITION_SATISFIED ? 0 : -1;
}

// Runs the passes of plan (see ShaderPlan.encode) on input tile (ix, iy, iw, ih) of src; with
// dst, writes the result's (cx, cy, cw, ch) to dst at (dx, dy), opaque. 0 on success.
static int run(JNIEnv * env, chain * c, const jint * plan, int plan_len, jobject src, int ix, int iy, int iw, int ih,
               jobject dst, int cx, int cy, int cw, int ch, int dx, int dy)
{
#define NEED(n) \
    do { \
        if (at + (n) > plan_len) return -2; \
    } while (0)
    int at = 0;
    NEED(3);
    const int target_w = plan[at++];
    const int target_h = plan[at++];
    const int slots = plan[at++];
    if (slots < 1 || slots > 4096) return -2;
    NEED(2 * slots);
    if (slots != c->slot_count) {
        free_slots(c);
        c->slots = calloc((size_t)slots, sizeof(slot));
        if (c->slots == NULL) return -3;
        c->slot_count = slots;
    }
    const int * sizes = plan + at;
    at += 2 * slots;
    if (sizes[0] != iw || sizes[1] != ih) return -2;
    clear_errors();
    for (int i = 0; i < slots; i++) {
        if (ensure(c, &c->slots[i], sizes[2 * i], sizes[2 * i + 1], i == 0 ? GL_RGBA8 : float_format, i == 0 ? 4 : float_bytes) != 0) return -3;
    }
    NEED(1);
    const int steps = plan[at++];
    const int * first_step = plan + at;
    // Checked before anything is drawn.
    for (int s = 0; s < steps; s++) {
        NEED(5);
        const int program = plan[at], out = plan[at + 1], binds = plan[at + 4];
        if (program < 0 || program >= c->count || out < 1 || out >= slots || binds != c->passes[program].binds) return -2;
        if (plan[at + 2] != c->slots[out].w || plan[at + 3] != c->slots[out].h) return -2;
        at += 5;
        NEED(binds);
        for (int b = 0; b < binds; b++) {
            if (plan[at + b] < 0 || plan[at + b] >= slots || plan[at + b] == out) return -2;
        }
        at += binds;
    }
    NEED(1);
    const int result = plan[at];
    if (result < 0 || result >= slots) return -2;
    const slot * res = &c->slots[result];
    if (dst != NULL && (cx < 0 || cy < 0 || cw < 1 || ch < 1 || cx + cw > res->w || cy + ch > res->h)) return -2;

    AndroidBitmapInfo si;
    void * spx = NULL;
    if (AndroidBitmap_getInfo(env, src, &si) != ANDROID_BITMAP_RESULT_SUCCESS || si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        si.width < 1 || si.height < 1 || AndroidBitmap_lockPixels(env, src, &spx) != ANDROID_BITMAP_RESULT_SUCCESS) return -4;
    const int up = upload(c, spx, (int)si.stride, (int)si.width, (int)si.height, ix, iy, iw, ih);
    AndroidBitmap_unlockPixels(env, src);
    if (up != 0) return -3;

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glBindVertexArray(vao);
    at = 0;
    for (int s = 0; s < steps; s++) {
        const int * st = first_step + at;
        const pass * p = &c->passes[st[0]];
        const slot * o = &c->slots[st[1]];
        glUseProgram(p->program);
        for (int b = 0; b < p->binds; b++) {
            const slot * in = &c->slots[st[5 + b]];
            glActiveTexture(GL_TEXTURE0 + (GLenum)b);
            glBindTexture(GL_TEXTURE_2D, in->tex);
            glUniform2f(p->size[b], (float)in->w, (float)in->h);
            glUniform2f(p->pt[b], 1.0f / (float)in->w, 1.0f / (float)in->h);
        }
        glUniform2f(p->out, (float)o->w, (float)o->h);
        glUniform2f(p->target, (float)target_w, (float)target_h);
        glUniform2f(p->input, (float)iw, (float)ih);
        draw_into(c, o->tex, o->w, o->h);
        at += 5 + p->binds;
    }
    if (ensure(c, &c->bytes, res->w, res->h, GL_RGBA8, 4) != 0) return -3;
    glUseProgram(encode_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, res->tex);
    glUniform1i(encode_src, 0);
    draw_into(c, c->bytes.tex, res->w, res->h);
    if (glGetError() != GL_NO_ERROR) return -5;
    const int waited = wait_gpu();
    if (waited != 0) return waited > 0 ? 1 : -5;
    if (dst != NULL) {
        const size_t row = (size_t)cw * 4;
        if (grow_scratch(c, row * (size_t)ch) != 0) return -3;
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(cx, cy, cw, ch, GL_RGBA, GL_UNSIGNED_BYTE, c->scratch);
        if (glGetError() != GL_NO_ERROR) return -5;
        AndroidBitmapInfo di;
        void * dpx = NULL;
        if (AndroidBitmap_getInfo(env, dst, &di) != ANDROID_BITMAP_RESULT_SUCCESS || di.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            dx < 0 || dy < 0 || dx + cw > (int)di.width || dy + ch > (int)di.height ||
            AndroidBitmap_lockPixels(env, dst, &dpx) != ANDROID_BITMAP_RESULT_SUCCESS) return -4;
        for (int j = 0; j < ch; j++) {
            uint32_t * to = (uint32_t *)((uint8_t *)dpx + (size_t)(dy + j) * di.stride) + dx;
            const uint32_t * from = (const uint32_t *)(c->scratch + row * j);
            for (int i = 0; i < cw; i++) to[i] = from[i] | 0xFF000000u;
        }
        AndroidBitmap_unlockPixels(env, dst);
    }
    return 0;
#undef NEED
}

EXPORT jint JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nRun(
    JNIEnv * env, jclass clazz, jlong handle, jintArray plan, jobject src, jint ix, jint iy, jint iw, jint ih,
    jobject dst, jint cx, jint cy, jint cw, jint ch, jint dx, jint dy)
{
    (void)clazz;
    chain * c = (chain *)(intptr_t)handle;
    if (c == NULL) return -1;
    const int len = (*env)->GetArrayLength(env, plan);
    jint * p = (*env)->GetIntArrayElements(env, plan, NULL);
    if (p == NULL) return -3;
    pthread_mutex_lock(&sh_lock);
    was_current w;
    const int current = sh_begin(&w) == 0;
    const int r = current ? run(env, c, p, len, src, ix, iy, iw, ih, dst, cx, cy, cw, ch, dx, dy) : -6;
    if (current) {
        if (r != 0) {
            // Whatever the GPU still holds of a failed run: none of it stays.
            clear_errors();
            free_slots(c);
        }
        // A plan that does not fit the chain, or bitmaps that will not do, are the caller's fault, not the GPU's.
        count(r != 0 && r != -2 && r != -4, r == 1);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    sh_end(&w);
    pthread_mutex_unlock(&sh_lock);
    (*env)->ReleaseIntArrayElements(env, plan, p, JNI_ABORT);
    if (r != 0) __android_log_print(ANDROID_LOG_WARN, TAG, "run %dx%d failed: %d", iw, ih, r);
    return r;
}

// Frees the chain's textures; its programs stay.
EXPORT void JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nTrim(JNIEnv * env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    chain * c = (chain *)(intptr_t)handle;
    if (c == NULL) return;
    pthread_mutex_lock(&sh_lock);
    was_current w;
    if (sh_attach(&w) == 0) {
        free_slots(c);
        glFlush();
    }
    sh_end(&w);
    pthread_mutex_unlock(&sh_lock);
}

// The most bytes the chain's textures held since the last call, which starts counting from what they hold now.
EXPORT jlong JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nPeak(JNIEnv * env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    chain * c = (chain *)(intptr_t)handle;
    if (c == NULL) return 0;
    pthread_mutex_lock(&sh_lock);
    const int64_t peak = c->peak;
    c->peak = c->held;
    pthread_mutex_unlock(&sh_lock);
    return peak;
}

EXPORT void JNICALL Java_com_infinity_comicreader_ai_GlesShaders_nClose(JNIEnv * env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    chain * c = (chain *)(intptr_t)handle;
    if (c == NULL) return;
    pthread_mutex_lock(&sh_lock);
    was_current w;
    if (sh_attach(&w) == 0) {
        close_chain(c);
        glFlush();
    } else {
        // The context is gone: so are its objects.
        free(c->slots);
        free(c->scratch);
        free(c->passes);
        free(c);
    }
    sh_end(&w);
    pthread_mutex_unlock(&sh_lock);
}
