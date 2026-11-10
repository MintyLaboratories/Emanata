package com.infinity.comicreader.debug

import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.data.SmartCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detector parity check's comparison of two score maps and their lines. */
class DetectorParityTest {

    private fun map(w: Int, h: Int, f: (Int, Int) -> Float) = ScoreMap(w, h, FloatArray(w * h) { f(it % w, it / w) })

    private fun box(l: Double, t: Double, r: Double, b: Double) = SmartCrop.TextBox(l, t, r, b, 0.9f)

    /** A 64 x 64 map with one line of text: rows 10 until 20, columns 8 until 56. */
    private val line = map(64, 64) { x, y -> if (x in 8 until 56 && y in 10 until 20) 0.95f else 0.02f }

    @Test
    fun theSameMapAndLinesAreOk() {
        val lines = SmartCrop.lines(line, 64, 64)
        val c = DetectorParity.compare(line, line, lines, lines)
        assertEquals(0.0, c.meanDiff, 0.0)
        assertEquals(1.0, c.textIou, 0.0)
        assertEquals(1, c.lines)
        assertEquals(1, c.matched)
        assertTrue(c.ok)
    }

    @Test
    fun aFewEdgePixelsApartAreStillOk() {
        // One edge column of the line falls below the threshold, as fp16 weights may make it.
        val other = map(64, 64) { x, y -> if (x in 8 until 55 && y in 10 until 20) 0.95f else if (x == 55 && y in 10 until 20) 0.2f else 0.02f }
        val c = DetectorParity.compare(line, other, SmartCrop.lines(line, 64, 64), SmartCrop.lines(other, 64, 64))
        assertTrue(c.maxDiff > 0.7)
        assertTrue(c.meanDiff < 0.005)
        assertTrue(c.textIou > 0.95)
        assertTrue(c.ok)
    }

    @Test
    fun aMissingOrMovedLineFails() {
        val none = map(64, 64) { _, _ -> 0.02f }
        assertFalse(DetectorParity.compare(line, none, SmartCrop.lines(line, 64, 64), emptyList()).ok)
        val moved = map(64, 64) { x, y -> if (x in 8 until 56 && y in 30 until 40) 0.95f else 0.02f }
        val c = DetectorParity.compare(line, moved, SmartCrop.lines(line, 64, 64), SmartCrop.lines(moved, 64, 64))
        assertEquals(1, c.otherLines)
        assertEquals(0, c.matched)
        assertFalse(c.ok)
    }

    @Test
    fun aTallerGlyphFailsThoughTheLinesOverlap() {
        val a = listOf(box(0.0, 0.0, 100.0, 20.0))
        val b = listOf(box(0.0, 0.0, 100.0, 22.0))
        val c = DetectorParity.compare(line, line, a, b)
        assertEquals(1, c.matched)
        assertFalse(c.ok)
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
