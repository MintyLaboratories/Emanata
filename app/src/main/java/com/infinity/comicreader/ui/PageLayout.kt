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

    private fun pageOrNone(page: Int, pageCount: Int) =
        if (page in 0 until pageCount) page else NONE

    companion object {
        const val NONE = -1
    }
}
