package minty.emanata.data

/** Thumbnail size of list rows, smallest first, one slider step each; [NORMAL] is 72 dp tall. */
enum class ListDensity(val key: String) {
    TINY("tiny"),
    COMPACT("compact"),
    NORMAL("normal"),
    LARGE("large"),
    EXTRA_LARGE("extra_large"),
    HUGE("huge");

    /** Position on the density slider, 0 for [TINY]. */
    val step: Int get() = ordinal

    companion object {
        fun of(key: String?): ListDensity = entries.firstOrNull { it.key == key } ?: NORMAL

        /** The density at slider [step], clamped to the ends. */
        fun ofStep(step: Int): ListDensity = entries[step.coerceIn(0, entries.lastIndex)]
    }
}

/** Where an entry's details (size, pages, read count) show. */
enum class DetailDisplay(val key: String) {
    HIDDEN("hidden"),

    /** On their own line below the title. */
    COMFORTABLE("comfortable"),

    /** Right-aligned at the end of the title's last line, as "done/total". */
    DENSE("dense");

    companion object {
        fun of(key: String?): DetailDisplay = entries.firstOrNull { it.key == key } ?: COMFORTABLE
    }
}

/**
 * How one library tab shows its entries: as a [grid] of [columns] x [rows] cells
 * (0 = auto) or as a list of [density] rows, with [detail] where the details go.
 */
data class TabView(
    val grid: Boolean,
    val columns: Int = 0,
    val rows: Int = 0,
    val density: ListDensity = ListDensity.NORMAL,
    val detail: DetailDisplay = DetailDisplay.COMFORTABLE
) {
    companion object {
        /** Largest column or row count; 0 is auto. */
        const val MAX_CELLS = 6

        /** Browse and Favorites start as grids, History and Bookmarks as lists. */
        fun defaultFor(tab: String) = TabView(grid = tab == Prefs.TAB_BROWSE || tab == Prefs.TAB_FAVORITES)
    }
}
