package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.CoverArt
import com.infinity.comicreader.util.Formats
import java.io.File

/** [DirectionDetection] for an open book, the cover included. */
object BookDirection {
    /** True when [file] is a book whose first page may be a wraparound cover; a loose page is not its folder's cover. */
    fun hasCover(file: File): Boolean = !Formats.isImageName(file.name)

    /**
     * What [source], opened from [file], tells without its cover. A single image stands for
     * the folder of [siblings] it is read with, whose names are its pages. A book's [facts],
     * when already read for the library, answer without reading anything; otherwise
     * ComicInfo is read only where that is cheap ([PageSource.comicInfo]). Blocking.
     */
    fun fromContents(
        file: File,
        source: PageSource,
        siblings: List<File> = emptyList(),
        facts: BookFacts? = null
    ): DirectionDetection.Detected? {
        if (!hasCover(file)) return DirectionDetection.fromContents(null, file.parentFile?.name.orEmpty(), siblings.map { it.name })
        if (facts != null) return facts.detected
        val names = (0 until source.pageCount).mapNotNull { source.pageName(it) }
        return DirectionDetection.fromContents(source.comicInfo(cheapOnly = true), file.name, names)
    }

    /**
     * The front half of a wraparound first page; null when it is not one or its barcode does
     * not tell. Blocking: the page is decoded.
     */
    fun coverFront(source: PageSource): Wraparound.Half? {
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
