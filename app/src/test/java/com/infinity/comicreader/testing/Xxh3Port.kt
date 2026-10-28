package com.infinity.comicreader.testing

import com.infinity.comicreader.data.Xxh3

/*
 * A pure-Kotlin port of XXH3-64 with seed 0 and the default secret, after xxHash
 * v0.8.3's scalar code, so JVM tests compute Book IDs as the device's native
 * xxHash does.
 *
 * xxHash - Extremely Fast Hash algorithm
 * Copyright (C) 2012-2023 Yann Collet
 *
 * BSD 2-Clause License (https://www.opensource.org/licenses/bsd-license.php)
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this
 *   list of conditions and the following disclaimer in the documentation and/or
 *   other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/** XXH3-64 (seed 0) in plain Kotlin: the JVM tests' stand-in for [com.infinity.comicreader.data.LibXxh3]. */
object Xxh3Port : Xxh3 {
    private const val P32_1 = 0x9E3779B1L
    private const val P32_2 = 0x85EBCA77L
    private const val P32_3 = 0xC2B2AE3DL
    private const val P64_1 = -0x61c8864e7a143579L // 0x9E3779B185EBCA87
    private const val P64_2 = -0x3d4d51c2d82b14b1L // 0xC2B2AE3D27D4EB4F
    private const val P64_3 = 0x165667B19E3779F9L
    private const val P64_4 = -0x7a1435883d4d519dL // 0x85EBCA77C2B2AE63
    private const val P64_5 = 0x27D4EB2F165667C5L
    private const val MIX_1 = 0x165667919E3779F9L
    private const val MIX_2 = -0x604de39ae16720dbL // 0x9FB21C651E98DF25

    private const val STRIPE = 64
    private const val STRIPES_PER_BLOCK = 16 // (secret length 192 - stripe 64) / 8

    /** The default secret, 192 bytes. */
    private val SECRET: ByteArray = intArrayOf(
        0xb8, 0xfe, 0x6c, 0x39, 0x23, 0xa4, 0x4b, 0xbe, 0x7c, 0x01, 0x81, 0x2c, 0xf7, 0x21, 0xad, 0x1c,
        0xde, 0xd4, 0x6d, 0xe9, 0x83, 0x90, 0x97, 0xdb, 0x72, 0x40, 0xa4, 0xa4, 0xb7, 0xb3, 0x67, 0x1f,
        0xcb, 0x79, 0xe6, 0x4e, 0xcc, 0xc0, 0xe5, 0x78, 0x82, 0x5a, 0xd0, 0x7d, 0xcc, 0xff, 0x72, 0x21,
        0xb8, 0x08, 0x46, 0x74, 0xf7, 0x43, 0x24, 0x8e, 0xe0, 0x35, 0x90, 0xe6, 0x81, 0x3a, 0x26, 0x4c,
        0x3c, 0x28, 0x52, 0xbb, 0x91, 0xc3, 0x00, 0xcb, 0x88, 0xd0, 0x65, 0x8b, 0x1b, 0x53, 0x2e, 0xa3,
        0x71, 0x64, 0x48, 0x97, 0xa2, 0x0d, 0xf9, 0x4e, 0x38, 0x19, 0xef, 0x46, 0xa9, 0xde, 0xac, 0xd8,
        0xa8, 0xfa, 0x76, 0x3f, 0xe3, 0x9c, 0x34, 0x3f, 0xf9, 0xdc, 0xbb, 0xc7, 0xc7, 0x0b, 0x4f, 0x1d,
        0x8a, 0x51, 0xe0, 0x4b, 0xcd, 0xb4, 0x59, 0x31, 0xc8, 0x9f, 0x7e, 0xc9, 0xd9, 0x78, 0x73, 0x64,
        0xea, 0xc5, 0xac, 0x83, 0x34, 0xd3, 0xeb, 0xc3, 0xc5, 0x81, 0xa0, 0xff, 0xfa, 0x13, 0x63, 0xeb,
        0x17, 0x0d, 0xdd, 0x51, 0xb7, 0xf0, 0xda, 0x49, 0xd3, 0x16, 0x55, 0x26, 0x29, 0xd4, 0x68, 0x9e,
        0x2b, 0x16, 0xbe, 0x58, 0x7d, 0x47, 0xa1, 0xfc, 0x8f, 0xf8, 0xb8, 0xd1, 0x7a, 0xd0, 0x31, 0xce,
        0x45, 0xcb, 0x3a, 0x8f, 0x95, 0x16, 0x04, 0x28, 0xaf, 0xd7, 0xfb, 0xca, 0xbb, 0x4b, 0x40, 0x7e
    ).let { b -> ByteArray(b.size) { b[it].toByte() } }

    override fun hash(data: ByteArray, length: Int): Long {
        require(length in 0..data.size)
        return when {
            length <= 16 -> upTo16(data, length)
            length <= 128 -> upTo128(data, length)
            length <= 240 -> upTo240(data, length)
            else -> long(data, length)
        }
    }

    private fun le64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 3 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun secret64(at: Int) = le64(SECRET, at)

    /** The high 64 bits of the unsigned 128-bit product of [a] and [b]. */
    private fun mulHigh(a: Long, b: Long): Long {
        val aLo = a and 0xFFFFFFFFL
        val aHi = a ushr 32
        val bLo = b and 0xFFFFFFFFL
        val bHi = b ushr 32
        val lo = aLo * bLo
        val mid1 = aHi * bLo
        val mid2 = aLo * bHi
        val cross = (lo ushr 32) + (mid1 and 0xFFFFFFFFL) + mid2
        return aHi * bHi + (mid1 ushr 32) + (cross ushr 32)
    }

