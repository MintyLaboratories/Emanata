package com.infinity.comicreader.net

import com.infinity.comicreader.util.Timing
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/**
 * A read-only channel over a file on a share, for archive readers that seek (zip,
 * 7z, tar, rar) and for streams. Reads go through runs fetched from [seekBlock]-aligned
 * offsets: a run after a seek is [seekBlock] long, so walking an archive's headers
 * fetches little around each; each run that follows on from the last fetched doubles, up
 * to [maxBlock], so a page streamed in small pieces costs few round trips. A span the
 * reader is about to read whole ([expect]) is fetched in runs of up to [maxBlock] from
 * its first read on. Each run is asked for at once ([RemoteInput.readFully]). While a
 * stream reads a run of at least [aheadFrom] bytes that followed on from the last, or one
 * inside an expected span, the next two are fetched in the background ([readAhead]),
 * within a budget all channels share and never while [thrifty]. The most recently used runs are
 * kept, up to [maxCached] bytes; the array of a run let go is reused for the next of its
 * length, so a long streamed read allocates little. Calls are serialized.
 */
class RemoteChannel(
    private val input: RemoteInput,
    minBlock: Int = MIN_BLOCK_BYTES,
    private val maxBlock: Int = MAX_BLOCK_BYTES,
    private val maxCached: Long = MAX_CACHED_BYTES,
    private val readAhead: Boolean = true,
    private val aheadFrom: Int = AHEAD_FROM_BYTES,
    private val background: Executor = AHEAD
) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private val size = input.length
    private var minBlock = minBlock

    /** How long a run fetched after a seek is: small while walking an archive's headers, larger for its pages. */
    var seekBlock: Int
        @Synchronized get() = minBlock
        @Synchronized set(value) {
            require(value > 0) { "Block of $value bytes" }
            minBlock = value
            window = value
        }

    /** A fetched run of the file, from [start]. */
    private class Run(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    /** A run being fetched in the background, [length] bytes from [start], counted in [BUDGET] until let go. */
    private class Ahead(val start: Long, val length: Int, val task: FutureTask<ByteArray>) {
        fun holds(at: Long) = at >= start && at < start + length
    }

    /** Kept runs, least recently used first. */
    private val runs = ArrayList<Run>()
    private var cachedBytes = 0L

    /** Where the last fetched run ended, and how long a run that follows on from it is. */
    private var lastEnd = -1L
    private var window = minBlock

    /** Spans about to be read whole ([expect]), as start and end, the latest last. */
    private val spans = ArrayDeque<LongArray>()

    /** Runs being fetched ahead, in file order, each from where the one before ends. */
    private val coming = ArrayDeque<Ahead>()

    /** The array of the run last let go, for the next run of its length. */
    private var spare: ByteArray? = null

    /** Round trips made so far, those ahead included; for tests. */
    var fetches = 0
        private set

    /** Runs fetched ahead so far; for tests. */
    var aheads = 0
        private set

    /** Arrays made for runs so far; for tests. */
    var allocations = 0
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

    /**
     * Says the [length] bytes from [start] are about to be read whole, as an archive
     * entry's are: a read inside them fetches up to [maxBlock] of them at once, and the
     * rest comes ahead. The latest [MAX_SPANS] are kept.
     */
    @Synchronized
    fun expect(start: Long, length: Long) {
        if (length <= 0 || start < 0 || start >= size) return
        spans.removeAll { it[0] == start }
        spans.addLast(longArrayOf(start, minOf(size, start + length)))
        while (spans.size > MAX_SPANS) spans.removeFirst()
    }

    /** The end of the latest expected span holding [at]; -1 when none does. */
    private fun spanEnd(at: Long): Long {
        for (i in spans.indices.reversed()) {
            val s = spans[i]
            if (at >= s[0] && at < s[1]) return s[1]
        }
        return -1
    }

    /** The kept run holding [at], else the one fetched ahead there, else one fetched now, long enough for [wanted] bytes up to [maxBlock]. */
    private fun runAt(at: Long, wanted: Int): Run {
        for (i in runs.indices.reversed()) {
            val run = runs[i]
            if (at >= run.start && at < run.end) {
                if (i != runs.lastIndex) runs.add(runs.removeAt(i))
                return run
            }
        }
        val run = adopt(at) ?: fetch(at, wanted)
        keep(run)
        fetchAhead(run)
        return run
    }

    /**
     * The run fetched ahead that holds [at], waited for, those before it let go; null
     * without one. When none holds [at] the stream went elsewhere: those done are let go.
     */
    private fun adopt(at: Long): Run? {
        val i = coming.indexOfFirst { it.holds(at) }
        if (i < 0) {
            coming.filter { it.task.isDone }.forEach(::drop)
            return null
        }
        repeat(i) { drop(coming.first()) }
        val a = coming.first()
        drop(a)
        val bytes = runCatching { Timing.span("share read ahead wait") { a.task.get() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        lastEnd = a.start + bytes.size
        return Run(a.start, bytes)
    }

    private fun drop(a: Ahead) {
        if (coming.remove(a)) BUDGET.addAndGet(-a.length.toLong())
    }

    private fun fetch(at: Long, wanted: Int): Run {
        val spanEnd = spanEnd(at)
        val start: Long
        val length: Int
        if (spanEnd > at) {
            // Inside a span about to be read whole: as much of it as a run takes.
            start = at
            length = minOf(spanEnd - at, maxOf(maxBlock, wanted).toLong()).toInt()
            window = minOf(length, maxBlock)
        } else {
            // A read going on from the last run fetched continues it; any other starts at an aligned offset.
            val follows = at == lastEnd
            start = if (follows) at else at - at % minBlock
            window = if (follows || start == lastEnd) minOf(window.toLong() * 2, maxBlock.toLong()).toInt() else minBlock
            val reach = (at - start + wanted).coerceAtMost(maxBlock.toLong()).toInt()
            length = minOf(maxOf(window, reach).toLong(), size - start).toInt()
        }
        val bytes = arrayOf(length)
        val n = Timing.span("share read ${length / 1024} KB") { fill(start, bytes, length) }
        fetches++
        lastEnd = start + n
        return Run(start, if (n == length) bytes else bytes.copyOf(n))
    }

    /** Fills [bytes] with up to [length] bytes at [start]; the count read. */
    private fun fill(start: Long, bytes: ByteArray, length: Int): Int {
        var n = 0
        while (n < length) {
            val r = input.readFully(start + n, bytes, n, length - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** An array of [length] bytes: the spare one when it fits. */
    private fun arrayOf(length: Int): ByteArray =
        spare?.takeIf { it.size == length }?.also { spare = null } ?: ByteArray(length).also { allocations++ }

    private fun keep(run: Run) {
        runs.add(run)
        cachedBytes += run.bytes.size
        while (cachedBytes > maxCached && runs.size > 1) {
            val old = runs.removeAt(0)
            cachedBytes -= old.bytes.size
            spare = old.bytes
        }
    }

    /**
     * Keeps up to [MAX_COMING] runs after [run] being fetched in the background when a
     * stream reads [run]: inside an expected span with more of it after, or once [run],
     * at least [aheadFrom] long, followed on from the run before; two, so the next fetch
     * is under way while the last one's final pieces come. Within [MAX_AHEAD_BYTES] for
     * all channels, never while [thrifty].
     */
    private fun fetchAhead(run: Run) {
        if (!readAhead || thrifty || run.end >= size || run.end != lastEnd) return
        // Runs coming from elsewhere are let be until done; none are added after them.
        if (coming.isNotEmpty() && coming.first().start != run.end) return
        val spanEnd = spanEnd(run.start)
        val inSpan = spanEnd > run.end
        if (!inSpan && (run.bytes.size < aheadFrom || window < aheadFrom)) return
        val limit = if (inSpan) spanEnd else size
        var next = coming.lastOrNull()?.let { it.start + it.length } ?: run.end
        while (coming.size < MAX_COMING && next < limit) {
            val grown = if (inSpan) maxBlock.toLong() else minOf(window.toLong() * 2, maxBlock.toLong())
            val length = minOf(grown, limit - next).toInt()
            if (BUDGET.addAndGet(length.toLong()) > MAX_AHEAD_BYTES) {
                BUDGET.addAndGet(-length.toLong())
                return
            }
            val start = next
            val bytes = arrayOf(length)
            val task = FutureTask {
                val n = Timing.span("share read ahead ${length / 1024} KB") { fill(start, bytes, length) }
                if (n == length) bytes else bytes.copyOf(n)
            }
            val a = Ahead(start, length, task)
            coming.addLast(a)
            fetches++
            aheads++
            window = maxOf(window, length)
            next += length
            try {
                background.execute(task)
            } catch (e: RuntimeException) {
                drop(a)
                return
            }
        }
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

    /** Closes the file; a run being fetched ahead fails with it, never waited for. */
    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        coming.toList().forEach(::drop)
        runs.clear()
        spans.clear()
        cachedBytes = 0
        spare = null
        input.close()
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    companion object {
        const val MIN_BLOCK_BYTES = 64 * 1024

        /** [seekBlock] while walking headers spread through an archive (rar, tar): a few fit, a page's worth is not fetched with each. */
        const val HEADER_BLOCK_BYTES = 8 * 1024

        /** The longest run: several reads in flight fetch it at the link's speed. */
        const val MAX_BLOCK_BYTES = 8 shl 20

        /** Room for two pages streaming at once in the longest runs, and some of what came before. */
        const val MAX_CACHED_BYTES = 24L shl 20

        /** How long a stream's runs must have grown before the next is fetched ahead: header walks never get there. */
        const val AHEAD_FROM_BYTES = 1 shl 20

        /** What all channels together may have fetched ahead and not read yet. */
        const val MAX_AHEAD_BYTES = 64L shl 20

        /** How many runs a stream has coming ahead at most. */
        private const val MAX_COMING = 2

        /** Spans [expect] keeps. */
        private const val MAX_SPANS = 4

        private val BUDGET = AtomicLong()

        /** True while battery saver holds back read-ahead (the Battery settings): no run is fetched ahead. */
        @Volatile
        var thrifty = false

        private val AHEAD: Executor by lazy {
            Executors.newCachedThreadPool { r -> Thread(r, "share-ahead").apply { isDaemon = true } }
        }

        /** What runs fetched ahead hold now, in all channels; for tests. */
        internal val aheadBytes: Long get() = BUDGET.get()

        /** Opens the file at [path] on its share as a channel. */
        @Throws(IOException::class)
        fun open(path: String): RemoteChannel = RemoteChannel(Remotes.open(path))
    }
}
