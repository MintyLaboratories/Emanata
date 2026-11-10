package com.infinity.comicreader.ai

import com.infinity.comicreader.data.SmartCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MlKitLinesTest {
    private val eps = 1e-9

    /**
     * A line [length] along its text and [thick] across it, centered on ([cx], [cy]) and turned
     * [degrees] clockwise: its corners clockwise from its text's top left, as ML Kit gives them.
     */
    private fun line(cx: Double, cy: Double, length: Double, thick: Double, degrees: Double = 0.0, confidence: Float = 0.9f): FoundLine {
        val a = Math.toRadians(degrees)
        val ux = cos(a) to sin(a)          // along the text
        val vx = -sin(a) to cos(a)         // down across it
        fun at(s: Double, t: Double) = Corner(cx + s * ux.first + t * vx.first, cy + s * ux.second + t * vx.second)
        val c = listOf(at(-length / 2, -thick / 2), at(length / 2, -thick / 2), at(length / 2, thick / 2), at(-length / 2, thick / 2))
        return FoundLine(c, c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y }, confidence)
    }

    @Test
    fun noLinesNoBoxes() {
        assertTrue(MlKitLines.boxes(emptyList(), 400, 600).isEmpty())
    }

    @Test
    fun anUprightLineIsItsBoxAndItsHeightItsGlyph() {
        val b = MlKitLines.box(line(200.0, 100.0, 300.0, 60.0), 400, 600)!!
        assertEquals(50.0, b.left, eps)
        assertEquals(70.0, b.top, eps)
        assertEquals(350.0, b.right, eps)
        assertEquals(130.0, b.bottom, eps)
        assertEquals(60.0, b.glyph, eps)
        assertEquals(0.9f, b.score)
    }

    @Test
    fun aRotatedLineKeepsItsLettersSizeNotItsBoxes() {
        val b = MlKitLines.box(line(200.0, 300.0, 200.0, 40.0, degrees = 30.0), 400, 600)!!
        // The box around it is far thicker than its letters.
        assertTrue(min(b.width, b.height) > 100)
        assertEquals(40.0, b.glyph, 1e-6)
        assertEquals(200.0 * cos(Math.toRadians(30.0)) + 40.0 * sin(Math.toRadians(30.0)), b.width, 1e-6)
    }

    @Test
    fun aLineReadUpwardsIsAsThickAsItIsWide() {
        // Turned a quarter counterclockwise: short and wide in the image, its letters as wide as the box.
        val b = MlKitLines.box(line(200.0, 300.0, 60.0, 80.0, degrees = -90.0), 400, 600)!!
        assertEquals(80.0, b.width, 1e-6)
        assertEquals(60.0, b.height, 1e-6)
        assertEquals(80.0, b.glyph, 1e-6)
    }

    @Test
    fun aRotatedLineTooThinForATitleMovesNoCrop() {
        // On a 400 x 600 cover a title's letters are at least MIN_GLYPH * 400 = 48 tall; this line's box is far taller.
        val boxes = MlKitLines.boxes(listOf(line(200.0, 300.0, 200.0, 40.0, degrees = 30.0)), 400, 600)
        val focus = SmartCrop.focus(boxes, 400, 600, FloatArray(0), FloatArray(0))
        assertNull(focus.title)
    }

    @Test
    fun theThickestRotatedLineIsTheTitle() {
        val credits = line(200.0, 550.0, 300.0, 30.0)
        val title = line(200.0, 150.0, 250.0, 70.0, degrees = -12.0)
        val subtitle = line(200.0, 260.0, 380.0, 52.0, degrees = 5.0)
        val boxes = MlKitLines.boxes(listOf(credits, title, subtitle), 400, 600)
        val focus = SmartCrop.focus(boxes, 400, 600, FloatArray(0), FloatArray(0))
        val t = focus.title
        assertNotNull(t)
        val b = boxes[1]
        assertEquals(b.top / 600, t!!.top, eps)
        assertEquals(b.bottom / 600, t.bottom, eps)
        // The subtitle is at least SAME_SIZE of the title's letters: kept with it.
        assertEquals(2, focus.lines.size)
    }

    @Test
    fun withoutCornersItsBoundsServeAndTheBoxRuleSizesIt() {
        val b = MlKitLines.box(FoundLine(null, 10.0, 20.0, 50.0, 140.0, 0.5f), 400, 600)!!
        assertEquals(SmartCrop.TextBox(10.0, 20.0, 50.0, 140.0, 0.5f), b)
        assertEquals(40.0, b.glyph, eps)
    }

    @Test
    fun cornersOtherThanFourOrNotFiniteFallBackToTheBounds() {
        val three = FoundLine(listOf(Corner(0.0, 0.0), Corner(9.0, 0.0), Corner(9.0, 9.0)), 1.0, 2.0, 30.0, 12.0, 0.5f)
        assertEquals(SmartCrop.TextBox(1.0, 2.0, 30.0, 12.0, 0.5f), MlKitLines.box(three, 400, 600))
        val nan = line(100.0, 100.0, 50.0, 10.0).let { it.copy(corners = it.corners!!.mapIndexed { i, c -> if (i == 2) Corner(Double.NaN, c.y) else c }) }
        assertEquals(SmartCrop.TextBox(75.0, 95.0, 125.0, 105.0, 0.9f), MlKitLines.box(nan, 400, 600))
        assertNull(MlKitLines.box(FoundLine(null, Double.NaN, 0.0, 10.0, 10.0, 0.5f), 400, 600))
    }

    @Test
    fun aLinePartlyOffTheImageIsClampedToItAndKeepsItsThickness() {
        val b = MlKitLines.box(line(20.0, 10.0, 100.0, 40.0), 400, 600)!!
        assertEquals(0.0, b.left, eps)
        assertEquals(0.0, b.top, eps)
        assertEquals(70.0, b.right, eps)
        assertEquals(30.0, b.bottom, eps)
        assertEquals(40.0, b.glyph, eps)
    }

    @Test
    fun linesOffTheImageOrWithoutThicknessAreLeftOut() {
        val off = line(500.0, 100.0, 100.0, 40.0)
        val flat = line(200.0, 100.0, 100.0, 0.0)
        val kept = line(200.0, 300.0, 100.0, 40.0)
        val boxes = MlKitLines.boxes(listOf(off, flat, kept), 400, 600)
        assertEquals(1, boxes.size)
        assertEquals(150.0, boxes[0].left, eps)
    }

    @Test
    fun confidenceBecomesAScoreWithinZeroAndOne() {
        assertEquals(1f, MlKitLines.box(line(200.0, 100.0, 100.0, 40.0, confidence = 1.7f), 400, 600)!!.score)
        assertEquals(0f, MlKitLines.box(line(200.0, 100.0, 100.0, 40.0, confidence = -0.2f), 400, 600)!!.score)
        assertEquals(0f, MlKitLines.box(line(200.0, 100.0, 100.0, 40.0, confidence = Float.NaN), 400, 600)!!.score)
    }
}
