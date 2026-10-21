package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverCellsTest {

    @Test
    fun libraryCoverSizeIsKept() {
        // 1000 px across, 200 x 300 covers with 8 px around each: four cells.
        assertEquals(CoverCells(4, 200, 300), CoverCells.of(1000, intArrayOf(200, 300), 150, 280, 4, 12))
    }

    @Test
    fun smallListThumbnailsGrowInTheirShape() {
        val cells = CoverCells.of(1000, intArrayOf(80, 120), 150, 280, 4, 12)
        assertEquals(3, cells.columns)
        assertEquals(325, cells.width)
        assertEquals(488, cells.height)
    }

    @Test
    fun listThumbnailsTakeTheGridCoverSizeWhenKnown() {
        assertEquals(CoverCells(4, 200, 300), CoverCells.of(1000, intArrayOf(80, 120), 150, 280, 4, 12, grid = intArrayOf(200, 300)))
        // A grid tile is used as it is, the grid size only stands in for a list row.
        assertEquals(CoverCells(3, 300, 450), CoverCells.of(1000, intArrayOf(300, 450), 150, 280, 4, 12, grid = intArrayOf(200, 300)))
        // A grid cover too small or too wide to use: list thumbnails grow as before.
        assertEquals(325, CoverCells.of(1000, intArrayOf(80, 120), 150, 280, 4, 12, grid = intArrayOf(100, 150)).width)
        assertEquals(325, CoverCells.of(1000, intArrayOf(80, 120), 150, 280, 4, 12, grid = intArrayOf(1200, 1800)).width)
    }

    @Test
    fun withoutATileCoversArePortrait() {
        val cells = CoverCells.of(600, null, 150, 280, 0, 12)
        assertEquals(CoverCells(2, 300, 450), cells)
    }

    @Test
    fun columnsAreCapped() = assertEquals(12, CoverCells.of(10_000, intArrayOf(160, 240), 150, 280, 0, 12).columns)

    @Test
    fun tileWiderThanTheGridFallsBack() = assertEquals(2, CoverCells.of(300, intArrayOf(400, 600), 150, 280, 0, 12).columns)
}
