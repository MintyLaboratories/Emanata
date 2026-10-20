package com.infinity.comicreader.ui

/**
 * Item/page bookkeeping for chapters laid end to end in one adapter. Pages keep
 * their absolute number in the strip, so a decoded-page cache keyed by page stays
 * valid across trims; [base] is the page item 0 shows once chapters before it are
 * trimmed away. Positions are adapter item positions, counted from [base].
 *
 * Only single-page layouts hold more than one chapter or are trimmed: a spread
 * layout keeps its one chapter, so [base] stays 0 and items map through [layout].
 */
class PageStrip<T>(first: T, pageCount: Int, private val layout: PageLayout) {

    /** A chapter in the strip: [first] is its first page in the whole strip. */
    class Chapter<T>(val value: T, val first: Int, val count: Int)

    /** Item positions [position] until [position] + [count] left the strip, with [chapters]. */
    class Removal<T>(val position: Int, val count: Int, val chapters: List<T>)

    private val list = ArrayList<Chapter<T>>()

    /** The chapters currently in the strip, in reading order. */
    val chapters: List<Chapter<T>> get() = list

    /** One past the last page in the strip. */
    val end get() = list.lastOrNull()?.let { it.first + it.count } ?: 0

    /** The page item 0 shows. */
    var base = 0
        private set

    val itemCount get() = (layout.itemCount(end) - layout.itemOf(base)).coerceAtLeast(0)

    init {
        list += Chapter(first, 0, pageCount)
    }

    private fun item(position: Int) = layout.itemOf(base) + position

    /** The page each slot of item [position] shows, as [PageLayout.slots]. */
    fun slots(position: Int): IntArray = layout.slots(item(position), end)

    /** The last page item [position] shows. */
    fun lastPage(position: Int): Int = layout.lastPage(item(position), end)

    /** The chapter holding [page]. */
    fun chapterOf(page: Int): Chapter<T> = list.lastOrNull { page >= it.first } ?: list[0]

    /**
     * Lays [count] pages of [value] in behind the last chapter and returns the item
     * positions they were inserted at, or null when refused (spread layout, no pages).
     */
    fun append(value: T, count: Int): IntRange? {
        if (layout.spread || count <= 0) return null
        val start = end - base
        list += Chapter(value, end, count)
        return start until start + count
    }

    /**
     * Forgets every item before [position], leaving item 0 to show that page, and
     * drops the chapters that end before it. Null when nothing changes or in a spread.
     */
    fun dropTo(position: Int): Removal<T>? {
        if (layout.spread) return null
        val keep = (base + position).coerceIn(base, end)
        if (keep == base) return null
        val gone = forget { it.first + it.count <= keep }
        val removal = Removal(0, keep - base, gone)
        base = keep
        return removal
    }

    /**
     * Throws away every chapter starting at item [position] or later. Null when
     * nothing changes or in a spread.
     */
    fun dropAfter(position: Int): Removal<T>? {
        if (layout.spread) return null
        val first = base + position
        if (first >= end) return null
        val count = end - first
        return Removal(first - base, count, forget { it.first >= first })
    }

    /** Empties the strip and returns every chapter that was in it. */
    fun clear(): List<T> {
        val all = list.map { it.value }
        list.clear()
        base = 0
        return all
    }

    private fun forget(gone: (Chapter<T>) -> Boolean): List<T> {
        val dropped = list.filter(gone)
        list.removeAll(dropped)
        return dropped.map { it.value }
    }
}
