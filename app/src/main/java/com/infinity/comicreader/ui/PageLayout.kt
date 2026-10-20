package com.infinity.comicreader.ui

/**
 * Maps pager items to pages. A single-page layout shows page i as item i; a spread
 * pairs pages (0, 1), (2, 3), ... and shows an odd last page alone.
 */
class PageLayout(val spread: Boolean, private val rtl: Boolean = false) {

    /** Items needed to show [pageCount] pages. */
    fun itemCount(pageCount: Int): Int =
        if (spread) (pageCount.coerceAtLeast(0) + 1) / 2 else pageCount.coerceAtLeast(0)

    /** The item showing [page]. */
    fun itemOf(page: Int): Int = if (spread) page / 2 else page

    /** The first page, in reading order, that [item] shows. */
    fun firstPage(item: Int): Int = if (spread) item * 2 else item

    /** The last page, in reading order, that [item] shows out of [pageCount]. */
    fun lastPage(item: Int, pageCount: Int): Int =
        minOf(if (spread) item * 2 + 1 else item, pageCount - 1)

    /**
     * The first page of the item [delta] items away from the one showing [page];
     * below 0 or at least the page count when that steps out of the book.
     */
    fun step(page: Int, delta: Int): Int = firstPage(itemOf(page) + delta)

    /** True when [page] has its item to itself: always, or a spread's lone last page. */
    fun shownAlone(page: Int, pageCount: Int): Boolean =
        itemOf(page).let { firstPage(it) == lastPage(it, pageCount) }

    /** True when [page] is on the item that shows the last of [pageCount] pages. */
    fun onLastItem(page: Int, pageCount: Int): Boolean =
        itemOf(page) >= itemOf((pageCount - 1).coerceAtLeast(0))

    /**
     * The page each slot of [item] shows, left to right on screen, or [NONE] for a
     * slot with no page (past the end, or the empty half beside a lone last page).
     */
    fun slots(item: Int, pageCount: Int): IntArray {
        if (!spread) return intArrayOf(pageOrNone(item, pageCount))
        val first = pageOrNone(item * 2, pageCount)
        val second = pageOrNone(item * 2 + 1, pageCount)
        return if (rtl) intArrayOf(second, first) else intArrayOf(first, second)
    }

    /** The slot of its item that [page] shows in, left to right on screen; -1 when out of the book. */
    fun slotOf(page: Int, pageCount: Int): Int = slots(itemOf(page), pageCount).indexOf(page)

    private fun pageOrNone(page: Int, pageCount: Int) =
        if (page in 0 until pageCount) page else NONE

    companion object {
        const val NONE = -1
    }
}
