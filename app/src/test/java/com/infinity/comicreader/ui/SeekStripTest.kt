package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Half
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStripTest {

    private val portrait = intArrayOf(2000, 3000)
    private val wide = intArrayOf(4000, 3000)

    private fun labels(cells: List<SeekStrip.Cell>) = cells.map { it.label }

    @Test
    fun oneCellAPageNumberedByPage() {
        val cells = SeekStrip.cells(PageLayout(spread = false), 3, numbered = false) { portrait }
        assertEquals(listOf("1", "2", "3"), labels(cells))
        assertEquals(listOf(listOf(PageLayout.Part(0)), listOf(PageLayout.Part(1)), listOf(PageLayout.Part(2))), cells.map { it.parts })
        assertEquals(2f / 3f, cells[0].aspect, 1e-6f)
    }

    @Test
    fun mergedPairsShowBothPagesLabeledByTheirRange() {
        val layout = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3)), spread = true, rtl = false)
        val cells = SeekStrip.cells(layout, 4, numbered = false) { portrait }
        assertEquals(listOf("1", "2–3", "4"), labels(cells))
        assertEquals(4f / 3f, cells[1].aspect, 1e-6f)
    }

    @Test
    fun rightToLeftPairsKeepScreenOrderButReadTheirRangeLowFirst() {
        val layout = PageLayout.of(listOf(intArrayOf(0, 1)), spread = true, rtl = true)
        val cell = SeekStrip.cells(layout, 2, numbered = false) { portrait }.single()
        assertEquals(listOf(PageLayout.Part(1), PageLayout.Part(0)), cell.parts)
        assertEquals("1–2", cell.label)
    }

    @Test
    fun splitSpreadsTakeTwoNumberedCellsOfHalfTheWidth() {
        val layout = PageLayout.split(3, rtl = false) { it == 1 }
        val cells = SeekStrip.cells(layout, 3, numbered = true) { if (it == 1) wide else portrait }
        assertEquals(listOf("1", "2", "3", "4"), labels(cells))
        assertEquals(listOf(PageLayout.Part(1, Half.LEFT)), cells[1].parts)
        assertEquals(listOf(PageLayout.Part(1, Half.RIGHT)), cells[2].parts)
        assertEquals(2f / 3f, cells[1].aspect, 1e-6f)
    }

    @Test
    fun splitHalvesOfARightToLeftBookStartOnTheRight() {
        val cells = SeekStrip.cells(PageLayout.split(1, rtl = true) { true }, 1, numbered = true) { wide }
        assertEquals(listOf(Half.RIGHT, Half.LEFT), cells.map { it.parts.single().half })
    }

    @Test
    fun aWidePageAloneGetsAWideCell() {
        val cells = SeekStrip.cells(PageLayout(spread = false), 1, numbered = false) { wide }
        assertEquals(4f / 3f, cells.single().aspect, 1e-6f)
    }

    @Test
    fun unknownAndOddSizesFallBackOrAreBounded() {
        assertEquals(SeekStrip.DEFAULT_ASPECT, SeekStrip.partAspect(PageLayout.Part(0), null), 0f)
        assertEquals(SeekStrip.DEFAULT_ASPECT, SeekStrip.partAspect(PageLayout.Part(0), intArrayOf(0, 10)), 0f)
        assertEquals(SeekStrip.MAX_ASPECT, SeekStrip.partAspect(PageLayout.Part(0), intArrayOf(9000, 1000)), 0f)
        assertEquals(SeekStrip.MIN_ASPECT, SeekStrip.partAspect(PageLayout.Part(0), intArrayOf(100, 5000)), 0f)
    }

    @Test
    fun noPagesNoCells() {
        assertEquals(emptyList<SeekStrip.Cell>(), SeekStrip.cells(PageLayout(spread = false), 0, numbered = false) { null })
        assertEquals("", SeekStrip.pagesLabel(emptyList()))
    }

    @Test
    fun widthsRoundAndCenteringSplitsTheRest() {
        assertEquals(107, SeekStrip.widthOf(2f / 3f, 160))
        assertEquals(1, SeekStrip.widthOf(0f, 160))
        assertEquals(400, SeekStrip.centerOffset(1000, 200))
        assertEquals(-50, SeekStrip.centerOffset(100, 200))
    }
}
