package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.Closeable
import java.nio.charset.Charset

/** A random-access sequence of pages that can each be rendered to a [Bitmap]. */
interface PageSource : Closeable {
    val pageCount: Int

    /** True when pages are drawn from vectors at any size, so none is native ([PdfSource]). */
    val isVector: Boolean get() = false

    /** What identifies the book's contents ([PreviewStore.bookId]), to key what is kept of its pages; null when unknown. */
    val bookKey: String? get() = null

    /** Render page [index] at exactly the size [target] gives for it. */
    fun loadPage(index: Int, target: Target): Bitmap

    /**
     * Intrinsic `[width, height]` of page [index] without decoding its pixels, or
     * null when it cannot be determined cheaply. Used to lay out continuous long
     * strips so pages do not resize as they load.
     */
    fun pageSize(index: Int): IntArray? = null

    /**
     * True when [pageSize] of every page in turn is cheap enough to ask for all of them:
     * false where reading one entry unpacks those before it (a solid archive).
     */
    val cheapSizes: Boolean get() = true

    /** True when the book is a solid archive, as its reader told on opening; false when it is known not to be; null when unknown or no archive. */
    val solid: Boolean? get() = null

    /**
     * True once a page read for its [sides] alone turned out to decode whole for them (a
     * progressive JPEG: any strip of it decodes every scan), so reading more costs a decode each.
     */
    val dearSides: Boolean get() = false

    /**
     * `[width, height]` of page [index] as found when it was last loaded ([loadPage]),
     * or null: how a source whose sizes are not [cheapSizes] still makes them known.
     */
    fun loadedSize(index: Int): IntArray? = null

    /**
     * Page [index] at full detail, laid out as [target] lays it out, holding about
     * [maxBytes] at most: decoded whole at its full size, or reduced to fit.
     */
    fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail =
        BitmapDetail(loadPage(index, target.detail(pageSize(index), maxBytes)))

    /**
     * A quick preview of page [index] fitting [Previews.BOX] ([Previews.TARGET]), when its
     * format decodes one much faster than the whole page; null otherwise. Blocking.
     */
    fun loadPreview(index: Int): Bitmap? = null

    /** The sides of page [index] as its decode for the reader found them ([sides]), without a read; null until it is decoded. */
    fun decodedSides(index: Int): SpreadSeams.Sides? = null

    /** Page [index]'s small preview as kept from an earlier read ([PreviewingSource]), without reading the book; null when none is. Blocking. */
    fun keptPreview(index: Int): Bitmap? = null

    /**
     * The first layer of page [index] at its own size, when the page is a layered image
     * whose first layer decodes on its own, from the start of the page alone (a layered
     * AVIF, [Decoders.firstLayer]); null otherwise. Blocking.
     */
    fun loadFirstLayer(index: Int): Bitmap? = null

    /**
     * The [Seams.Profile] of page [index]'s [bottom] or top edge, decoded as a thin strip
     * where its format allows ([Decoders.edge]); null when it cannot be read. Blocking.
     */
    fun edge(index: Int, bottom: Boolean): Seams.Profile? = null

    /**
     * The left and right [SpreadSeams.Sides] of page [index], decoded as thin strips where
     * its format allows ([Decoders.sides]); null when it cannot be read. Blocking.
     */
    fun sides(index: Int): SpreadSeams.Sides? = null

    /** Name of page [index] as stored (an archive entry's path), for messages; null when it has none. */
    fun pageName(index: Int): String? = null

    /**
     * The book's ComicInfo.xml; null when it has none. Blocking. With [cheapOnly], also
     * null when reading it would first unpack pages the first one does not need.
     */
    fun comicInfo(cheapOnly: Boolean = false): ComicInfo? = null

    /** True when the book has a ComicInfo.xml that [comicInfo] with `cheapOnly` skips. */
    fun comicInfoCostly(): Boolean = false

    /** The charset the book's file names were read in ([ArchiveReader.nameCharset]); null where none applies. */
    val nameCharset: Charset? get() = null
}
