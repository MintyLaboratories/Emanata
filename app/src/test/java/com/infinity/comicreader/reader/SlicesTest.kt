package com.infinity.comicreader.reader

import com.infinity.comicreader.data.ReadingDirection
import com.infinity.comicreader.reader.DirectionDetection.Detected
import com.infinity.comicreader.reader.DirectionDetection.Source
import com.infinity.comicreader.reader.Slices.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlicesTest {

    private fun sizes(vararg wh: Pair<Int, Int>): List<IntArray?> = wh.map { intArrayOf(it.first, it.second) }

    private val sliced = sizes(800 to 2400, 800 to 3100, 800 to 1900, 800 to 4000, 800 to 2700, 800 to 3600)

    @Test
    fun tallPagesOfOneWidthAndVaryingHeightsAreSlices() {
        assertEquals(Shape.SURE, Slices.shape(sliced))
        assertEquals(Detected(ReadingDirection.TTB, Source.SHAPE), Slices.verdict(Shape.SURE))
    }

    @Test
    fun aCoverBeforeTheSlicesStillLeavesThemSure() {
        assertEquals(Shape.SURE, Slices.shape(sizes(2000 to 3000) + sliced))
        // With only three slices behind it, the seams decide.
        assertEquals(Shape.LIKELY, Slices.shape(sizes(2000 to 3000, 800 to 2400, 800 to 3100, 800 to 1900)))
    }

    @Test
    fun uniformHeightsLeaveItToTheSeams() {
        assertEquals(Shape.LIKELY, Slices.shape(List(8) { intArrayOf(800, 4000) }))
    }

    @Test
    fun widthsWithinOnePercentAreOne() {
        assertEquals(Shape.SURE, Slices.shape(sizes(800 to 2400, 805 to 3100, 798 to 1900, 800 to 4000)))
    }

    @Test
    fun ordinaryPagesAreNotSlices() {
        assertEquals(Shape.NONE, Slices.shape(List(20) { intArrayOf(2000, 3000) }))
        assertEquals(Shape.NONE, Slices.shape(List(12) { intArrayOf(1988, 3056 + it) }))
    }

    @Test
    fun twoFileSpreadsAreNotSlices() {
        assertEquals(Shape.NONE, Slices.shape(sizes(2000 to 3000, 4000 to 3000, 2000 to 3000, 2000 to 3000, 4000 to 3000, 2000 to 3000)))
    }

    @Test
    fun aFewTallPagesInAComicAreNotSlices() {
        // Two fold-out posters among ordinary pages.
        assertEquals(Shape.NONE, Slices.shape(List(10) { intArrayOf(2000, 3000) } + sizes(2000 to 6000, 2000 to 6100)))
    }

    @Test
    fun tooTallOrMixedWidthsAreNotSlices() {
        assertEquals(Shape.NONE, Slices.shape(List(6) { intArrayOf(800, 8800 + it) }))
        assertEquals(Shape.NONE, Slices.shape(sizes(600 to 2400, 800 to 3100, 1000 to 2900, 700 to 4000, 900 to 2700)))
    }

    @Test
    fun unknownSizesAreSkipped() {
        assertEquals(Shape.SURE, Slices.shape(listOf(null) + sliced + listOf(null, intArrayOf(0, 0))))
        assertEquals(Shape.NONE, Slices.shape(listOf(null, intArrayOf(800, 2400), null, intArrayOf(800, 3000))))
        assertEquals(Shape.NONE, Slices.shape(emptyList()))
    }

    @Test
    fun onlyTheFirstPagesAreLookedAt() {
        val late = List(Slices.SAMPLE) { intArrayOf(2000, 3000) } + sliced
        assertEquals(Shape.NONE, Slices.shape(late))
    }

    @Test
    fun fourOrdinaryPagesFirstAreNotSlices() {
        assertEquals(true, Slices.ordinary(List(4) { intArrayOf(2000, 3000) }))
        assertEquals(true, Slices.ordinary(sizes(2000 to 3000, 4000 to 3000, 2000 to 3000, 2000 to 3000, 800 to 2400)))
        // A cover or credits before the slices, an unknown size, or too few pages do not settle it.
        assertEquals(false, Slices.ordinary(sizes(2000 to 3000, 800 to 2400, 2000 to 3000, 2000 to 3000)))
        assertEquals(false, Slices.ordinary(listOf(null) + List(4) { intArrayOf(2000, 3000) }))
        assertEquals(false, Slices.ordinary(List(3) { intArrayOf(2000, 3000) }))
    }

    @Test
    fun shapesSettleWhatTheyCan() {
        assertEquals(Slices.Checked(Detected(ReadingDirection.TTB, Source.SHAPE)), Slices.settled(sliced))
        assertEquals(Slices.Checked(null), Slices.settled(List(4) { intArrayOf(2000, 3000) }))
        assertEquals(Slices.Checked(null), Slices.settled(sizes(2000 to 3000, 800 to 2400, 2000 to 3000)))
        // Uniform slices wait for their seams, unknown sizes for their headers.
        assertNull(Slices.settled(List(8) { intArrayOf(800, 4000) }))
        assertNull(Slices.settled(listOf(null, intArrayOf(2000, 3000), intArrayOf(2000, 3000))))
    }

    @Test
    fun seamsPairNeighboringSlices() {
        val book = sizes(2000 to 3000, 800 to 2400, 800 to 3100, 800 to 1900, 2000 to 3000, 800 to 2400, 800 to 2400)
        assertEquals(listOf(1, 2, 5), Slices.seamPairs(book))
        assertEquals(listOf(1), Slices.seamPairs(listOf(null, intArrayOf(800, 2400), intArrayOf(800, 2500), null, intArrayOf(800, 2600))))
        assertEquals(emptyList<Int>(), Slices.seamPairs(List(6) { intArrayOf(2000, 3000) }))
    }

    @Test
    fun seamsSettleALikelyShapeWhenEnoughTell() {
        val seams = Detected(ReadingDirection.TTB, Source.SEAMS)
        assertEquals(seams, Slices.verdict(Shape.LIKELY, List(6) { true }))
        assertEquals(seams, Slices.verdict(Shape.LIKELY, listOf(true, true, false, true, true, true, true)))
        // Flat seams do not count either way.
        assertEquals(seams, Slices.verdict(Shape.LIKELY, List(5) { true } + List(10) { null }))
        assertNull(Slices.verdict(Shape.LIKELY, List(4) { true } + List(10) { null }))
        assertNull(Slices.verdict(Shape.LIKELY, listOf(true, true, true, false, false, false)))
        assertNull(Slices.verdict(Shape.LIKELY))
        assertNull(Slices.verdict(Shape.NONE, List(10) { true }))
    }

    @Test
    fun generatedSlicesOfOneStripAreTopToBottom() {
        val width = 240
        val heights = List(8) { 960 }
        val slices = SyntheticStrips.cut(SyntheticStrips.strip(width, heights.sum(), seed = 11), width, heights)
        val shape = Slices.shape(heights.map { intArrayOf(width, it) })
        assertEquals(Shape.LIKELY, shape)
        assertEquals(Detected(ReadingDirection.TTB, Source.SEAMS), Slices.verdict(shape, seams(slices, width)))
    }

    @Test
    fun generatedTallPagesThatDoNotRunOnAreNot() {
        val width = 240
        val pages = List(8) { SyntheticStrips.strip(width, 960, seed = 40L + it) }
        assertNull(Slices.verdict(Slices.shape(List(8) { intArrayOf(width, 960) }), seams(pages, width)))
    }

    @Test
    fun generatedSlicesWithBlankGapsNeedEnoughSeamsThatTell() {
        val width = 240
        val heights = List(8) { 960 }
        // Every seam but two falls in a white gap between panels.
        val gaps = (1 until 8).filter { it != 3 && it != 6 }.map { k -> (k * 960 - 40) until (k * 960 + 40) }
        val slices = SyntheticStrips.cut(SyntheticStrips.strip(width, heights.sum(), seed = 12, blank = gaps), width, heights)
        val told = seams(slices, width)
        assertEquals(2, told.count { it != null })
        assertNull(Slices.verdict(Shape.LIKELY, told))
    }

    private fun seams(pages: List<IntArray>, width: Int): List<Boolean?> = (0 until pages.size - 1).map { k ->
        val a = Seams.profile(pages[k], width, pages[k].size / width, bottom = true)!!
        val b = Seams.profile(pages[k + 1], width, pages[k + 1].size / width, bottom = false)!!
        Seams.runsOn(a, b)
    }
}
