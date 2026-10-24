package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry

/** How a new list of library rows relates to the one shown, so a refresh redraws only what changed. */
object RowChanges {
    /**
     * True when [new] holds the same rows as [old] in the same order: entries by [keyOf],
     * section headers by kind, whatever else about them changed.
     */
    fun sameRows(old: List<Any>, new: List<Any>): Boolean {
        if (old.size != new.size) return false
        for (i in old.indices) {
            val a = old[i]
            val b = new[i]
            val same = when (a) {
                is BookEntry -> b is BookEntry && keyOf(a) == keyOf(b)
                is SectionRow -> b is SectionRow && a.kind == b.kind
                else -> a == b
            }
            if (!same) return false
        }
        return true
    }

    /** What tells rows apart: the path, and for a bookmark its page (a book can have several). */
    fun keyOf(entry: BookEntry): String = if (entry.bookmarkPage >= 0) entry.path + "#" + entry.bookmarkPage else entry.path

    /** A stable, non-negative row id for [entry] (64-bit FNV-1a of its [keyOf]). */
    fun idOf(entry: BookEntry): Long {
        var hash = -0x340d631b7bdddcdbL
        for (c in keyOf(entry)) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }
}
