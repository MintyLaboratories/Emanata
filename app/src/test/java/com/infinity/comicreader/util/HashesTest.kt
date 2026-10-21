package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HashesTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun wholeDigestAsLowercaseHex() = assertEquals(abc, Hashes.sha256Hex("abc"))

    @Test
    fun leadingBytesOnly() = assertEquals(abc.substring(0, 32), Hashes.sha256Hex("abc", bytes = 16))

    @Test
    fun byteCountClampedToDigest() = assertEquals(abc, Hashes.sha256Hex("abc", bytes = 99))
}
