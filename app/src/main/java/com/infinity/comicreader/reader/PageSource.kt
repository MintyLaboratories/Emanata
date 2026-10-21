package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.Closeable

/** A random-access sequence of pages that can each be rendered to a [Bitmap]. */
interface PageSource : Closeable {
    val pageCount: Int

    /** True when pages are drawn from vectors at any size, so none is native ([PdfSource]). */
    val isVector: Boolean get() = false

    /** Render page [index] at exactly the size [target] gives for it. */
    fun loadPage(index: Int, target: Target): Bitmap

    /**
     * Intrinsic `[width, height]` of page [index] without decoding its pixels, or
     * null when it cannot be determined cheaply. Used to lay out continuous long
     * strips so pages do not resize as they load.
     */
    fun pageSize(index: Int): IntArray? = null

    /**
     * Page [index] at full detail, laid out as [target] lays it out, holding about
     * [maxBytes] at most: decoded whole at its full size, or reduced to fit.
     */
    fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail =
        BitmapDetail(loadPage(index, target.detail(pageSize(index), maxBytes)))

    /** Name of page [index] as stored (an archive entry's path), for messages; null when it has none. */
    fun pageName(index: Int): String? = null
}
