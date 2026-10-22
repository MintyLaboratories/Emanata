package com.infinity.comicreader.net

import java.io.InputStream

/** [input] read from its start to its end, in the chunks asked for; closing it closes [input]. */
class RemoteInputStream(private val input: RemoteInput) : InputStream() {
    private var position = 0L
    private var mark = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= input.length) return -1
        val n = input.read(position, b, off, minOf(len.toLong(), input.length - position).toInt())
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = n.coerceIn(0, input.length - position)
        position += skipped
        return skipped
    }

    override fun available(): Int = (input.length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun markSupported(): Boolean = true

    override fun mark(readlimit: Int) {
        mark = position
    }

    override fun reset() {
        position = mark
    }

    override fun close() = input.close()
}
