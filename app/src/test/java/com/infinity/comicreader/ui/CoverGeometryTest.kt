package com.infinity.comicreader.ui

import com.infinity.comicreader.data.CoverShape
import com.infinity.comicreader.data.ListDensity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverGeometryTest {

    @Test
    fun aTallCellFitsTheShapeByWidth() {
        assertArrayEquals(intArrayOf(200, 300), CoverGeometry.box(200, 400, 2f / 3f))
    }

    @Test
    fun aWideCellFitsTheShapeByHeight() {
        assertArrayEquals(intArrayOf(200, 300), CoverGeometry.box(500, 300, 2f / 3f))
        assertArrayEquals(intArrayOf(225, 300), CoverGeometry.box(500, 300, 3f / 4f))
    }

    @Test
    fun theCellShapeTakesTheWholeCell() {
        assertArrayEquals(intArrayOf(500, 300), CoverGeometry.box(500, 300, 0f))
    }

    @Test
    fun aFreeHeightFollowsTheShape() {
        assertEquals(300, CoverGeometry.freeHeight(200, 2f / 3f, 999))
        assertEquals(999, CoverGeometry.freeHeight(200, 0f, 999))
    }

    @Test
    fun aBookmarkPageTakesEightyPercent() {
        assertArrayEquals(intArrayOf(80, 120), com.infinity.comicreader.data.CoverArt.pageBox(100, 150))
        assertArrayEquals(intArrayOf(1, 1), com.infinity.comicreader.data.CoverArt.pageBox(1, 1))
    }

    @Test
    fun listThumbnailsKeepTodaysNormalSlot() {
        // Today's 54 x 72 dp slot is Normal with the cell's shape.
        assertArrayEquals(intArrayOf(54, 72), CoverGeometry.listBoxDp(ListDensity.NORMAL, CoverShape.CELL))
        assertArrayEquals(intArrayOf(54, 72), CoverGeometry.listBoxDp(ListDensity.NORMAL, CoverShape.WIDE))
        assertArrayEquals(intArrayOf(48, 72), CoverGeometry.listBoxDp(ListDensity.NORMAL, CoverShape.BOOK))
        assertArrayEquals(intArrayOf(32, 48), CoverGeometry.listBoxDp(ListDensity.COMPACT, CoverShape.BOOK))
        assertArrayEquals(intArrayOf(80, 120), CoverGeometry.listBoxDp(ListDensity.LARGE, CoverShape.BOOK))
    }
}
