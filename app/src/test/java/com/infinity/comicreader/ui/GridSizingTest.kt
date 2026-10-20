package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
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
    fun headersWithinTheFirstRows() {
        // 3 columns: sections of 2, 7 and 1 cells take 1, 3 and 1 rows.
        assertEquals(1, GridSizing.headersWithin(1, listOf(2, 7, 1), 3))
        assertEquals(2, GridSizing.headersWithin(2, listOf(2, 7, 1), 3))
        assertEquals(2, GridSizing.headersWithin(4, listOf(2, 7, 1), 3))
        assertEquals(3, GridSizing.headersWithin(5, listOf(2, 7, 1), 3))
        // A collapsed section is a header without rows.
        assertEquals(3, GridSizing.headersWithin(2, listOf(0, 0, 4), 3))
        assertEquals(0, GridSizing.headersWithin(3, emptyList(), 3))
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
    fun rowsShareWhatPaddingAndHeaderLeave() {
        // 2400 px list, 8 px top, 192 px kept clear of the button, a 144 px "Recent" row.
        assertEquals(2056, GridSizing.rowSpace(2400, 8, 192, 144))
        assertEquals(2200, GridSizing.rowSpace(2400, 8, 192, 0))
        assertEquals(0, GridSizing.rowSpace(100, 8, 192, 144))
        assertEquals(685, GridSizing.cellHeight(GridSizing.rowSpace(2400, 8, 192, 144), 3))
    }
}
