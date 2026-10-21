package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Half

/**
 * Maps pager items to pages. Without [views], a single-page layout shows page i as
 * item i and a spread pairs pages (0, 1), (2, 3), ... with an odd last page alone.
 * With [views], item i shows `views[i]`: one or two parts, each a page or one half of
 * one, in reading order, as the spread engine laid them out; a page split in halves
 * spans two items. A [spread] layout's items are two-slot rows; [rtl] puts the first
 * part of a pair on the right.
 */
class PageLayout(val spread: Boolean, private val rtl: Boolean = false, private val views: List<List<Part>>? = null) {

    /** What one slot shows: [page], whole or one [half] of it. */
    data class Part(val page: Int, val half: Half = Half.WHOLE)

    /** True when [other] maps items to the same parts on screen. */
    fun sameAs(other: PageLayout): Boolean = spread == other.spread && rtl == other.rtl && views == other.views

    /** True when items are laid out from [views] (or as pairs), so chapters cannot be laid end to end. */
    val grouped: Boolean get() = spread || views != null

    /** The first item showing each page, for [views]. */
    private val itemOfPage: IntArray? = views?.let { v ->
        val pages = v.maxOfOrNull { parts -> parts.maxOfOrNull { it.page } ?: -1 }?.plus(1) ?: 0
        IntArray(pages) { -1 }.also { map ->
            for ((item, parts) in v.withIndex()) for (p in parts) if (map[p.page] < 0) map[p.page] = item
        }
    }

    /** The item showing each part, for [views]. */
    private val itemOfPart: Map<Part, Int>? = views?.let { v ->
        HashMap<Part, Int>().also { map -> for ((item, parts) in v.withIndex()) for (p in parts) map.putIfAbsent(p, item) }
    }

    /** The item showing [part]; for a part no view holds, the first item showing its page. */
    fun itemOf(part: Part): Int = itemOfPart?.get(part) ?: itemOf(part.page)

    /** Items needed to show [pageCount] pages. */
    fun itemCount(pageCount: Int): Int = when {
        views != null -> views.size
        spread -> (pageCount.coerceAtLeast(0) + 1) / 2
        else -> pageCount.coerceAtLeast(0)
    }

    /** The first item showing [page]. */
    fun itemOf(page: Int): Int {
        val map = itemOfPage ?: return if (spread) page / 2 else page
        return when {
            page < 0 -> -1
            page >= map.size -> views!!.size + (page - map.size)
            else -> map[page]
        }
    }

    /** The first page, in reading order, that [item] shows; outside the views, the page it would be. */
    fun firstPage(item: Int): Int {
        val v = views ?: return if (spread) item * 2 else item
        return when {
            item < 0 -> -1
            item >= v.size -> (itemOfPage?.size ?: 0) + (item - v.size)
            else -> v[item].first().page
        }
    }

    /** The last page, in reading order, that [item] shows out of [pageCount]. */
    fun lastPage(item: Int, pageCount: Int): Int {
        val v = views ?: return minOf(if (spread) item * 2 + 1 else item, pageCount - 1)
        return v.getOrNull(item)?.last()?.page ?: minOf(firstPage(item), pageCount - 1)
    }

    /**
     * The first page of the item [delta] items away from the one showing [page];
     * below 0 or at least the page count when that steps out of the book.
     */
    fun step(page: Int, delta: Int): Int = firstPage(itemOf(page) + delta)

    /** True when [item] holds a single part. */
    fun alone(item: Int, pageCount: Int): Boolean = slots(item, pageCount).count { it != NONE } <= 1

    /** True when [page] has its item to itself: always, or a spread's lone page. */
    fun shownAlone(page: Int, pageCount: Int): Boolean = alone(itemOf(page), pageCount)

    /** True when [page] is on the item that shows the last of [pageCount] pages. */
    fun onLastItem(page: Int, pageCount: Int): Boolean =
        itemOf(page) >= itemOf((pageCount - 1).coerceAtLeast(0))

    /** True when [item] is the last item of [pageCount] pages, or past it. */
    fun isLastItem(item: Int, pageCount: Int): Boolean = item >= itemCount(pageCount) - 1

    /**
     * The page each slot of [item] shows, left to right on screen, or [NONE] for a
     * slot with no page (past the end, or the empty half beside a lone page).
     */
    fun slots(item: Int, pageCount: Int): IntArray = parts(item, pageCount).let { p -> IntArray(p.size) { p[it]?.page ?: NONE } }

    /** The part each slot of [item] shows, left to right on screen, as [slots]; null for an empty slot. */
    fun parts(item: Int, pageCount: Int): Array<Part?> {
        val v = views
        if (v != null) {
            val parts = v.getOrNull(item)?.filter { it.page in 0 until pageCount }.orEmpty()
            val ordered = if (rtl && spread) parts.reversed() else parts
            return if (spread) {
                if (ordered.size >= 2) arrayOf(ordered[0], ordered[1]) else arrayOf(ordered.firstOrNull(), null)
            } else {
                arrayOf(ordered.firstOrNull())
            }
        }
        if (!spread) return arrayOf(partOrNone(item, pageCount))
        val first = partOrNone(item * 2, pageCount)
        val second = partOrNone(item * 2 + 1, pageCount)
        return if (rtl) arrayOf(second, first) else arrayOf(first, second)
    }

    /** The slot of its item that [page] shows in, left to right on screen; -1 when out of the book. */
    fun slotOf(page: Int, pageCount: Int): Int = slots(itemOf(page), pageCount).indexOf(page)

    private fun partOrNone(page: Int, pageCount: Int) = if (page in 0 until pageCount) Part(page) else null

    companion object {
        const val NONE = -1

        /** A [spread] layout of [views] given as page indices in reading order, each page whole. */
        fun of(views: List<IntArray>, spread: Boolean, rtl: Boolean): PageLayout =
            PageLayout(spread, rtl, views.map { v -> v.map { Part(it) } })
    }
}
