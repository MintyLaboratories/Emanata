package minty.emanata.data

import minty.emanata.util.NaturalOrder

/**
 * What the library sorts by. Each option has two named directions; the first one
 * listed is where it starts ([descendingFirst] tells which way that is). Options
 * whose values often tie ([hasTies]) take a second level.
 */
enum class SortKey(val key: String, val hasTies: Boolean, val descendingFirst: Boolean) {
    /** A → Z / Z → A, in natural order; the final tiebreaker of every other option. */
    NAME("name", false, false),

    /** Newest / oldest first. */
    YEAR("year", true, true),

    /** Newest / oldest first. */
    ADDED("added", false, true),

    /** Recent / oldest first. */
    ACCESSED("accessed", false, true),

    /** Largest / smallest first. */
    SIZE("size", false, true),

    /** Longest / shortest first. */
    LENGTH("length", false, true),

    /** Archives / images first: folder, archive, PDF, loose image. */
    TYPE("type", true, false),

    /** Left-to-right / right-to-left first. */
    DIRECTION("direction", true, false);

    companion object {
        fun of(key: String?): SortKey? = entries.firstOrNull { it.key == key }
    }
}

/** One sort level: [key] in its first direction, or the other one when [flipped]. */
data class SortLevel(val key: SortKey, val flipped: Boolean = false) {
    /** True when larger values come first. */
    val descending: Boolean get() = key.descendingFirst != flipped

    fun flip() = copy(flipped = !flipped)

    /** `year` or `year:flipped`. */
    fun encode(): String = if (flipped) "${key.key}:$FLIPPED" else key.key

    companion object {
        private const val FLIPPED = "flipped"

        fun decode(value: String?): SortLevel? {
            if (value.isNullOrEmpty()) return null
            val key = SortKey.of(value.substringBefore(':')) ?: return null
            return SortLevel(key, value.substringAfter(':', "") == FLIPPED)
        }
    }
}

/**
 * One tab's order: [foldersFirst], then [by], then [then] where [by] has ties,
 * then name A → Z. Two levels at most.
 */
data class TabSort(val by: SortLevel, val then: SortLevel? = null, val foldersFirst: Boolean = true) {
    /** The second level in effect: only after an option with ties, and never the first level again. */
    val thenInEffect: SortLevel? get() = then?.takeIf { by.key.hasTies && it.key != by.key }

    /**
     * Tapping [key] under "Sort by": the selected option flips, another is picked in
     * its first direction with "Then by" back at None.
     */
    fun tapBy(key: SortKey): TabSort =
        if (by.key == key) copy(by = by.flip()) else copy(by = SortLevel(key), then = null)

    /** Tapping [key] under "Then by" (null is None): the selected option flips, another is picked. */
    fun tapThen(key: SortKey?): TabSort = when {
        key == null -> copy(then = null)
        then?.key == key -> copy(then = then.flip())
        else -> copy(then = SortLevel(key))
    }

    /** Keys whose values the order needs beyond names and types. */
    val keys: Set<SortKey> get() = setOfNotNull(by.key, thenInEffect?.key)

    companion object {
        /** Browse and Favorites by name A → Z; History and Bookmarks by access, recent first. */
        fun defaultFor(tab: String) = TabSort(
            SortLevel(if (tab == Prefs.TAB_HISTORY || tab == Prefs.TAB_BOOKMARKS) SortKey.ACCESSED else SortKey.NAME)
        )
    }
}

/** An entry's value for a [SortKey] (types and names aside), or null when it has none. */
fun interface SortValues {
    fun of(entry: BookEntry, key: SortKey): Long?
}

object LibrarySort {
    /** Position of [entry] in "archives first" order: folder, archive, PDF, loose image. */
    fun typeRank(entry: BookEntry): Long? = when (entry.type) {
        EntryType.FOLDER -> 0
        EntryType.ARCHIVE -> 1
        EntryType.PDF -> 2
        EntryType.IMAGE -> 3
        EntryType.OTHER -> null
    }

    /** The order [sort] gives, reading values through [values]; entries without a value come last. */
    fun comparator(sort: TabSort, values: SortValues): Comparator<BookEntry> {
        var order: Comparator<BookEntry> =
            if (sort.foldersFirst) compareByDescending { it.isDir } else Comparator { _, _ -> 0 }
        order = order.then(level(sort.by, values))
        sort.thenInEffect?.let { order = order.then(level(it, values)) }
        return order.thenBy(NaturalOrder) { it.name }
    }

    private fun level(level: SortLevel, values: SortValues): Comparator<BookEntry> {
        if (level.key == SortKey.NAME) {
            val byName = compareBy<BookEntry, String>(NaturalOrder) { it.name }
            return if (level.flipped) byName.reversed() else byName
        }
        val key = level.key
        val descending = level.descending
        return Comparator { a, b ->
            val x = valueOf(a, key, values)
            val y = valueOf(b, key, values)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                descending -> y.compareTo(x)
                else -> x.compareTo(y)
            }
        }
    }

    /** [entry]'s value for [key]: its type rank for [SortKey.TYPE], else what [values] says. */
    fun valueOf(entry: BookEntry, key: SortKey, values: SortValues): Long? =
        if (key == SortKey.TYPE) typeRank(entry) else values.of(entry, key)
}
