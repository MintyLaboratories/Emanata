package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.FavoriteKind

/** One Favorites tab section: its entries in display order and how many are still being classified. */
data class FavoriteGroup(val kind: FavoriteKind, val entries: List<BookEntry>, val pending: Int)

/** A section header row of the library list. */
data class SectionRow(val kind: FavoriteKind, val title: String, val count: Int, val collapsed: Boolean, val loading: Boolean)

object FavoriteSections {
    /**
     * Splits [sorted] into Libraries, Sets and Comics, keeping its order inside each
     * and leaving empty sections out. An entry whose kind is not known yet
     * ([kindOf] gives null) waits under Sets.
     */
    fun arrange(sorted: List<BookEntry>, kindOf: (BookEntry) -> FavoriteKind?): List<FavoriteGroup> {
        val by = HashMap<FavoriteKind, MutableList<BookEntry>>()
        val pending = HashMap<FavoriteKind, Int>()
        for (entry in sorted) {
            val kind = kindOf(entry)
            val section = kind ?: FavoriteKind.SET
            by.getOrPut(section) { ArrayList() }.add(entry)
            if (kind == null) pending[section] = (pending[section] ?: 0) + 1
        }
        return FavoriteKind.entries.mapNotNull { kind ->
            by[kind]?.let { FavoriteGroup(kind, it, pending[kind] ?: 0) }
        }
    }

    /** Header and entry rows for [groups]; a collapsed section shows its header only. */
    fun rows(groups: List<FavoriteGroup>, collapsed: Set<FavoriteKind>, title: (FavoriteKind) -> String): List<Any> {
        val out = ArrayList<Any>()
        for (g in groups) {
            val closed = g.kind in collapsed
            out.add(SectionRow(g.kind, title(g.kind), g.entries.size, closed, g.pending > 0))
            if (!closed) out.addAll(g.entries)
        }
        return out
    }
}
