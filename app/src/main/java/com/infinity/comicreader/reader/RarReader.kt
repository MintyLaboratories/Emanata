package com.infinity.comicreader.reader

import com.infinity.comicreader.net.RemoteChannel
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.Objects
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** An archive the RAR reader turns down whatever it listed: encrypted, or solid RAR 4. */
internal class RefusedRar(message: String) : IOException(message)

/**
 * Reads a rar/cbr archive, RAR 4 or 5, through libarchive ([LibArchive]) from [channel]: a local
 * file's, or a book's on a share ([remote]); the reader closes it.
 *
 * - libarchive walks headers only forward. In an archive that is not solid, each read opens a
 *   handle of its own on a view in which the main header is followed straight by the entry's
 *   ([Spliced]): one header read whatever the entry, and no unpacking memory held between reads.
 *   A solid archive keeps its handle going forward; an entry at or before the last one read
 *   reopens it and unpacks every entry up to it ([ExtractingReader] reads them in order).
 * - A solid RAR 5 is listed by its headers ([Rar5Headers]): libarchive would unpack it whole to
 *   list it. libarchive cannot read solid RAR 4 past its first entry: such an archive does not open.
 * - Paths use "/" whatever made the archive (RAR 4 stores "\"), as the other formats' do.
 * - A header that cannot be read ends the listing, keeping the entries before it. An archive
 *   without any, or with an encrypted entry, does not open (IOException).
 * - Reads are serialized, first come, first served: a libarchive handle is used by one thread
 *   at a time. Closing never waits: it closes the channel, so a read under way fails, and that
 *   read frees its handle when it ends.
 */
