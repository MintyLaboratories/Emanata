package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.IOException
import java.io.InputStream

/** Image pages of an archive, in natural order of their paths. */
class ArchiveSource(private val reader: ArchiveReader) : PageSource {
    private class Page(val path: String, val entry: ArchiveEntry)

    private val pages: List<Page> = try {
        reader.entries
            .filter { Formats.isImageName(it.path) }
            .map { Page(it.path, it) }
            .sortedWith(compareBy(NaturalOrder) { it.path })
    } catch (t: Throwable) {
        runCatching { reader.close() }
        throw t
    }

    override val pageCount: Int get() = pages.size

    /** Path of each page inside the archive, in page order. */
    val pagePaths: List<String> get() = pages.map { it.path }

    /** Runs [block] on the encoded bytes of page [index]. */
    fun <T> readPage(index: Int, block: (InputStream) -> T): T = reader.read(pages[index].entry, block)

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
        runCatching { reader.close() }
    }

    companion object {
        private const val TAG = "ArchiveSource"
    }
}
