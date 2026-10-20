package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Image pages of an archive, in natural order of their paths. With [nested], archives
 * inside the archive contribute their pages too, as if the inner archive were a
 * folder: `Book/Issue 2.cbz/01.jpg`. [rootKey] identifies [root] for the nested-archive
 * cache. With [firstPageOnly] only the first page is collected, so a cover never
 * extracts more than the inner archive it comes from.
 *
 * At most [maxOpenInner] inner archives stay open, least recently read closing first,
 * since each open reader keeps its decoder state; a closed one reopens from its copy
 * when one of its pages is read again.
 */
class ArchiveSource(
    root: ArchiveReader,
    rootKey: String = "",
    private val nested: NestedArchives? = null,
    private val firstPageOnly: Boolean = false,
    private val maxOpenInner: Int = MAX_OPEN_INNER
) : PageSource {
    /** One archive of the book; [copy] is null for the root, which is never closed early. */
    private class Slot(val copy: File?, var reader: ArchiveReader?) {
        var users = 0
        var lastUse = 0L
    }

    private class Page(val path: String, val slot: Slot, val entry: ArchiveEntry)

    /** Guards every [Slot]. */
    private val lock = Any()
    private val slots = arrayListOf(Slot(null, root))
    private var clock = 0L
    private var closed = false

    private val pages: List<Page> = try {
        ArrayList<Page>().also { collect(slots[0], rootKey, "", 0, it) }
    } catch (t: Throwable) {
        closeAll()
        throw t
    }

    /** Adds the pages of [slot] to [out] in order; true once [firstPageOnly] is satisfied. */
    private fun collect(slot: Slot, key: String, prefix: String, depth: Int, out: MutableList<Page>): Boolean {
        val reader = acquire(slot)
        try {
            val canNest = nested != null && depth < NestedArchives.MAX_DEPTH
            // An inner archive sorts by its file name, like the books of a folder.
            val items = reader.entries
                .filter { !ArchiveJunk.isJunk(it.path, it.size) }
                .filter { Formats.isImageName(it.path) || (canNest && Formats.isArchiveName(it.path)) }
                .sortedWith(compareBy(NaturalOrder) { it.path })
            for (entry in items) {
                if (Formats.isImageName(entry.path)) {
                    out += Page(prefix + entry.path, slot, entry)
                    if (firstPageOnly) return true
                    continue
                }
                val inner = nested?.open(reader, key, entry) ?: continue
                val child = Slot(inner.file, inner.reader)
                synchronized(lock) { slots += child }
                if (collect(child, inner.key, prefix + entry.path + "/", depth + 1, out)) return true
            }
            return false
        } finally {
            release(slot)
        }
    }

    /** Opens [slot] if it was closed for room and marks it in use until [release]. */
    private fun acquire(slot: Slot): ArchiveReader = synchronized(lock) {
        if (closed) throw IOException("Source closed")
        val reader = slot.reader ?: reopen(slot)
        slot.users++
        slot.lastUse = ++clock
        evict()
        reader
    }

    private fun reopen(slot: Slot): ArchiveReader {
        val copy = slot.copy ?: throw IOException("Archive closed")
        val reader = nested?.reopen(copy) ?: throw IOException("Could not reopen ${copy.name}")
        slot.reader = reader
        return reader
    }

    private fun release(slot: Slot) {
        synchronized(lock) {
            slot.users--
            evict()
        }
    }

    /** Closes idle inner archives, least recently read first, while more than [maxOpenInner] are open. */
    private fun evict() {
        var open = slots.count { it.copy != null && it.reader != null }
        while (open > maxOpenInner) {
            val idle = slots.filter { it.copy != null && it.reader != null && it.users == 0 }
                .minByOrNull { it.lastUse } ?: return
            runCatching { idle.reader?.close() }
            idle.reader = null
            open--
        }
    }

    override val pageCount: Int get() = pages.size

    /** Path of each page, inner archives included as folders, in page order. */
    val pagePaths: List<String> get() = pages.map { it.path }

    /** Inner archives currently open; for tests. */
    internal val openInnerCount: Int get() = synchronized(lock) { slots.count { it.copy != null && it.reader != null } }

    /** Runs [block] on the encoded bytes of page [index]. */
    fun <T> readPage(index: Int, block: (InputStream) -> T): T {
        val page = pages[index]
        val reader = acquire(page.slot)
        try {
            return reader.read(page.entry, block)
        } finally {
            release(page.slot)
        }
    }

    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (pages.isEmpty()) throw IOException("Archive contains no images")
        val page = pages[index]
        try {
            // Buffered under the reader's lock, decoded outside it.
            val bytes = readPage(index) { BitmapUtils.readCapped(it) }
            return BitmapUtils.decodeBytes(bytes, reqWidth, reqHeight)
                ?: throw IOException("Could not decode ${page.path}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load archive entry ${page.path} (index $index)", t)
            throw t
        }
    }

    override fun pageSize(index: Int): IntArray? {
        if (index !in pages.indices) return null
        return runCatching {
            BitmapUtils.boundsBytes(readPage(index) { BitmapUtils.readCapped(it) })
        }.getOrNull()
    }

    override fun close() {
        closeAll()
    }

    private fun closeAll() {
        synchronized(lock) {
            closed = true
            for (s in slots) {
                runCatching { s.reader?.close() }
                s.reader = null
            }
        }
        nested?.close()
    }

    companion object {
        private const val TAG = "ArchiveSource"

        /** Enough for a spread and its neighbours across an issue boundary. */
        const val MAX_OPEN_INNER = 4
    }
}
