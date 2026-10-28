package com.infinity.comicreader.data

/**
 * A book's identity by its contents ([BookIdBytes]): the same for every copy of a
 * file, wherever it is and whatever it is named, on any device. Per-book data is
 * kept by it, so it follows a book moved, renamed or copied. Written as 16
 * lowercase hex digits ([hex]).
 */
@JvmInline
value class BookId(val value: Long) {
    /** The 16 lowercase hex digits it is stored as. */
    val hex: String
        get() {
            val out = CharArray(16)
            for (i in 0 until 16) out[i] = HEX[(value ushr (60 - 4 * i)).toInt() and 0xF]
            return String(out)
        }

    override fun toString(): String = hex

    companion object {
        private const val HEX = "0123456789abcdef"

        /** The ID written as [hex] digits; null when that is not 16 hex digits. */
        fun parse(hex: String?): BookId? {
            if (hex == null || hex.length != 16) return null
            var v = 0L
            for (c in hex) {
                val d = Character.digit(c, 16)
                if (d < 0) return null
                v = (v shl 4) or d.toLong()
            }
            return BookId(v)
        }
    }
}

/**
 * The bytes a Book ID is the XXH3-64 (seed 0) of. **Never change this layout**: IDs
 * are stored, exported and carried between devices, and a different one would orphan
 * every book's data.
 *
 * ```
 * offset  length  bytes
 * 0       8       the file's size in bytes, unsigned 64-bit little-endian
 * 8       n       files under 64 KiB (65,536 bytes): the whole file (n = size)
 * 8       32768   otherwise: the file's first 32 KiB, then
 * 32776   32768     its last 32 KiB
 * ```
 *
 * So a file of 64 KiB or more gives 65,544 bytes, a smaller one 8 + its size.
 */
object BookIdBytes {
    /** Bytes taken from each end of a file of [WHOLE_BELOW] bytes or more. */
    const val END_BYTES = 32 * 1024

    /** Files smaller than this are taken whole. */
    const val WHOLE_BELOW = 2 * END_BYTES

    /** Bytes of the size field in front. */
    const val SIZE_BYTES = 8

    /** How many bytes the ID of a file of [size] bytes hashes. */
    fun length(size: Long): Int {
        require(size >= 0) { "Size $size" }
        return SIZE_BYTES + if (size < WHOLE_BELOW) size.toInt() else WHOLE_BELOW
    }

    /** Where in the file the bytes after the size come from: (file position, offset in the layout, length). */
    fun ranges(size: Long): List<Triple<Long, Int, Int>> = when {
        size == 0L -> emptyList()
        size < WHOLE_BELOW -> listOf(Triple(0L, SIZE_BYTES, size.toInt()))
        else -> listOf(Triple(0L, SIZE_BYTES, END_BYTES), Triple(size - END_BYTES, SIZE_BYTES + END_BYTES, END_BYTES))
    }

    /** A layout for a file of [size] bytes with the size written and the rest to fill ([ranges]). */
    fun start(size: Long): ByteArray {
        val out = ByteArray(length(size))
        for (i in 0 until SIZE_BYTES) out[i] = (size ushr (8 * i)).toByte()
        return out
    }

    /** The layout of a file of [size] bytes, each of its [ranges] filled by [read]`(position, into, offset, length)`. */
    fun of(size: Long, read: (Long, ByteArray, Int, Int) -> Unit): ByteArray {
        val out = start(size)
        for ((position, offset, length) in ranges(size)) read(position, out, offset, length)
        return out
    }

    /** The ID of [layout], one made by [of]. */
    fun idOf(layout: ByteArray, hash: Xxh3): BookId = BookId(hash.hash(layout, layout.size))
}