    /** The 128-bit product of [a] and [b] folded to 64 bits: low xor high. */
    private fun mulFold(a: Long, b: Long): Long = (a * b) xor mulHigh(a, b)

    private fun avalanche64(h0: Long): Long {
        var h = h0
        h = h xor (h ushr 33)
        h *= P64_2
        h = h xor (h ushr 29)
        h *= P64_3
        return h xor (h ushr 32)
    }

    private fun avalanche(h0: Long): Long {
        var h = h0 xor (h0 ushr 37)
        h *= MIX_1
        return h xor (h ushr 32)
    }

    private fun rrmxmx(h0: Long, length: Int): Long {
        var h = h0 xor (java.lang.Long.rotateLeft(h0, 49) xor java.lang.Long.rotateLeft(h0, 24))
        h *= MIX_2
        h = h xor ((h ushr 35) + length)
        h *= MIX_2
        return h xor (h ushr 28)
    }

    private fun mix16(b: ByteArray, at: Int, secretAt: Int): Long =
        mulFold(le64(b, at) xor secret64(secretAt), le64(b, at + 8) xor secret64(secretAt + 8))

    private fun upTo16(b: ByteArray, n: Int): Long = when {
        n > 8 -> {
            val lo = le64(b, 0) xor (secret64(24) xor secret64(32))
            val hi = le64(b, n - 8) xor (secret64(40) xor secret64(48))
            avalanche(n + java.lang.Long.reverseBytes(lo) + hi + mulFold(lo, hi))
        }
        n >= 4 -> {
            val first = le32(b, 0)
            val last = le32(b, n - 4)
            rrmxmx((last + (first shl 32)) xor (secret64(8) xor secret64(16)), n)
        }
        n > 0 -> {
            val c1 = b[0].toLong() and 0xFF
            val c2 = b[n shr 1].toLong() and 0xFF
            val c3 = b[n - 1].toLong() and 0xFF
            val combined = ((c1 shl 16) or (c2 shl 24) or c3 or (n.toLong() shl 8)) and 0xFFFFFFFFL
            avalanche64(combined xor (le32(SECRET, 0) xor le32(SECRET, 4)))
        }
        else -> avalanche64(secret64(56) xor secret64(64))
    }

    private fun upTo128(b: ByteArray, n: Int): Long {
        var acc = n * P64_1
        if (n > 32) {
            if (n > 64) {
                if (n > 96) {
                    acc += mix16(b, 48, 96)
                    acc += mix16(b, n - 64, 112)
                }
                acc += mix16(b, 32, 64)
                acc += mix16(b, n - 48, 80)
            }
            acc += mix16(b, 16, 32)
            acc += mix16(b, n - 32, 48)
        }
        acc += mix16(b, 0, 0)
        acc += mix16(b, n - 16, 16)
        return avalanche(acc)
    }

    private fun upTo240(b: ByteArray, n: Int): Long {
        var acc = n * P64_1
        for (i in 0 until 8) acc += mix16(b, 16 * i, 16 * i)
        acc = avalanche(acc)
        for (i in 8 until n / 16) acc += mix16(b, 16 * i, 16 * (i - 8) + 3)
        // The last 16 bytes against the secret's end, less 17.
        acc += mix16(b, n - 16, 136 - 17)
        return avalanche(acc)
    }

    /** One stripe of 64 bytes at [at] into [acc], against the secret from [secretAt]. */
    private fun accumulate(acc: LongArray, b: ByteArray, at: Int, secretAt: Int) {
        for (i in 0 until 8) {
            val data = le64(b, at + 8 * i)
            val key = data xor secret64(secretAt + 8 * i)
            acc[i xor 1] += data
            acc[i] += (key and 0xFFFFFFFFL) * (key ushr 32)
        }
    }

    private fun scramble(acc: LongArray) {
        for (i in 0 until 8) {
            var a = acc[i]
            a = a xor (a ushr 47)
            a = a xor secret64(SECRET.size - STRIPE + 8 * i)
            acc[i] = a * P32_1
        }
    }

    private fun long(b: ByteArray, n: Int): Long {
        val acc = longArrayOf(P32_3, P64_1, P64_2, P64_3, P64_4, P32_2, P64_5, P32_1)
        val blockBytes = STRIPE * STRIPES_PER_BLOCK
        val blocks = (n - 1) / blockBytes
        for (block in 0 until blocks) {
            for (s in 0 until STRIPES_PER_BLOCK) accumulate(acc, b, block * blockBytes + s * STRIPE, s * 8)
            scramble(acc)
        }
        val stripes = ((n - 1) - blockBytes * blocks) / STRIPE
        for (s in 0 until stripes) accumulate(acc, b, blocks * blockBytes + s * STRIPE, s * 8)
        // The last stripe, ending at the end, against the secret's end less 7.
        accumulate(acc, b, n - STRIPE, SECRET.size - STRIPE - 7)
        var result = n * P64_1
        for (i in 0 until 4) result += mulFold(acc[2 * i] xor secret64(11 + 16 * i), acc[2 * i + 1] xor secret64(11 + 16 * i + 8))
        return avalanche(result)
    }
}
