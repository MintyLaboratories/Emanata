package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GridPickTest {

    @Test
    fun cellsCountFromTheTopLeftWithGaps() {
        // 36 px cells, 4 px gaps: cell n spans [40n, 40n + 36), its gap counts with it.
        assertEquals(0, GridPick.cellAt(0f, 36f, 4f))
        assertEquals(0, GridPick.cellAt(39f, 36f, 4f))
        assertEquals(1, GridPick.cellAt(40f, 36f, 4f))
        assertEquals(6, GridPick.cellAt(275f, 36f, 4f))
    }

    @Test
    fun dragsPastTheEdgesClampToTheGrid() {
        assertEquals(0, GridPick.cellAt(-50f, 36f, 4f))
        assertEquals(6, GridPick.cellAt(5000f, 36f, 4f))
    }

    @Test
    fun labelsNameColumnsFirstAndAutoForZero() {
        assertEquals("4 × 3", GridPick.label(4, 3, "Auto"))
        assertEquals("Auto × 3", GridPick.label(0, 3, "Auto"))
        assertEquals("Auto × Auto", GridPick.label(0, 0, "Auto"))
    }
}
