// RAR reading (4 and 5) through libarchive for LibArchive.kt, from a Java source called back for
// reads, seeks and skips. A reader walks the archive's headers in order; the caller reopens it
// to go back. One thread at a time per reader.

#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <jni.h>
#include <locale.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// Bytes the source may fill per read.
#define BLOCK_BYTES (256 * 1024)

typedef struct {
    struct archive *a;
    struct archive_entry *entry;  // the current header, owned by a; NULL before the first and at the end
    JNIEnv *env;                  // the calling thread's, set by each call before libarchive may read
    jobject source;               // global reference
    jmethodID read, seek, skip;
    void *buffer;                 // memory the source's reads fill
    jobject buffer_ref;           // global reference to a direct ByteBuffer over buffer
    // Of the current entry: the block libarchive returned last and how much of it is not taken yet.
    const char *block;
    size_t block_left;
    int ended;                    // the current entry's data is all read
} Reader;

// Names come out as UTF-8 whatever the process's locale: libarchive's conversions follow the
// calling thread's locale, set to this one for each call.
static locale_t utf8_locale;

static locale_t enter(void) { return utf8_locale ? uselocale(utf8_locale) : (locale_t) 0; }

static void leave(locale_t old) {
    if (old) uselocale(old);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    utf8_locale = newlocale(LC_CTYPE_MASK, "C.UTF-8", (locale_t) 0);
    return JNI_VERSION_1_6;
}

static void throw_io(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;  // a source's own exception goes up instead
    jclass c = (*env)->FindClass(env, "java/io/IOException");
    if (c) (*env)->ThrowNew(env, c, message ? message : "archive error");
}

static void throw_archive(JNIEnv *env, Reader *r) { throw_io(env, archive_error_string(r->a)); }

static la_ssize_t source_read(struct archive *a, void *data, const void **buff) {
    Reader *r = data;
    JNIEnv *env = r->env;
    if ((*env)->ExceptionCheck(env)) return ARCHIVE_FATAL;
    jint n = (*env)->CallIntMethod(env, r->source, r->read, r->buffer_ref);
    if ((*env)->ExceptionCheck(env)) {
        archive_set_error(a, EIO, "source read failed");
        return ARCHIVE_FATAL;
    }
    *buff = r->buffer;
    return n < 0 ? 0 : n;
}

static la_int64_t source_seek(struct archive *a, void *data, la_int64_t offset, int whence) {
    Reader *r = data;
    JNIEnv *env = r->env;
    if ((*env)->ExceptionCheck(env)) return ARCHIVE_FATAL;
    jlong at = (*env)->CallLongMethod(env, r->source, r->seek, (jlong) offset, (jint) whence);
    if ((*env)->ExceptionCheck(env)) {
        archive_set_error(a, EIO, "source seek failed");
        return ARCHIVE_FATAL;
    }
    return at;
}

static la_int64_t source_skip(struct archive *a, void *data, la_int64_t request) {
    Reader *r = data;
    JNIEnv *env = r->env;
    if ((*env)->ExceptionCheck(env)) return ARCHIVE_FATAL;
    jlong skipped = (*env)->CallLongMethod(env, r->source, r->skip, (jlong) request);
    if ((*env)->ExceptionCheck(env)) {
        archive_set_error(a, EIO, "source skip failed");
        return ARCHIVE_FATAL;
    }
    return skipped;
}

static void free_reader(JNIEnv *env, Reader *r) {
    if (r->a) archive_read_free(r->a);
    if (r->source) (*env)->DeleteGlobalRef(env, r->source);
    if (r->buffer_ref) (*env)->DeleteGlobalRef(env, r->buffer_ref);
    free(r->buffer);
    free(r);
}

JNIEXPORT jstring JNICALL
Java_com_infinity_comicreader_reader_LibArchive_nVersion(JNIEnv *env, jclass c) {
    (void) c;
    return (*env)->NewStringUTF(env, archive_version_details());
}

