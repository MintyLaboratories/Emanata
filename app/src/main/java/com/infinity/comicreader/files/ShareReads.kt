package com.infinity.comicreader.files

import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.RemoteInput
import java.io.Closeable
import java.io.IOException

/**
 * Reads a file on a share, [size] bytes long, at any offset, for another app reading it
 * through [BookProvider]: opened by [open] at the first read, through a [RemoteChannel],
 * so reads that follow on from each other fetch growing runs of up to [MAX_RUN_BYTES]
 * and a read back into the last few runs costs no round trip. Nothing goes to storage.
 */
class ShareReads(val size: Long, private val open: () -> RemoteInput) : Closeable {
    private var channel: RemoteChannel? = null
    private var closed = false

    /** Round trips made so far; for tests. */
    val fetches: Int @Synchronized get() = channel?.fetches ?: 0

    /** Fills [into] with up to [count] bytes at [offset]; fewer only at the end, 0 past it. */
    @Synchronized
    @Throws(IOException::class)
    fun read(offset: Long, count: Int, into: ByteArray): Int {
        if (closed) throw IOException("Closed")
        if (offset < 0 || count < 0) throw IOException("Read of $count at $offset")
        if (offset >= size || count == 0) return 0
        val wanted = minOf(count.toLong(), size - offset, into.size.toLong()).toInt()
        val ch = channel ?: RemoteChannel(open(), maxBlock = MAX_RUN_BYTES, maxCached = MAX_KEPT_BYTES).also { channel = it }
        ch.position(offset)
        var n = 0
        while (n < wanted) {
            val r = ch.read(into, n, wanted - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** Lets go of the file; reads fail from then on. */
    @Synchronized
    override fun close() {
        closed = true
        channel?.let { runCatching { it.close() } }
        channel = null
    }

    companion object {
        /** The longest run one round trip fetches: a few MB keeps a streaming read near the link's speed. */
        const val MAX_RUN_BYTES = 4 shl 20

        /** What is kept of the runs fetched, for reads that go back a little. */
        const val MAX_KEPT_BYTES = 16L shl 20
    }
}
