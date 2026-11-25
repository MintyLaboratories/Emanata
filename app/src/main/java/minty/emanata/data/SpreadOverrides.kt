package minty.emanata.data

import minty.emanata.reader.HiddenPages
import org.json.JSONArray
import org.json.JSONObject

/**
 * A book's own spread choices, by book index, for when seams or Shift pair its pages wrongly:
 * pages shown [alone], and pages [paired] with the next page shown. A page is in one of them
 * at most, and no two pairs overlap.
 */
data class SpreadOverrides(val alone: Set<Int> = emptySet(), val paired: Set<Int> = emptySet()) {

    val isEmpty: Boolean get() = alone.isEmpty() && paired.isEmpty()

    /**
     * Without the choices that touch [page]: shown alone, or in a pair the reader chose, its
     * own or that of [previous], the page shown before it.
     */
    fun cleared(page: Int, previous: Int = page - 1) = SpreadOverrides(alone - page, paired - page - previous)

    /** [page] shown alone, any pair it was in undone ([cleared]). */
    fun showingAlone(page: Int, previous: Int = page - 1) = cleared(page, previous).let { it.copy(alone = it.alone + page) }

    /**
     * [page] paired with [next], the page shown after it; pairs either would overlap (that of
     * [previous], the page shown before, and that of [next]) are undone, and neither is shown
     * alone any more.
     */
    fun pairing(page: Int, next: Int = page + 1, previous: Int = page - 1) =
        SpreadOverrides(alone - page - next, paired - previous - next + page)

    /** The choices as pages shown while [hidden] are left out: those of hidden pages drop. */
    fun shown(hidden: Set<Int>): SpreadOverrides =
        if (hidden.isEmpty()) this
        else SpreadOverrides(
            alone.filter { it !in hidden }.mapTo(HashSet()) { HiddenPages.toShown(it, hidden) },
            paired.filter { it !in hidden }.mapTo(HashSet()) { HiddenPages.toShown(it, hidden) }
        )

    /** The choices once book page `i` went to [moves]`[i]` (gone where -1 or missing) of [pageCount]. */
    fun moved(moves: IntArray, pageCount: Int): SpreadOverrides {
        fun move(p: Int) = moves.getOrNull(p)?.takeIf { it in 0 until pageCount }
        return SpreadOverrides(alone.mapNotNullTo(HashSet(), ::move), paired.mapNotNullTo(HashSet(), ::move))
    }

    fun toJson(): JSONObject = JSONObject().put(ALONE, JSONArray(alone.sorted())).put(PAIRED, JSONArray(paired.sorted()))

    companion object {
        private const val ALONE = "alone"
        private const val PAIRED = "pair"

        /** [toJson]'s object; negative or malformed pages drop, and a page both alone and paired stays alone. */
        fun of(o: JSONObject?): SpreadOverrides {
            if (o == null) return SpreadOverrides()
            fun pages(key: String): Set<Int> {
                val arr = o.optJSONArray(key) ?: return emptySet()
                return (0 until arr.length()).mapNotNullTo(HashSet()) { arr.optInt(it, -1).takeIf { p -> p >= 0 } }
            }
            val alone = pages(ALONE)
            return SpreadOverrides(alone, pages(PAIRED) - alone)
        }
    }
}
