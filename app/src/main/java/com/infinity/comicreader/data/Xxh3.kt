package com.infinity.comicreader.data

/**
 * XXH3-64 with seed 0, as xxHash v0.8.3 computes it: what a Book ID is a hash by
 * ([BookIdBytes]). On the device this is [LibXxh3]; JVM tests stand in a pure-JVM
 * port, since they cannot load native code.
 */
fun interface Xxh3 {
    /** The hash of the first [length] bytes of [data]. */
    fun hash(data: ByteArray, length: Int): Long
}

/** xxHash's own XXH3-64, built from source (`src/main/cpp`), through JNI: one call per hash. */
object LibXxh3 : Xxh3 {
    init {
        System.loadLibrary("avifjni")
    }

    override fun hash(data: ByteArray, length: Int): Long {
        require(length in 0..data.size) { "$length of ${data.size} bytes" }
        return nHash(data, length)
    }

    /** The vector code XXH3 was built with: 0 scalar, 1 SSE2, 4 NEON (xxhash.h's `XXH_VECTOR`). */
    val vector: Int get() = nVector()

    @JvmStatic
    private external fun nHash(data: ByteArray, length: Int): Long

    @JvmStatic
    private external fun nVector(): Int
}
