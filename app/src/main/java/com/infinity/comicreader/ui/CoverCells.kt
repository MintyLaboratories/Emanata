package com.infinity.comicreader.ui

import kotlin.math.roundToInt

/** A folder peek's grid: [columns] cells across, each holding a [width] x [height] cover. */
data class CoverCells(val columns: Int, val width: Int, val height: Int) {
    companion object {
        /** Height of a cover over its width when the library's tile tells none. */
        const val DEFAULT_ASPECT = 1.5

        /**
         * Cells across [avail] pixels, each with [pad] pixels on either side of its cover.
         * Covers take the library's [tile] size (width, height) when it is at least
         * [minWidth] wide, so covers it already made show 1:1; smaller tiles (list rows)
         * grow to cells about [cellWidth] wide in the tile's shape. At most [maxColumns].
         */
        fun of(avail: Int, tile: IntArray?, minWidth: Int, cellWidth: Int, pad: Int, maxColumns: Int): CoverCells {
            if (tile != null && tile[0] >= minWidth && tile[1] > 0 && tile[0] + 2 * pad <= avail) {
                val columns = (avail / (tile[0] + 2 * pad)).coerceIn(1, maxColumns)
                return CoverCells(columns, tile[0], tile[1])
            }
            val columns = (avail / cellWidth.coerceAtLeast(1)).coerceIn(2, maxColumns)
            val width = (avail / columns - 2 * pad).coerceAtLeast(1)
            val aspect = if (tile != null && tile[0] > 0 && tile[1] > 0) tile[1].toDouble() / tile[0] else DEFAULT_ASPECT
            return CoverCells(columns, width, (width * aspect).roundToInt().coerceAtLeast(1))
        }
    }
}
