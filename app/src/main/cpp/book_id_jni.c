// JNI binding over xxHash's XXH3-64 for com.infinity.comicreader.data.LibXxh3: one
// call hashes the bytes of a Book ID (BookIdBytes) as they lie in one Java array,
// pinned for the call, not copied. xxhash.h is compiled in whole (XXH_INLINE_ALL),
// with its NEON code path on arm64 and SSE2 on x86_64.

#include <jni.h>
#include <stddef.h>

#define XXH_INLINE_ALL
#include "xxhash.h"

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

// XXH3-64 (seed 0) of the first `length` bytes of `data`; 0 when `length` is out of range.
EXPORT jlong JNICALL Java_com_infinity_comicreader_data_LibXxh3_nHash(JNIEnv * env, jclass clazz, jbyteArray data, jint length)
{
    (void)clazz;
    if (length < 0 || length > (*env)->GetArrayLength(env, data)) {
        return 0;
    }
    void * bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (bytes == NULL) {
        return 0;
    }
    const XXH64_hash_t hash = XXH3_64bits(bytes, (size_t)length);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
    return (jlong)hash;
}

// Which vector code XXH3 was built with (XXH_VECTOR: 0 scalar, 1 SSE2, 4 NEON), for the device check.
EXPORT jint JNICALL Java_com_infinity_comicreader_data_LibXxh3_nVector(JNIEnv * env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return XXH_VECTOR;
}
