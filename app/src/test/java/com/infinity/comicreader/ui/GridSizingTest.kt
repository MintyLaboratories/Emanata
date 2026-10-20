package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridSizingTest {

    @Test
    fun explicitColumnsWin() {
        assertEquals(4, GridSizing.columns(4, 300f))
        assertEquals(2, GridSizing.columns(2, 1200f))
    }

    @Test
    fun autoColumnsFollowWidth() {
        assertEquals(3, GridSizing.columns(0, 360f))
        assertEquals(6, GridSizing.columns(0, 924f))
    }

    @Test
    fun autoColumnsStayInRange() {
        assertEquals(2, GridSizing.columns(0, 100f))
        assertEquals(6, GridSizing.columns(0, 2000f))
    }

    @Test
    fun autoRowsKeepFixedCells() {
        assertEquals(0, GridSizing.cellHeight(2400, 0))
    }

    @Test
    fun unmeasuredSpaceKeepsFixedCells() {
        assertEquals(0, GridSizing.cellHeight(0, 3))
    }

    @Test
    fun rowsSplitTheAvailableHeight() {
        assertEquals(800, GridSizing.cellHeight(2400, 3))
        assertEquals(2400, GridSizing.cellHeight(2400, 1))
    }

    @Test
    fun rowsRoundDown() {
        // Rounding down keeps the last row on screen.
        assertEquals(342, GridSizing.cellHeight(2400, 7))
    }

    @Test
    fun cellsNeverShrinkBelowMinimum() {
        assertEquals(300, GridSizing.cellHeight(900, 6, minPx = 300))
        assertEquals(450, GridSizing.cellHeight(900, 2, minPx = 300))
    }

    @Test
    fun coverWaitsForMeasuredCell() {
        assertNull(GridSizing.coverRequest(0, 0, rows = 0, fixedCoverPx = 336))
        assertNull(GridSizing.coverRequest(308, 0, rows = 3, fixedCoverPx = 336))
    }

    @Test
    fun coverTakesCellWidthAndFixedHeight() {
        assertEquals(308 to 336, GridSizing.coverRequest(308, 0, rows = 0, fixedCoverPx = 336))
    }

    @Test
    fun coverTakesCellSizeWithRows() {
        assertEquals(308 to 950, GridSizing.coverRequest(308, 950, rows = 3, fixedCoverPx = 336))
    }
}
