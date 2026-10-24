package com.infinity.comicreader.net

import com.infinity.comicreader.util.Timing
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A read-only channel over a file on a share, for archive readers that seek (zip,
 * 7z, tar, rar). Reads go through runs fetched from [minBlock]-aligned offsets: a
 * run after a seek is [minBlock] long, so walking an archive's headers fetches little
 * around each; each run that follows on from the last fetched doubles, up to
 * [maxBlock], so a page streamed in small pieces costs few round trips. The most
 * recently used runs are kept, up to [maxCached] bytes. Calls are serialized.
 */
class RemoteChannel(
    private val input: RemoteInput,
    private val minBlock: Int = MIN_BLOCK_BYTES,
    private val maxBlock: Int = MAX_BLOCK_BYTES,
    private val maxCached: Long = MAX_CACHED_BYTES
) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private val size = input.length

    /** A fetched run of the file, from [start]. */
    private class Run(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    /** Kept runs, least recently used first. */
    private val runs = ArrayList<Run>()
    private var cachedBytes = 0L

    /** Where the last fetched run ended, and how long a run that follows on from it is. */
    private var lastEnd = -1L
    private var window = minBlock

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
            val run = runAt(position, dst.remaining())
            val at = (position - run.start).toInt()
            if (at >= run.bytes.size) break
            val n = minOf(dst.remaining(), run.bytes.size - at)
            dst.put(run.bytes, at, n)
            position += n
            total += n
        }
        return if (total == 0) -1 else total
    }

    /** Fills [buffer] from [offset] with up to [count] bytes at the current position; -1 at the end. */
    @Synchronized
    fun read(buffer: ByteArray, offset: Int, count: Int): Int = read(ByteBuffer.wrap(buffer, offset, count))

    /** The kept run holding [at], else one fetched from there, long enough for [wanted] bytes up to [maxBlock]. */
    private fun runAt(at: Long, wanted: Int): Run {
        for (i in runs.indices.reversed()) {
            val run = runs[i]
            if (at >= run.start && at < run.end) {
                if (i != runs.lastIndex) runs.add(runs.removeAt(i))
                return run
            }
        }
        val start = at - at % minBlock
        window = if (start == lastEnd) minOf(window.toLong() * 2, maxBlock.toLong()).toInt() else minBlock
        val reach = (at - start + wanted).coerceAtMost(maxBlock.toLong()).toInt()
        val length = minOf(maxOf(window, reach).toLong(), size - start).toInt()
        val bytes = ByteArray(length)
        var n = 0
        Timing.span("share read ${length / 1024} KB") {
            while (n < length) {
                val r = input.read(start + n, bytes, n, length - n)
                if (r < 0) break
                n += r
            }
        }
        fetches++
        val run = Run(start, if (n == length) bytes else bytes.copyOf(n))
        lastEnd = run.end
        runs.add(run)
        cachedBytes += run.bytes.size
        while (cachedBytes > maxCached && runs.size > 1) cachedBytes -= runs.removeAt(0).bytes.size
        return run
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
        runs.clear()
        cachedBytes = 0
        input.close()
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    companion object {
        const val MIN_BLOCK_BYTES = 64 * 1024
        const val MAX_BLOCK_BYTES = 1 shl 20
        const val MAX_CACHED_BYTES = 8L shl 20

        /** Opens the file at [path] on its share as a channel. */
        @Throws(IOException::class)
        fun open(path: String): RemoteChannel = RemoteChannel(Remotes.open(path))
    }
}
