package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.nio.charset.Charset

/**
 * Pages a reader left out of a book by hand ("Hide this page"), kept by their index in
 * the book. Everything else counts pages as shown: with hidden pages left out, numbered
 * from 0 without gaps. These turn one count into the other.
 */
object HiddenPages {
    /** The book index of shown page [shown] while [hidden] are left out. */
    fun toBook(shown: Int, hidden: Set<Int>): Int {
        var page = shown
        for (h in hidden.filter { it >= 0 }.sorted()) {
            if (h <= page) page++ else break
        }
        return page
    }

    /** The shown index of book page [page] while [hidden] are left out; a hidden page's is the next shown page's. */
    fun toShown(page: Int, hidden: Set<Int>): Int = page - hidden.count { it in 0 until page }

    /** Shown index [shown] while [old] were hidden, as it reads once [new] are. */
    fun remap(shown: Int, old: Set<Int>, new: Set<Int>): Int = toShown(toBook(shown, old), new)

    /** The book indices shown of [count] pages; all of them when [hidden] would leave none. */
    fun shown(count: Int, hidden: Set<Int>): IntArray {
        val kept = (0 until count).filterNot { it in hidden }
        return if (kept.isEmpty()) IntArray(count) { it } else kept.toIntArray()
    }

    /** [source] with [hidden] left out, or [source] itself when none of them is in it. */
    fun wrap(source: PageSource, hidden: Set<Int>): PageSource {
        val count = source.pageCount
        if (hidden.none { it in 0 until count }) return source
        val shown = shown(count, hidden)
        return if (shown.size == count) source else HidingSource(source, shown)
    }

    /** The book index of [source]'s page [index]: through a [HidingSource], else [index] itself. */
    fun bookIndex(source: PageSource?, index: Int): Int = (source as? HidingSource)?.bookIndex(index) ?: index

    /**
     * What the seams across hidden pages of book [bookId] are kept by ([StoredPages]):
     * [bookId] itself unless pages are hidden, so stored values never land on other pages.
     */
    fun pagesId(bookId: String, source: PageSource): String =
        (source as? HidingSource)?.let { bookId + "\u0000shown:" + it.shownKey } ?: bookId

    /** The book's page count behind [source]. */
    fun bookCount(source: PageSource): Int = (source as? HidingSource)?.inner?.pageCount ?: source.pageCount
}

/**
 * [inner] showing only its pages [shown] (book indices, ascending): page `i` here is
 * page `shown[i]` there. Closing it closes [inner].
 */
class HidingSource(val inner: PageSource, private val shown: IntArray) : PageSource {

    /** The book index of page [index]. */
    fun bookIndex(index: Int): Int = shown[index]

    /** The book indices shown, as text. */
    val shownKey: String get() = shown.joinToString(",")

    override val pageCount: Int get() = shown.size

    override val isVector: Boolean get() = inner.isVector

    override val cheapSizes: Boolean get() = inner.cheapSizes

    override fun loadPage(index: Int, target: Target): Bitmap = inner.loadPage(shown[index], target)

    override fun pageSize(index: Int): IntArray? = shown.getOrNull(index)?.let { inner.pageSize(it) }

    override fun loadedSize(index: Int): IntArray? = shown.getOrNull(index)?.let { inner.loadedSize(it) }

    override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail = inner.loadDetail(shown[index], target, maxBytes)

    override fun loadPreview(index: Int): Bitmap? = shown.getOrNull(index)?.let { inner.loadPreview(it) }

    override fun keptPreview(index: Int): Bitmap? = shown.getOrNull(index)?.let { inner.keptPreview(it) }

    override fun decodedSides(index: Int): SpreadSeams.Sides? = shown.getOrNull(index)?.let { inner.decodedSides(it) }

    override fun loadFirstLayer(index: Int): Bitmap? = shown.getOrNull(index)?.let { inner.loadFirstLayer(it) }

    override fun edge(index: Int, bottom: Boolean): Seams.Profile? = shown.getOrNull(index)?.let { inner.edge(it, bottom) }

    override fun sides(index: Int): SpreadSeams.Sides? = shown.getOrNull(index)?.let { inner.sides(it) }

    override fun pageName(index: Int): String? = shown.getOrNull(index)?.let { inner.pageName(it) }

    override fun comicInfo(cheapOnly: Boolean): ComicInfo? = inner.comicInfo(cheapOnly)

    override fun comicInfoCostly(): Boolean = inner.comicInfoCostly()

    override val nameCharset: Charset? get() = inner.nameCharset

    override fun close() = inner.close()
}
