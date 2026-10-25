package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * Image pages of an archive, in natural order of their paths. With [nested], archives
 * inside the archive contribute their pages too, as if the inner archive were a
 * folder: `Book/Issue 2.cbz/01.jpg`. [rootKey] identifies [root] for the nested-archive
 * cache. Only the first [pageLimit] pages are collected, so a cover never extracts more
 * than the inner archives its page comes from.
 *
 * At most [maxOpenInner] inner archives stay open, least recently read closing first,
 * since each open reader keeps its decoder state; a closed one reopens from its copy
 * when one of its pages is read again.
 */
class ArchiveSource(
    root: ArchiveReader,
    rootKey: String = "",
    private val nested: NestedArchives? = null,
    private val pageLimit: Int = Int.MAX_VALUE,
    private val maxOpenInner: Int = MAX_OPEN_INNER
) : PageSource {
    /**
     * One archive of the book; [copy] is null for the root, which is never closed early.
     * [randomAccess] is its reader's, kept once known, as the reader may close for room.
     */
    private class Slot(val copy: File?, var reader: ArchiveReader?) {
        var users = 0
        var lastUse = 0L
        var randomAccess: Boolean? = null
    }

    /** A page: its [path] shown, and its [key], the places of its entry and the inner archives holding it. */
    private class Page(val path: String, val key: String, val slot: Slot, val entry: ArchiveEntry)

    /** Guards every [Slot]. */
    private val lock = Any()
    private val slots = arrayListOf(Slot(null, root))
    private var clock = 0L
    private var closed = false

    private val pages: List<Page> = try {
        ArrayList<Page>().also { collect(slots[0], rootKey, "", "", 0, it) }
    } catch (t: Throwable) {
        closeAll()
        throw t
    }

    /** Adds the pages of [slot] to [out] in order; true once [pageLimit] pages are in. */
    private fun collect(slot: Slot, key: String, prefix: String, places: String, depth: Int, out: MutableList<Page>): Boolean {
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
                    out += Page(prefix + entry.path, places + entry.index, slot, entry)
                    if (out.size >= pageLimit) return true
                    continue
                }
                val inner = nested?.open(reader, key, entry) ?: continue
                val child = Slot(inner.file, inner.reader)
                synchronized(lock) { slots += child }
                if (collect(child, inner.key, prefix + entry.path + "/", places + entry.index + "/", depth + 1, out)) return true
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
        if (slot.randomAccess == null) slot.randomAccess = reader.randomAccess
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

    /** False when any archive holding pages is solid. */
    override val cheapSizes: Boolean = synchronized(lock) { pages.map { it.slot }.distinct().all { it.randomAccess == true } }

    /** Sizes of the pages loaded so far, by index ([loadedSize]). */
    private val loadedSizes = ConcurrentHashMap<Int, IntArray>()

    override fun loadedSize(index: Int): IntArray? = loadedSizes[index]

    /** Path of each page, inner archives included as folders, in page order. */
    val pagePaths: List<String> get() = pages.map { it.path }

    /**
     * What tells each page apart whatever its name reads as, in page order: the places of its
     * entry and of the inner archives holding it in their archives ("3/17").
     */
    val pageKeys: List<String> get() = pages.map { it.key }

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

    override fun loadPage(index: Int, target: Target): Bitmap {
        if (pages.isEmpty()) throw IOException("Archive contains no images")
        val page = pages[index]
        try {
            // Buffered under the reader's lock, decoded outside it.
            val bytes = readPage(index) { BitmapUtils.readCapped(it) }
            if (!loadedSizes.containsKey(index)) sizeOf(bytes)?.let { loadedSizes[index] = it }
            return Decoders.decode(bytes, target)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load archive entry ${page.path} (index $index)", t)
            throw t
        }
    }

    /** Which pages are worth reading for a quick preview. */
    private val quick = QuickPreviews()

    /**
     * Reads page [index] only when its name allows a quick preview ([QuickPreviews]),
     * and only past its header when its format has one ([Decoders.preview]).
     */
    override fun loadPreview(index: Int): Bitmap? {
        val path = pages.getOrNull(index)?.path ?: return null
        if (!quick.worthReading(path)) return null
        val bytes = readPage(index) { Decoders.quickBytes(it) }
        quick.found(path, bytes != null)
        return bytes?.let { Decoders.preview(it) }
    }

    /** Read only for a page named as AVIF, the one format with layers that decode on their own. */
    override fun loadFirstLayer(index: Int): Bitmap? {
        val path = pages.getOrNull(index)?.path ?: return null
        if (!Formats.ext(path).equals("avif", ignoreCase = true)) return null
        return readPage(index) { Decoders.firstLayer(it) }
    }

    /** From the entry's header when it tells, else from the whole entry. */
    override fun pageSize(index: Int): IntArray? {
        if (index !in pages.indices) return null
        runCatching { readPage(index) { Decoders.headerSize(it) } }.getOrNull()?.let { return it }
        return runCatching {
            Decoders.size(readPage(index) { BitmapUtils.readCapped(it) })
        }.getOrNull()
    }

    /** [bytes]' image size from its header, else as the platform reads it. */
    private fun sizeOf(bytes: ByteArray): IntArray? = runCatching {
        Decoders.headerSize(ByteArrayInputStream(bytes)) ?: Decoders.size(bytes)
    }.getOrNull()

    override fun edge(index: Int, bottom: Boolean): Seams.Profile? {
        if (index !in pages.indices) return null
        return runCatching { Decoders.edge(readPage(index) { BitmapUtils.readCapped(it) }, bottom) }.getOrNull()
    }

    override fun sides(index: Int): SpreadSeams.Sides? {
        if (index !in pages.indices) return null
        return runCatching { Decoders.sides(readPage(index) { BitmapUtils.readCapped(it) }) }.getOrNull()
    }

    override fun pageName(index: Int): String? = pages.getOrNull(index)?.path

    /**
     * The outer archive's ComicInfo.xml; inner archives' own are not read. In a solid
     * archive, [cheapOnly] reads it only when it is stored before the first page.
     */
    override fun comicInfo(cheapOnly: Boolean): ComicInfo? = withRoot { reader ->
        ComicInfo.read(reader, before = if (cheapOnly) cheapBefore(reader) else Int.MAX_VALUE)
    }

    override val nameCharset: Charset? = slots[0].reader?.nameCharset

    override fun comicInfoCostly(): Boolean = withRoot { reader ->
        val entry = ComicInfo.entryOf(reader)
        entry != null && entry.index >= cheapBefore(reader)
    }

    /**
     * The entry index a root entry must come before to be read without unpacking pages
     * the first one does not need: any, unless the root is solid.
     */
    private fun cheapBefore(reader: ArchiveReader): Int =
        if (reader.randomAccess) Int.MAX_VALUE else pages.firstOrNull()?.takeIf { it.slot === slots[0] }?.entry?.index ?: 0

    private inline fun <T> withRoot(block: (ArchiveReader) -> T): T {
        val root = slots[0]
        val reader = acquire(root)
        try {
            return block(reader)
        } finally {
            release(root)
        }
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

        /** Enough for a spread and its neighbors across an issue boundary. */
        const val MAX_OPEN_INNER = 4
    }
}
