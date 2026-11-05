package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.data.SimilarFinder
import com.infinity.comicreader.data.SimilarStore
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.PageHash
import com.infinity.comicreader.util.SimilarBooks
import com.infinity.comicreader.util.Timing
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Reads what the similar-books search compares of a book ([SimilarStore.Entry]): the
 * hashes of the pages [SimilarBooks.slotPages] samples, each from its kept preview, a
 * quick preview, or a decode sampled down to a few thousand pixels, whichever comes
 * first; and the book's formats and middle page's size.
 */
object BookPrints {
    /** Largest source a page is decoded from for its hash: the decoder's own power-of-two sampling does most of the shrinking. */
    private const val HASH_CAP_PIXELS = 65_536L

    /** The box a page is drawn into for its hash; a PDF's is rendered this small. */
    private const val HASH_BOX = 128

    private val HASH_TARGET = Target(HASH_BOX, HASH_BOX, capPixels = HASH_CAP_PIXELS)

    /**
     * What the search compares of book [file], names read as [charset] says; null when it
     * cannot be opened, has no pages, or [canceled] turned true before every page was read.
     * Kept ([SimilarFinder.Read.keep]) only when each page hashed or will never decode.
     * Blocking.
     */
    fun read(file: File, charset: String, cacheDir: File, canceled: () -> Boolean = { false }): SimilarFinder.Read? =
        Timing.span("book print") { readNow(file, charset, cacheDir, canceled) }

    private fun readNow(file: File, charset: String, cacheDir: File, canceled: () -> Boolean): SimilarFinder.Read? {
        val source = runCatching { PageSources.open(file, charset, cacheDir, canceled, copyNested = false) }.getOrNull() ?: return null
        try {
            val count = source.pageCount
            if (count <= 0) return null
            val hashes = HashMap<Int, PageHash?>()
            var keep = true
            for (index in SimilarBooks.pagesToRead(count)) {
                if (canceled()) return null
                hashes[index] = try {
                    hashOf(source, index)
                } catch (t: Throwable) {
                    if (!isPermanent(t)) keep = false
                    null
                }
            }
            if (!keep && canceled()) return null
            val slots = SimilarBooks.slotPages(count).map { pages -> pages.map { hashes[it] } }
            val middle = SimilarBooks.slotPages(count)[SimilarBooks.SLOTS / 2].first()
            val size = runCatching { source.pageSize(middle) ?: source.loadedSize(middle) }.getOrNull()
            val image = if (source.isVector) "" else source.pageName(middle)?.let { Formats.ext(it) }.orEmpty()
            val entry = SimilarStore.Entry(
                SimilarBooks.Print(count, slots), size?.getOrNull(0) ?: 0, size?.getOrNull(1) ?: 0, Formats.ext(file.name), image
            )
            return SimilarFinder.Read(entry, keep)
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * The hash of page [index] of [source], from the cheapest small picture of it there is.
     * Never recycled: a quick preview may still be on its way to the preview store.
     */
    private fun hashOf(source: PageSource, index: Int): PageHash =
        hashOf(source.keptPreview(index) ?: source.loadPreview(index) ?: source.loadPage(index, HASH_TARGET))

    /** True when [t] says page bytes will never decode; out of memory, read errors and interruptions may pass. */
    internal fun isPermanent(t: Throwable): Boolean = when (t) {
        is DecodeException -> !t.transient
        is IOException, is OutOfMemoryError, is InterruptedException, is CancellationException -> false
        else -> t is Exception
    }

    /** The hash of [bitmap]. */
    fun hashOf(bitmap: Bitmap): PageHash {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return PageHash.of(pixels, w, h)
    }
}
