package com.infinity.comicreader.reader

/** Where a book's pages went when their order changed, as when their names read anew. */
object PageMoves {
    /**
     * The new book index of each page, in old order, from the pages' keys in the old order
     * [before] and the new [after] ([ArchiveSource.pageKeys]); -1 for a page gone.
     */
    fun of(before: List<String>, after: List<String>): IntArray {
        val at = HashMap<String, Int>(after.size * 2)
        after.forEachIndexed { i, key -> at.putIfAbsent(key, i) }
        return IntArray(before.size) { at[before[it]] ?: -1 }
    }

    /** True when [moves] leaves every one of [count] pages where it was. */
    fun none(moves: IntArray, count: Int): Boolean = moves.size == count && moves.indices.all { moves[it] == it }
}
