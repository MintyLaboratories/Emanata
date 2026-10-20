package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.IOException
import java.io.InputStream

/**
 * Image pages of an archive, in natural order of their paths. With [nested], archives
 * inside the archive contribute their pages too, as if the inner archive were a
 * folder: `Book/Issue 2.cbz/01.jpg`. [rootKey] identifies [root] for the nested-archive
 * cache. With [firstPageOnly] only the first page is collected, so a cover never
 * extracts more than the inner archive it comes from.
 */
class ArchiveSource(
    root: ArchiveReader,
    rootKey: String = "",
    private val nested: NestedArchives? = null,
    private val firstPageOnly: Boolean = false
) : PageSource {
    private class Page(val path: String, val reader: ArchiveReader, val entry: ArchiveEntry)

    private val readers = arrayListOf(root)

    private val pages: List<Page> = try {
        ArrayList<Page>().also { collect(root, rootKey, "", 0, it) }
    } catch (t: Throwable) {
        release()
        throw t
    }

    /** Adds the pages of [reader] to [out] in order; true once [firstPageOnly] is satisfied. */
    private fun collect(reader: ArchiveReader, key: String, prefix: String, depth: Int, out: MutableList<Page>): Boolean {
        val canNest = nested != null && depth < NestedArchives.MAX_DEPTH
        // An inner archive sorts by its file name, like the books of a folder.
        val items = reader.entries
            .filter { !ArchiveJunk.isJunk(it.path, it.size) }
            .filter { Formats.isImageName(it.path) || (canNest && Formats.isArchiveName(it.path)) }
            .sortedWith(compareBy(NaturalOrder) { it.path })
        for (entry in items) {
            if (Formats.isImageName(entry.path)) {
                out += Page(prefix + entry.path, reader, entry)
                if (firstPageOnly) return true
                continue
            }
            val inner = nested?.open(reader, key, entry) ?: continue
            readers += inner.reader
            if (collect(inner.reader, inner.key, prefix + entry.path + "/", depth + 1, out)) return true
        }
        return false
    }

    override val pageCount: Int get() = pages.size

    /** Path of each page, inner archives included as folders, in page order. */
    val pagePaths: List<String> get() = pages.map { it.path }

    /** Runs [block] on the encoded bytes of page [index]. */
    fun <T> readPage(index: Int, block: (InputStream) -> T): T {
        val page = pages[index]
        return page.reader.read(page.entry, block)
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
        release()
    }

    private fun release() {
        for (r in readers) runCatching { r.close() }
        nested?.close()
    }

    companion object {
        private const val TAG = "ArchiveSource"
    }
}