// A reader of the RAR the source holds, from the source's current position (its start). Listing:
// moving past an entry of a solid RAR 4 jumps over its data instead of unpacking it.
JNIEXPORT jlong JNICALL
Java_com_infinity_comicreader_reader_LibArchive_nOpen(JNIEnv *env, jclass c, jobject source, jboolean listing) {
    (void) c;
    Reader *r = calloc(1, sizeof(Reader));
    if (!r) {
        throw_io(env, "out of memory");
        return 0;
    }
    r->env = env;
    jclass sc = (*env)->GetObjectClass(env, source);
    r->read = (*env)->GetMethodID(env, sc, "read", "(Ljava/nio/ByteBuffer;)I");
    r->seek = r->read ? (*env)->GetMethodID(env, sc, "seek", "(JI)J") : NULL;
    r->skip = r->seek ? (*env)->GetMethodID(env, sc, "skip", "(J)J") : NULL;
    (*env)->DeleteLocalRef(env, sc);
    if (!r->skip) {
        free(r);
        return 0;  // NoSuchMethodError is pending
    }
    r->source = (*env)->NewGlobalRef(env, source);
    r->buffer = malloc(BLOCK_BYTES);
    if (r->buffer) {
        jobject local = (*env)->NewDirectByteBuffer(env, r->buffer, BLOCK_BYTES);
        if (local) {
            r->buffer_ref = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
        }
    }
    if (!r->source || !r->buffer_ref || !(r->a = archive_read_new())) {
        free_reader(env, r);
        throw_io(env, "cannot open the archive");
        return 0;
    }
    archive_read_support_format_rar(r->a);
    archive_read_support_format_rar5(r->a);
    if (listing && archive_read_set_format_option(r->a, "rar", "solid-fast-skip", "1") != ARCHIVE_OK) {
        throw_archive(env, r);
        free_reader(env, r);
        return 0;
    }
    archive_read_set_read_callback(r->a, source_read);
    archive_read_set_seek_callback(r->a, source_seek);
    archive_read_set_skip_callback(r->a, source_skip);
    archive_read_set_callback_data(r->a, r);
    locale_t old = enter();
    int rc = archive_read_open1(r->a);
    leave(old);
    // A source callback that threw fails the call even when libarchive carried on.
    if (rc != ARCHIVE_OK || (*env)->ExceptionCheck(env)) {
        throw_archive(env, r);
        free_reader(env, r);
        return 0;
    }
    return (jlong) (intptr_t) r;
}

// The next header: its name as UTF-8 bytes, with meta = {size or -1, file type, header
// position, 1 when its data or the header itself is encrypted}; null at the end of the archive.
JNIEXPORT jbyteArray JNICALL
Java_com_infinity_comicreader_reader_LibArchive_nNext(JNIEnv *env, jclass c, jlong handle, jlongArray meta) {
    (void) c;
    Reader *r = (Reader *) (intptr_t) handle;
    r->env = env;
    r->block = NULL;
    r->block_left = 0;
    r->ended = 0;
    locale_t old = enter();
    int rc = archive_read_next_header(r->a, &r->entry);
    // A source callback that threw fails the call even when libarchive carried on.
    if (rc == ARCHIVE_EOF || rc < ARCHIVE_WARN || (*env)->ExceptionCheck(env)) {
        leave(old);
        r->entry = NULL;
        if (rc != ARCHIVE_EOF) throw_archive(env, r);
        return NULL;
    }
    const char *name = archive_entry_pathname_utf8(r->entry);
    if (!name) name = archive_entry_pathname(r->entry);
    leave(old);
    jlong values[4] = {
        archive_entry_size_is_set(r->entry) ? archive_entry_size(r->entry) : -1,
        archive_entry_filetype(r->entry),
        archive_read_header_position(r->a),
        archive_entry_is_encrypted(r->entry) ? 1 : 0
    };
    (*env)->SetLongArrayRegion(env, meta, 0, 4, values);
    size_t length = name ? strlen(name) : 0;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) length);
    if (out && length) (*env)->SetByteArrayRegion(env, out, 0, (jsize) length, (const jbyte *) name);
    return out;
}

// Makes the current entry's next block available; 0 at its end, -1 on failure (thrown).
static int next_block(JNIEnv *env, Reader *r) {
    while (r->block_left == 0) {
        if (r->ended) return 0;
        const void *buff;
        size_t size;
        la_int64_t offset;
        locale_t old = enter();
        int rc = archive_read_data_block(r->a, &buff, &size, &offset);
        leave(old);
        if ((*env)->ExceptionCheck(env)) return -1;  // a source callback threw
        if (rc == ARCHIVE_EOF) {
            r->ended = 1;
            return 0;
        }
        if (rc < ARCHIVE_WARN) {
            throw_archive(env, r);
            return -1;
        }
        r->block = buff;
        r->block_left = size;
    }
    return 1;
}

// Up to len bytes of the current entry into dst at off; -1 at its end.
JNIEXPORT jint JNICALL
Java_com_infinity_comicreader_reader_LibArchive_nRead(JNIEnv *env, jclass c, jlong handle, jbyteArray dst, jint off, jint len) {
    (void) c;
    Reader *r = (Reader *) (intptr_t) handle;
    r->env = env;
    if (len <= 0) return 0;
    if (!r->entry) {
        throw_io(env, "no entry");
        return -1;
    }
    if (next_block(env, r) <= 0) return -1;
    size_t n = r->block_left < (size_t) len ? r->block_left : (size_t) len;
    (*env)->SetByteArrayRegion(env, dst, off, (jsize) n, (const jbyte *) r->block);
    r->block += n;
    r->block_left -= n;
    return (jint) n;
}

JNIEXPORT void JNICALL
Java_com_infinity_comicreader_reader_LibArchive_nClose(JNIEnv *env, jclass c, jlong handle) {
    (void) c;
    Reader *r = (Reader *) (intptr_t) handle;
    if (!r) return;
    r->env = env;
    locale_t old = enter();
    free_reader(env, r);
    leave(old);
}
