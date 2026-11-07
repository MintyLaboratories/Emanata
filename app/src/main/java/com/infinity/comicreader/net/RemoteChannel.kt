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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A read-only channel over a file on a share, for archive readers that seek (zip,
 * 7z, tar, rar) and for streams. Reads go through runs fetched from [seekBlock]-aligned
 * offsets: a run after a seek is [seekBlock] long, so walking an archive's headers
 * fetches little around each; each run that follows on from the last fetched doubles, up
 * to [maxBlock], so a page streamed in small pieces costs few round trips. A span the
 * reader is about to read whole ([expect]) is fetched in runs of up to [maxBlock] from
 * its first read on, or of [sharedBlock] while another span is read at the same time, so
 * one page never waits long behind another: calls are serialized. Each run is asked for
 * at once ([RemoteInput.readFully]). While a stream reads a run of at least [aheadFrom]
 * bytes that followed on from the last, or one inside an expected span, or one ending a
 * span read right after another span was read up to it, the next two are fetched in the background
 * ([readAhead]; as [RemoteInput.background] work, waited on as the reader's own request),
 * within a budget all channels share, only while a quarter of the heap stays free after
 * them, never while [thrifty], and on a metered connection only inside a span or when the whole file is read
 * ([toEnd]). The most recently used runs are kept, up to
 * [maxCached] bytes, and only the latest once memory runs low ([trim]); the array of a
 * run let go is reused for the next of its length, so a long streamed read allocates little.
 */
class RemoteChannel(
    private val input: RemoteInput,
    minBlock: Int = MIN_BLOCK_BYTES,
    private val maxBlock: Int = MAX_BLOCK_BYTES,
    private val maxCached: Long = MAX_CACHED_BYTES,
    private val readAhead: Boolean = true,
    private val aheadFrom: Int = AHEAD_FROM_BYTES,
    private val background: Executor = AHEAD,
    private val sharedBlock: Int = SHARED_BLOCK_BYTES,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** True when the file is read front to back to its end (a whole-file fetch or copy): what comes ahead is read anyway. */
    private val toEnd: Boolean = false,
    /** False when a few entries are read apart (pages sampled for a hash): nothing comes ahead past an entry's end. */
    private val entriesAhead: Boolean = true
) : SeekableByteChannel {
    private var position = 0L

    @Volatile
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

    /** A span about to be read whole ([expect]), with how far reads got into it, and when. */
    private class Span(val start: Long, val end: Long) {
        var readTo = start
        var readAt = Long.MIN_VALUE

        fun holds(at: Long) = at >= start && at < end

        /** True while a stream is part way through it, read from within [SHARED_HOLD_MS] before [now]. */
        fun underWay(now: Long) = readTo > start && readTo < end && now - readAt < SHARED_HOLD_MS
    }

    /** A fetched run of the file, from [start]. */
    private class Run(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    /**
     * A run being fetched in the background, [length] bytes from [start], counted in [BUDGET]
     * until let go; one let go unread is [dropped], and its fetch stops asking for more.
     */
    private class Ahead(val start: Long, val length: Int) {
        lateinit var task: FutureTask<ByteArray>

        @Volatile
        var dropped = false

        fun holds(at: Long) = at >= start && at < start + length
    }

    /** Kept runs, least recently used first. */
    private val runs = ArrayList<Run>()
    private var cachedBytes = 0L

    /** Where the last fetched run ended, and how long a run that follows on from it is. */
    private var lastEnd = -1L
    private var window = minBlock

    /** Spans about to be read whole ([expect]), the latest last. */
    private val spans = ArrayDeque<Span>()

    /** True during a read while a span other than its own is under way: another stream shares the channel. */
    private var shared = false

    /** Runs being fetched ahead, in file order, each from where the one before ends. */
    private val coming = ArrayDeque<Ahead>()

    /** The array of the run last let go, for the next run of its length. */
    private var spare: ByteArray? = null

    /** The [TRIMS] this channel has heard of. */
    private var trims = TRIMS.get()

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
        shedOnTrim()
        if (!dst.hasRemaining()) return 0
        if (position >= size) return -1
        val now = clock()
        val span = spanAt(position)
        shared = spans.any { it !== span && it.underWay(now) }
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
        if (span != null && position <= span.end) {
            span.readTo = maxOf(span.readTo, position)
            span.readAt = now
        }
        return if (total == 0) -1 else total
    }

    /**
     * Keeps [bytes], already read at [start] (both ends read for a Book ID, [FileEnds]), as a
     * run: reads within them ask the share for nothing. The channel owns them from then on.
     */
    @Synchronized
    fun seed(start: Long, bytes: ByteArray) {
        if (bytes.isEmpty() || start < 0 || start + bytes.size > size) return
        keep(Run(start, bytes))
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
        spans.removeAll { it.start == start }
        spans.addLast(Span(start, minOf(size, start + length)))
        while (spans.size > MAX_SPANS) spans.removeFirst()
    }

    /** The latest expected span holding [at]; null when none does. */
    private fun spanAt(at: Long): Span? = spans.lastOrNull { it.holds(at) }

    /** The longest run inside a span now: [sharedBlock] while another stream shares the channel. */
    private val spanBlock: Int get() = if (shared) minOf(sharedBlock, maxBlock) else maxBlock

    /** True when an earlier span was read up to just before [span], at most an entry's header between: entries read in a row. */
    private fun followsRead(span: Span): Boolean = spans.any {
        it !== span && it.readTo > it.start && it.readTo <= span.start && span.start - it.readTo <= ENTRY_GAP_BYTES
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
        repeat(i) { drop(coming.first(), unread = true) }
        val a = coming.first()
        drop(a)
        val bytes = runCatching { Timing.span("share read ahead wait") { input.awaiting { a.task.get() } } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        lastEnd = a.start + bytes.size
        return Run(a.start, bytes)
    }

    /** Lets go of [a]; one [unread] is canceled, its fetch asking for nothing more. */
    private fun drop(a: Ahead, unread: Boolean = false) {
        if (!coming.remove(a)) return
        BUDGET.addAndGet(-a.length.toLong())
        if (unread && !a.task.isDone) {
            a.dropped = true
            a.task.cancel(false)
        }
    }

    private fun fetch(at: Long, wanted: Int): Run {
        val spanEnd = spanAt(at)?.end ?: -1
        val start: Long
        val length: Int
        if (spanEnd > at) {
            // Inside a span about to be read whole: as much of it as a run takes.
            start = at
            length = minOf(spanEnd - at, maxOf(spanBlock, wanted).toLong()).toInt()
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

    /** Fills [bytes] with up to [length] bytes at [start] from [from] while [wanted]; the count read. */
    private fun fill(start: Long, bytes: ByteArray, length: Int, from: RemoteInput = input, wanted: () -> Boolean = { true }): Int {
        var n = 0
        while (n < length && wanted()) {
            val r = from.readFully(start + n, bytes, n, length - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** An array of [length] bytes: the spare one when it fits. */
    private fun arrayOf(length: Int): ByteArray =
        spare?.takeIf { it.size == length }?.also { spare = null } ?: ByteArray(length).also { allocations++ }

    /** Lets go of the kept runs but the latest, and of the spare array, once [trim] was called since the last read. */
    private fun shedOnTrim() {
        val now = TRIMS.get()
        if (now == trims) return
        trims = now
        while (runs.size > 1) cachedBytes -= runs.removeAt(0).bytes.size
        spare = null
    }

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
     * stream reads [run]: inside an expected span with more of it after; at a span's end
     * when an earlier span was read up to just before it, as entries read in a row are;
     * or, outside any span, once [run], at least [aheadFrom] long, followed on from the run
     * before. Two, so the next fetch is under way while the last one's final pieces come.
     * Within [MAX_AHEAD_BYTES] for all channels, never while [thrifty]; on a metered
     * connection ([Metered]) only where the bytes are read anyway: inside a span, or [toEnd].
     */
    private fun fetchAhead(run: Run) {
        if (!readAhead || thrifty || run.end >= size || run.end != lastEnd) return
        // Runs coming from elsewhere are let be until done; none are added after them.
        if (coming.isNotEmpty() && coming.first().start != run.end) return
        val span = spanAt(run.start)
        val spanEnd = span?.end ?: -1
        val inSpan = spanEnd > run.end
        // Past a span's end more comes only for entries read in a row: one page read whole (a cover) wants no more.
        val inRow = entriesAhead && span != null && !inSpan && !shared && followsRead(span)
        if (!inSpan && !inRow && (span != null || run.bytes.size < aheadFrom || window < aheadFrom)) return
        if (!inSpan && !toEnd && Metered.skipsOptional()) return
        val limit = if (inSpan) spanEnd else size
        var next = coming.lastOrNull()?.let { it.start + it.length } ?: run.end
        while (coming.size < MAX_COMING && next < limit) {
            val grown = if (inSpan) spanBlock.toLong() else minOf(window.toLong() * 2, maxBlock.toLong())
            val length = minOf(grown, limit - next).toInt()
            if (heapRoom() - length < heapMax() / 4) return
            if (BUDGET.addAndGet(length.toLong()) > MAX_AHEAD_BYTES) {
                BUDGET.addAndGet(-length.toLong())
                return
            }
            val start = next
            val bytes = arrayOf(length)
            val a = Ahead(start, length)
            val task = FutureTask {
                val n = Timing.span("share read ahead ${length / 1024} KB") { fill(start, bytes, length, input.background) { open && !a.dropped } }
                if (n == length) bytes else bytes.copyOf(n)
            }
            a.task = task
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

    /** Closes the file; runs being fetched ahead are canceled, never waited for, and ask for nothing more. */
    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        coming.toList().forEach { drop(it, unread = true) }
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

        /** The longest run inside a span while another stream shares the channel: a page waits behind no more of another's. */
        const val SHARED_BLOCK_BYTES = 1 shl 20

        /** How recently another span must have been read for its stream to count as sharing the channel. */
        private const val SHARED_HOLD_MS = 2_000L

        /** What may lie between entries read in a row: a local header with its name and extra fields. */
        private const val ENTRY_GAP_BYTES = 64 * 1024

        /** How long a stream's runs must have grown before the next is fetched ahead: header walks never get there. */
        const val AHEAD_FROM_BYTES = 1 shl 20

        /** What all channels together may have fetched ahead and not read yet. */
        const val MAX_AHEAD_BYTES = 64L shl 20

        /** How many runs a stream has coming ahead at most. */
        private const val MAX_COMING = 2

        /** Spans [expect] keeps. */
        private const val MAX_SPANS = 4

        private val BUDGET = AtomicLong()

        /** Counts [trim] calls; each channel sheds its kept runs at its next read after one. */
        private val TRIMS = AtomicInteger()

        /** Memory runs low: every channel keeps only its latest run from its next read on. */
        fun trim() {
            TRIMS.incrementAndGet()
        }

        /** The heap free now, and its most; runs come ahead only while a quarter of it stays free. Replaced in tests. */
        internal var heapRoom: () -> Long = { Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) } }
        internal var heapMax: () -> Long = { Runtime.getRuntime().maxMemory() }

        /** True while battery saver holds back read-ahead (the Battery settings): no run is fetched ahead. */
        @Volatile
        var thrifty = false

        private val AHEAD: Executor by lazy {
            Executors.newCachedThreadPool { r -> Thread(r, "share-ahead").apply { isDaemon = true } }
        }

        /** What runs fetched ahead hold now, in all channels; for tests. */
        internal val aheadBytes: Long get() = BUDGET.get()

        /**
         * Opens the file at [path] on its share as a channel, starting from its ends when just
         * read for its Book ID ([FileEnds]) and its time is known to be the same; [entriesAhead]
         * as the channel's.
         */
        @Throws(IOException::class)
        fun open(path: String, entriesAhead: Boolean = true): RemoteChannel = RemoteChannel(Remotes.open(path), entriesAhead = entriesAhead).also { channel ->
            val modified = Remotes.cachedStat(path)?.modified ?: return@also
            for ((start, bytes) in FileEnds.of(path, channel.size, modified)) channel.seed(start, bytes)
        }
    }
}
