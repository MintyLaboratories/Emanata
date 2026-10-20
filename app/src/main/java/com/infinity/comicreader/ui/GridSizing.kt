package com.infinity.comicreader.ui

/** Library grid geometry, kept free of Android types so it can be unit-tested. */
object GridSizing {

    /** Width per column when the column count is automatic. */
    const val AUTO_COLUMN_DP = 120f

    /** Columns for the [setting] (0 = auto: one per [AUTO_COLUMN_DP] of [widthDp], 2..6). */
    fun columns(setting: Int, widthDp: Float): Int {
        if (setting > 0) return setting
        return (widthDp / AUTO_COLUMN_DP).toInt().coerceIn(2, 6)
    }

    /**
     * Height of one grid cell so that [rows] rows fill [availablePx], but never below
     * [minPx]. Returns 0 (auto: covers keep their fixed height) when [rows] is 0 or
     * the space is not measured yet.
     */
    fun cellHeight(availablePx: Int, rows: Int, minPx: Int = 0): Int {
        if (rows <= 0 || availablePx <= 0) return 0
        return maxOf(availablePx / rows, minPx)
    }

    /**
     * Height the grid rows share in a list [heightPx] tall: less its top padding, its
     * bottom padding (which keeps the last row clear of the floating button) and the
     * full-width [headerPx] row above the cells, if any.
     */
    fun rowSpace(heightPx: Int, paddingTop: Int, paddingBottom: Int, headerPx: Int): Int =
        (heightPx - paddingTop - paddingBottom - headerPx).coerceAtLeast(0)

    /**
     * How many section headers show at the top of the list with its first [rows]
     * rows: sections hold [sectionItems] cells each (0 when collapsed) in [columns]
     * columns, and every header reached before the rows run out counts.
     */
    fun headersWithin(rows: Int, sectionItems: List<Int>, columns: Int): Int {
        var used = 0
        var headers = 0
        for (items in sectionItems) {
            if (used >= rows) break
            headers++
            used += (items + columns - 1) / columns
        }
        return headers
    }
}
