package com.infinity.comicreader.data

/**
 * How one library tab shows its entries: as a [grid] of [columns] x [rows] cells
 * (0 = auto) or as a list.
 */
data class TabView(
    val grid: Boolean,
    val columns: Int = 0,
    val rows: Int = 0
) {
    companion object {
        /** Largest column or row count; 0 is auto. */
        const val MAX_CELLS = 6

        /** Browse and Favorites start as grids, History and Bookmarks as lists. */
        fun defaultFor(tab: String) = TabView(grid = tab == Prefs.TAB_BROWSE || tab == Prefs.TAB_FAVORITES)
    }
}
