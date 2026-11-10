package com.infinity.comicreader.debug

import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.reader.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detector parity check's comparison of two score maps and their lines. */
class DetectorParityTest {

    private fun map(w: Int, h: Int, f: (Int, Int) -> Float) = ScoreMap(w, h, FloatArray(w * h) { f(it % w, it / w) })

    private fun box(l: Double, t: Double, r: Double, b: Double) = SmartCrop.TextBox(l, t, r, b, 0.9f)

    /** A 320 x 320 map with one line of text: rows 50 until 100, columns 40 until 280. */
    private val line = map(320, 320) { x, y -> if (x in 40 until 280 && y in 50 until 100) 0.95f else 0.02f }

    @Test
    fun theSameMapAndLinesAreOk() {
        val lines = SmartCrop.lines(line, 320, 320)
        val c = DetectorParity.compare(line, line, lines, lines, 320, 320)
        assertEquals(0.0, c.meanDiff, 0.0)
        assertEquals(1.0, c.textIou, 0.0)
        assertEquals(1, c.lines)
        assertEquals(1, c.matched)
        assertEquals(2, c.titles)
        assertTrue(c.evidence)
        assertTrue(c.ok)
    }

    @Test
    fun aPageWithoutTextForAFocusIsNoEvidence() {
        // The same small lines in both: nothing a focus uses, so nothing to judge by.
        val small = listOf(box(10.0, 50.0, 60.0, 54.0), box(10.0, 70.0, 60.0, 74.0))
        val c = DetectorParity.compare(line, line, small, small, 100, 100)
        assertEquals(0, c.big)
        assertEquals(0, c.titles)
        assertEquals(0.0, c.focusDiff, 0.0)
        assertFalse(c.evidence)
        assertTrue(c.same)
        assertFalse(c.ok)
        // Nor are no lines at all.
        assertFalse(DetectorParity.compare(line, line, emptyList(), emptyList(), 100, 100).ok)
    }

    @Test
    fun theVerdictTellsDifferentCopiesFromPagesWithoutEvidence() {
        val lines = SmartCrop.lines(line, 320, 320)
        val judged = DetectorParity.compare(line, line, lines, lines, 320, 320)
        val small = listOf(box(10.0, 50.0, 60.0, 54.0))
        val unjudged = DetectorParity.compare(line, line, small, small, 100, 100)
        val none = map(320, 320) { _, _ -> 0.02f }
        val differs = DetectorParity.compare(line, none, lines, emptyList(), 320, 320)
        assertEquals(DetectorParity.OK, DetectorParity.verdict(listOf(judged, judged)))
        assertEquals(DetectorParity.INCONCLUSIVE, DetectorParity.verdict(listOf(judged, unjudged)))
        assertEquals(DetectorParity.INCONCLUSIVE, DetectorParity.verdict(emptyList()))
        assertEquals(DetectorParity.FAIL, DetectorParity.verdict(listOf(unjudged, differs)))
    }

    @Test
    fun aFewEdgePixelsApartAreStillOk() {
        // One edge column of the line falls below the threshold, as fp16 weights may make it.
        val other = map(320, 320) { x, y -> if (x in 40 until 279 && y in 50 until 100) 0.95f else if (x == 279 && y in 50 until 100) 0.2f else 0.02f }
        val c = DetectorParity.compare(line, other, SmartCrop.lines(line, 320, 320), SmartCrop.lines(other, 320, 320), 320, 320)
        assertTrue(c.maxDiff > 0.7)
        assertTrue(c.meanDiff < 0.005)
        assertTrue(c.textIou > 0.95)
        assertTrue(c.ok)
    }

    @Test
    fun aMissingOrMovedLineFails() {
        val none = map(320, 320) { _, _ -> 0.02f }
        assertFalse(DetectorParity.compare(line, none, SmartCrop.lines(line, 320, 320), emptyList(), 320, 320).ok)
        val moved = map(320, 320) { x, y -> if (x in 40 until 280 && y in 150 until 200) 0.95f else 0.02f }
        val c = DetectorParity.compare(line, moved, SmartCrop.lines(line, 320, 320), SmartCrop.lines(moved, 320, 320), 320, 320)
        assertEquals(1, c.otherLines)
        assertEquals(0, c.matched)
        assertFalse(c.ok)
    }

    @Test
    fun aTallerGlyphFailsThoughTheLinesOverlap() {
        val a = listOf(box(0.0, 0.0, 100.0, 20.0))
        val b = listOf(box(0.0, 0.0, 100.0, 22.0))
        val c = DetectorParity.compare(line, line, a, b, 100, 100)
        assertEquals(1, c.matched)
        assertFalse(c.ok)
    }

    @Test
    fun smallLinesLeftUnmatchedDoNotCountButAreTold() {
        val title = box(10.0, 10.0, 90.0, 30.0)
        // Glyphs of 4 px on a 100 px side: too small for any focus.
        val a = listOf(title, box(10.0, 50.0, 14.0, 54.0))
        val b = listOf(title, box(13.0, 50.0, 17.0, 54.0))
        val c = DetectorParity.compare(line, line, a, b, 100, 100)
        assertEquals(1, c.matched)
        assertEquals(2, c.big)
        assertEquals(2, c.bigMatched)
        assertEquals(listOf(0.04, 0.04), c.unmatched)
        assertEquals(0.0, c.focusDiff, 0.0)
        assertTrue(c.ok)
    }

    @Test
    fun focusesApartByTheirFarthestEdge() {
        assertEquals(0.0, DetectorParity.apart(null, null), 0.0)
        assertEquals(1.0, DetectorParity.apart(Box(0.1, 0.1, 0.5, 0.2), null), 0.0)
        assertEquals(0.03, DetectorParity.apart(Box(0.1, 0.1, 0.5, 0.2), Box(0.1, 0.12, 0.5, 0.23)), 1e-9)
    }

    @Test
    fun linesOverlapByIntersectionOverUnion() {
        assertEquals(1.0, DetectorParity.iou(box(0.0, 0.0, 10.0, 10.0), box(0.0, 0.0, 10.0, 10.0)), 1e-9)
        assertEquals(1.0 / 3, DetectorParity.iou(box(0.0, 0.0, 10.0, 10.0), box(5.0, 0.0, 15.0, 10.0)), 1e-9)
        assertEquals(0.0, DetectorParity.iou(box(0.0, 0.0, 10.0, 10.0), box(10.0, 0.0, 20.0, 10.0)), 0.0)
    }

    @Test
    fun pagesAreDetectorInputsOfFourShapes() {
        assertEquals(4, DetectorParity.PAGES.size)
        for (p in DetectorParity.PAGES) {
            assertEquals(0, p[0] % SmartCrop.STRIDE)
            assertEquals(0, p[1] % SmartCrop.STRIDE)
            assertTrue(maxOf(p[0], p[1]) <= SmartCrop.INPUT_SIDE)
        }
    }
}
