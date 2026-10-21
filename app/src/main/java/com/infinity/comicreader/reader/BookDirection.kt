package com.infinity.comicreader.reader

import com.infinity.comicreader.data.CoverArt
import com.infinity.comicreader.util.Formats
import java.io.File

/** [DirectionDetection] for an open book, the cover included. */
object BookDirection {
    /**
     * The direction [source], opened from [file], suggests. A single image stands for
     * the folder of [siblings] it is read with, whose names are its pages. Blocking:
     * a book's wraparound first page is decoded to find its barcode.
     */
    fun detect(file: File, source: PageSource, siblings: List<File> = emptyList()): DirectionDetection.Detected? {
        val single = Formats.isImageName(file.name)
        val names = if (single) siblings.map { it.name } else (0 until source.pageCount).mapNotNull { source.pageName(it) }
        val bookName = if (single) file.parentFile?.name.orEmpty() else file.name
        // A loose page is not the folder's cover, so only books look at theirs.
        return DirectionDetection.detect(source.comicInfo(), bookName, names) { if (single) null else coverFront(source) }
    }

    /** The front half of a wraparound first page; null when it is not one or its barcode does not tell. */
    private fun coverFront(source: PageSource): Wraparound.Half? {
        if (source.pageCount == 0 || source.isVector) return null
        val size = source.pageSize(0) ?: return null
        if (!Wraparound.isWraparound(size[0], size[1])) return null
        val page = runCatching { source.loadPage(0, Target(0, Wraparound.SCAN_HEIGHT, Fit.SHRINK)) }.getOrNull() ?: return null
        return try {
            CoverArt.frontOf(page)
        } finally {
            page.recycle()
        }
    }
}
