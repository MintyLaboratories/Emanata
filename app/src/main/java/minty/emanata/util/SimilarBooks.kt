package minty.emanata.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds books that are probably the same book in another file: repacked into another
 * archive or re-encoded into another image format, so neither bytes nor Book IDs match.
 * Two books are alike when their page counts differ by one at most and the pages at the
 * same places look alike ([PageHash]). Free of Android types.
 *
 * A book is sampled at [SLOTS] places: the cover and pages a quarter, half and three
 * quarters in. Each place holds a page and the one after it, so a page added or dropped
 * anywhere before it still leaves a page both books share.
 */
object SimilarBooks {

    /** Where in a book its sampled places start, as shares of its last page's index. */
    private val SHARES = doubleArrayOf(0.0, 0.25, 0.5, 0.75)

    /** Places sampled in every book. */
    val SLOTS = SHARES.size

    /** Most bits of 128 two pages may differ in and still look alike. */
    const val MAX_DISTANCE = 12

    /**
     * Most bits two pages may differ in when either book is rendered from vector pages (PDF)
     * or short ([SHORT_BOOK]): pages of text in one layout come close to each other, and a
     * short book's places share pages.
     */
    const val STRICT_DISTANCE = 6

    /** Books of fewer pages are compared strictly ([STRICT_DISTANCE]). */
    const val SHORT_BOOK = 8

    /** Places a strict comparison needs alike, none other. */
    private const val STRICT_PLACES = 3

    /**
     * What is compared of a book: its page count, for each sampled place the hashes of its
     * pages (null where unreadable), and whether its pages are [vector] (rendered, as a PDF's).
     */
    data class Print(val pages: Int, val slots: List<List<PageHash?>>, val vector: Boolean = false)

    /** The pages of a book of [pageCount] pages sampled for each place, in order: one or two each, none without pages. */
    fun slotPages(pageCount: Int): List<List<Int>> = SHARES.map { share ->
        if (pageCount <= 0) return@map emptyList()
        val first = (share * (pageCount - 1)).roundToInt()
        if (first + 1 < pageCount) listOf(first, first + 1) else listOf(first)
    }

    /** Every page [slotPages] samples, each once, in book order. */
    fun pagesToRead(pageCount: Int): List<Int> = slotPages(pageCount).flatten().distinct().sorted()

    /** The closest pair of telling (not flat) pages of two places; null when either has none. */
    fun distance(a: List<PageHash?>, b: List<PageHash?>): Int? {
        var best: Int? = null
        for (x in a) {
            if (x == null || x.flat) continue
            for (y in b) {
                if (y == null || y.flat) continue
                val d = x.distance(y)
                if (best == null || d < best) best = d
            }
        }
        return best
    }

    /**
     * True when [a] and [b] are probably one book: page counts at most one apart, two or
     * more places compared, and every compared place alike, or all but one when four are.
     * When either is vector or short, [STRICT_PLACES] or more places must be compared, all
     * alike within [STRICT_DISTANCE].
     */
    fun alike(a: Print, b: Print): Boolean {
        if (a.pages <= 0 || b.pages <= 0 || abs(a.pages - b.pages) > 1) return false
        val strict = a.vector || b.vector || minOf(a.pages, b.pages) < SHORT_BOOK
        val most = if (strict) STRICT_DISTANCE else MAX_DISTANCE
        var compared = 0
        var matched = 0
        for (i in 0 until minOf(a.slots.size, b.slots.size)) {
            val d = distance(a.slots[i], b.slots[i]) ?: continue
            compared++
            if (d <= most) matched++
        }
        val misses = compared - matched
        if (strict) return misses == 0 && matched >= STRICT_PLACES
        if (matched < 2) return false
        return misses == 0 || (compared >= SLOTS && misses <= 1)
    }

    /**
     * The groups of two or more [items] alike by their [prints], each in the order given,
     * in the order of their first item; items without a print are left out. Alike is
     * followed through: a like b and b like c puts all three together.
     */
    fun <T> groups(items: List<T>, prints: (T) -> Print?): List<List<T>> {
        val known = items.mapNotNull { item -> prints(item)?.let { item to it } }
        val parent = IntArray(known.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        // Sorted by page count, only neighbors one page apart at most need comparing.
        val order = known.indices.sortedBy { known[it].second.pages }
        for (i in order.indices) {
            val a = known[order[i]].second
            for (j in i + 1 until order.size) {
                val b = known[order[j]].second
                if (b.pages - a.pages > 1) break
                if (root(order[i]) != root(order[j]) && alike(a, b)) parent[root(order[j])] = root(order[i])
            }
        }
        return known.indices.groupBy { root(it) }.values
            .filter { it.size > 1 }
            .map { it.sorted() }
            .sortedBy { it.first() }
            .map { members -> members.map { known[it].first } }
    }
}