class RarReader private constructor(
    private val channel: SeekableByteChannel,
    private val remote: RemoteChannel?
) : ArchiveReader {
    /** Reads [file]. */
    constructor(file: File) : this(FileChannel.open(file.toPath(), StandardOpenOption.READ), null)

    private val engine: ArchiveEngine

    /** The signature and main header, which [Spliced] views put first; null for a solid archive or an unreadable main header. */
    private val prefix: ByteArray?

    override val solid: Boolean?

    /** False for a solid archive, where each entry unpacks those stored before it. */
    override val randomAccess: Boolean

    override val entries: List<ArchiveEntry>

    /** Each entry's place among the headers libarchive walks (folders and links too). */
    private val ordinals: IntArray

    /** Where each of those headers starts, then the archive's size. */
    private val starts: LongArray

    /** Serializes reads first come, first served: a page for the screen waits for the read in progress, never a run of background reads. */
    private val lock = ReentrantLock(true)
    private var handle = 0L
    private var source: LibArchive.ChannelSource? = null

    /** The header [handle] is on: -1 before the first. */
    private var current = -1

    /** Counts the handles opened, so a stream outliving its handle is told apart. */
    private var generation = 0

    /** False once a [Spliced] view failed: reads walk from the start from then on. */
    private var splicing = true

    @Volatile
    private var closed = false

    /** Reads that went straight to their entry's header ([Spliced]); for tests and checks. */
    @Volatile
    var spliced = 0
        private set

    /** Reads whose [Spliced] view failed, walked from the start instead; for tests and checks. */
    @Volatile
    var unspliced = 0
        private set

    init {
        try {
            engine = LibArchive.engine ?: throw IOException("libarchive not loaded")
            // Each entry's header sits before its data: small fetches while walking them.
            remote?.seekBlock = RemoteChannel.HEADER_BLOCK_BYTES
            val head = readAt(0, RarMain.HEAD_BYTES)
            val main = RarMain.of(head)
            solid = main?.solid
            randomAccess = solid != true
            prefix = main?.takeIf { !it.solid && it.end <= MAX_PREFIX_BYTES }?.let { m ->
                if (m.end <= head.size) head.copyOf(m.end.toInt()) else readAt(0, m.end.toInt()).takeIf { it.size.toLong() == m.end }
            }
            val headers = ArrayList<Rar5Headers.Header>()
            try {
                if (main?.version == 5 && main.solid) Rar5Headers.list(channel, headers) else list(headers)
            } catch (e: IOException) {
                if (e is RefusedRar) throw e
                if (main?.version == 4 && main.solid) throw RefusedRar("Solid RAR 4 cannot be read: ${e.message}")
                if (headers.none { it.file }) throw e
            }
            val files = headers.withIndex().filter { it.value.file }
            entries = files.mapIndexed { i, h -> ArchiveEntry(pathOf(h.value.name), h.value.size, i) }
            ordinals = IntArray(files.size) { files[it].index }
            starts = LongArray(headers.size + 1) { if (it < headers.size) headers[it].start else channel.size() }
            remote?.seekBlock = RemoteChannel.MIN_BLOCK_BYTES
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    /** Every header libarchive walks, into [into]; throws for one it cannot read, [into] holding those before it. */
    private fun list(into: MutableList<Rar5Headers.Header>) {
        channel.position(0)
        val h = engine.open(LibArchive.ChannelSource(channel, HEADER_READ_BYTES))
        try {
            val meta = LongArray(LibArchive.META_COUNT)
            while (true) {
                val name = engine.next(h, meta) ?: break
                if (meta[LibArchive.META_ENCRYPTED] != 0L) throw RefusedRar("Encrypted archive")
                into += Rar5Headers.Header(name, meta[LibArchive.META_SIZE], meta[LibArchive.META_TYPE] == LibArchive.TYPE_FILE, meta[LibArchive.META_POSITION])
            }
        } finally {
            engine.close(h)
        }
    }

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T = try {
        lock.withLock {
            if (closed) throw IOException("Closed")
            val ordinal = ordinals[entry.index]
            // On a share: its header and packed data, about to be read whole.
            remote?.expect(starts[ordinal], starts[ordinal + 1] - starts[ordinal])
            moveTo(ordinal, entry.path)
            source?.chunk = LibArchive.BLOCK_BYTES
            try {
                block(EntryStream(handle, generation))
            } finally {
                // Not solid: the next read opens a handle of its own; this one's memory goes now.
                // Closed meanwhile: close() did not wait for this read, so its handle goes here.
                if (solid == false || closed) drop()
            }
        }
    } finally {
        // close() found the lock taken: whoever lets go of it last after closing frees the handle.
        if (closed) dropUnlessBusy()
    }

    /** Puts the handle on header [ordinal], which holds [path]. */
    private fun moveTo(ordinal: Int, path: String) {
        val p = prefix
        var viewFailed = false
        if (solid == false) {
            drop()
            if (splicing && p != null && starts[ordinal] > p.size) {
                try {
                    open(Spliced(channel, p, starts[ordinal]))
                    current = ordinal - 1
                    step(ordinal, path)
                    spliced++
                    return
                } catch (e: IOException) {
                    if (closed) throw e
                    drop()
                    viewFailed = true
                }
            }
        }
        if (handle == 0L || current >= ordinal) {
            drop()
            open(channel)
            current = -1
        }
        while (current < ordinal) step(ordinal, path)
        // The walk from the start found the entry the view did not: no more views.
        if (viewFailed) {
            splicing = false
            unspliced++
        }
    }

    /** Opens a handle on [view] from its start. */
    private fun open(view: SeekableByteChannel) {
        view.position(0)
        // Headers only while walking an archive that is not solid; a solid one unpacks as it goes.
        val s = LibArchive.ChannelSource(view, if (solid == false) HEADER_READ_BYTES else LibArchive.BLOCK_BYTES)
        handle = engine.open(s)
        source = s
        generation++
    }

    /** Moves the handle to the next header, checking it holds [path] when it is header [ordinal]. */
    private fun step(ordinal: Int, path: String) {
        val meta = LongArray(LibArchive.META_COUNT)
        val name = native { engine.next(handle, meta) } ?: run {
            drop()
            throw IOException("Entry $ordinal missing")
        }
        current++
        if (current == ordinal && pathOf(name) != path) {
            drop()
            throw IOException("Entry $ordinal is not where it was listed")
        }
    }

    /** Runs a native [call], letting go of the handle when it fails: libarchive reads no further after an error. */
    private inline fun <T> native(call: () -> T): T = try {
        call()
    } catch (t: Throwable) {
        drop()
        throw t
    }

    /** Closes the handle; the next read opens one anew. */
    private fun drop() {
        if (handle != 0L) engine.close(handle)
        handle = 0
        source = null
    }

    /** The current entry's data on handle [h], while it is the one opened [opened]th. */
    private inner class EntryStream(private val h: Long, private val opened: Int) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Objects.checkFromIndexSize(off, len, b.size)
            if (len == 0) return 0
            if (handle == 0L || generation != opened) throw IOException("Entry no longer open")
            return native { engine.read(h, b, off, len) }
        }
    }

    private fun readAt(at: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        channel.position(at)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }

    /** Never waits: a read under way fails on the closed channel and frees its handle itself. */
    override fun close() {
        closed = true
        runCatching { channel.close() }
        dropUnlessBusy()
    }

    /** Frees the handle unless a read holds the lock; that read frees it once it lets go. */
    private fun dropUnlessBusy() {
        if (!lock.tryLock()) return
        try {
            drop()
        } finally {
            lock.unlock()
        }
    }

    /** Whether a libarchive handle is open; for tests. */
    internal val holdsHandle: Boolean
        get() = lock.withLock { handle != 0L }

    companion object {
        /** How much libarchive reads at once while walking headers: a few, never a page's worth. */
        private const val HEADER_READ_BYTES = 8 * 1024

        /** The longest main header put before an entry's ([Spliced]); one longer is walked past instead. */
        private const val MAX_PREFIX_BYTES = 64L * 1024

        /** [name] (UTF-8) with "/" as the only separator. */
        private fun pathOf(name: ByteArray): String = String(name, Charsets.UTF_8).replace('\\', '/')

        /** Reads the rar on a share through [channel], in place; the reader closes it. */
        fun inPlace(channel: RemoteChannel): RarReader = RarReader(channel, channel)

        /** Reads the rar [channel] holds; the reader closes it. */
        internal fun of(channel: SeekableByteChannel): RarReader = RarReader(channel, channel as? RemoteChannel)
    }
}

/**
 * [channel] as an archive in which [prefix] (its signature and main header) is followed
 * straight by its bytes from [from] on: to libarchive, the header at [from] comes first.
 * Read only; closing leaves [channel] open.
 */
internal class Spliced(private val channel: SeekableByteChannel, private val prefix: ByteArray, private val from: Long) : SeekableByteChannel {
    private var position = 0L

    override fun read(dst: ByteBuffer): Int {
        if (!dst.hasRemaining()) return 0
        if (position >= size()) return -1
        if (position < prefix.size) {
            val n = minOf(dst.remaining(), prefix.size - position.toInt())
            dst.put(prefix, position.toInt(), n)
            position += n
            return n
        }
        channel.position(from + position - prefix.size)
        val n = channel.read(dst)
        if (n > 0) position += n
        return n
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0) { "Negative position" }
        position = newPosition
        return this
    }

    override fun size(): Long = prefix.size + (channel.size() - from).coerceAtLeast(0)

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = channel.isOpen

    override fun close() = Unit
}
