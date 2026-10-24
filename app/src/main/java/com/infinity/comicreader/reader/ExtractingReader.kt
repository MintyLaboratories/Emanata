package com.infinity.comicreader.reader

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A solid archive ([inner], where each entry unpacks those stored before it) read once,
 * front to back: an entry asked for unpacks every entry from where the last read stopped
 * up to it, and each is kept as a file under [dir], so it is read from there afterwards.
 * An entry the pass went by without keeping (its file since deleted) is unpacked anew
 * from the archive. Entries kept by an earlier reader of the same [dir] count as read.
 * Closing closes [inner], then tells [onClosed]; the kept files stay, for the cache to trim.
 */
class ExtractingReader(
    private val inner: ArchiveReader,
    private val dir: File,
    private val onClosed: () -> Unit = {}
) : ArchiveReader {
    override val entries: List<ArchiveEntry> get() = inner.entries

    /** False: an entry not reached yet still unpacks those before it. */
    override val randomAccess: Boolean get() = false

    /** Entries in the order the archive stores them. */
    private val stored = inner.entries.sortedBy { it.index }

    /** Where in [stored] the pass stopped: entries before it were unpacked. */
    private var next = 0

    /** Serializes the pass first come, first served: a page for the screen waits for the read under way, never a run of background reads. */
    private val lock = ReentrantLock(true)

    /** Entries this reader unpacked into files; for tests. */
    var unpacked = 0
        private set

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T {
        kept(entry)?.let { return it.inputStream().use(block) }
        lock.withLock {
            kept(entry)?.let { return it.inputStream().use(block) }
            val at = stored.indexOfFirst { it.index == entry.index }
            if (at < next) return inner.read(entry, block)
            while (next <= at) {
                val e = stored[next]
                // Kept already by an earlier reader: the pass goes by without writing it again.
                if (kept(e) == null) keep(e) else inner.read(e) { }
                next++
            }
        }
        val file = kept(entry) ?: throw IOException("Could not keep ${entry.path}")
        return file.inputStream().use(block)
    }

    private fun fileOf(entry: ArchiveEntry) = File(dir, entry.index.toString())

    private fun kept(entry: ArchiveEntry): File? = fileOf(entry).takeIf { it.isFile }

    /** Unpacks [entry] into its file, written aside and renamed once whole. */
    private fun keep(entry: ArchiveEntry) {
        dir.mkdirs()
        val target = fileOf(entry)
        val part = File(dir, "${entry.index}.part")
        try {
            inner.read(entry) { input -> part.outputStream().use { input.copyTo(it) } }
            unpacked++
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not keep ${entry.path}")
            }
        } finally {
            part.delete()
        }
    }

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            inner.close()
        } finally {
            onClosed()
        }
    }
}
