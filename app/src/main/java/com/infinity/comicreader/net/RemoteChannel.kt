package com.infinity.comicreader.net

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A read-only channel over a file on a share, for archive readers that seek (zip,
 * 7z, tar, rar). Reads go through whole aligned blocks of [blockSize] bytes, the
 * last [blocks] of them kept, so the many small reads of an archive's headers and
 * a page streamed in small pieces cost few round trips. Calls are serialized.
 */
class RemoteChannel(
    private val input: RemoteInput,
    private val blockSize: Int = BLOCK_BYTES,
    private val blocks: Int = MAX_BLOCKS
) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private val size = input.length

    /** Kept blocks by index, least recently used first. */
    private val cache = object : LinkedHashMap<Long, ByteArray>(blocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > blocks
    }

    /** Round trips made so far; for tests. */
    var fetches = 0
        private set

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        ensureOpen()
        if (!dst.hasRemaining()) return 0
        if (position >= size) return -1
        var total = 0
        while (dst.hasRemaining() && position < size) {
            val index = position / blockSize
            val block = block(index)
            val at = (position - index * blockSize).toInt()
            if (at >= block.size) break
            val n = minOf(dst.remaining(), block.size - at)
            dst.put(block, at, n)
            position += n
            total += n
        }
        return if (total == 0) -1 else total
    }

    /** Fills [buffer] from [offset] with up to [count] bytes at the current position; -1 at the end. */
    @Synchronized
    fun read(buffer: ByteArray, offset: Int, count: Int): Int = read(ByteBuffer.wrap(buffer, offset, count))

    private fun block(index: Long): ByteArray {
        cache[index]?.let { return it }
        val start = index * blockSize
        val length = minOf(blockSize.toLong(), size - start).toInt()
        val bytes = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(start + n, bytes, n, length - n)
            if (r < 0) break
            n += r
        }
        fetches++
        val block = if (n == length) bytes else bytes.copyOf(n)
        cache[index] = block
        return block
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    @Synchronized
    override fun position(): Long {
        ensureOpen()
        return position
    }

    @Synchronized
    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        if (newPosition < 0) throw IllegalArgumentException("Negative position")
        position = newPosition
        return this
    }

    override fun size(): Long {
        ensureOpen()
        return size
    }

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    @Synchronized
    override fun isOpen(): Boolean = open

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        cache.clear()
        input.close()
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    companion object {
        const val BLOCK_BYTES = 1 shl 20
        const val MAX_BLOCKS = 8

        /** Opens the file at [path] on its share as a channel. */
        @Throws(IOException::class)
        fun open(path: String): RemoteChannel = RemoteChannel(Remotes.open(path))
    }
}
