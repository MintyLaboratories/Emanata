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
    fun anUprightLineIsItsBoxGrownAndItsHeightItsGlyph() {
        val b = MlKitLines.box(line(200.0, 100.0, 300.0, 60.0), 400, 600)!!
        // 60 tall grown to 75: 7.5 more on each side.
        assertEquals(42.5, b.left, eps)
        assertEquals(62.5, b.top, eps)
        assertEquals(357.5, b.right, eps)
        assertEquals(137.5, b.bottom, eps)
        assertEquals(MlKitLines.GROW * 60.0, b.height, eps)
        assertEquals(60.0, b.glyph, eps)
        assertEquals(0.9f, b.score)
    }

    @Test
    fun growingKeepsWhatIsTitleSized() {
        // Letters just under MIN_GLYPH * 400 = 48: no title, however much its box grows.
        val under = MlKitLines.boxes(listOf(line(200.0, 100.0, 300.0, 47.9)), 400, 600)
        assertTrue(under.single().height > 48)
        assertNull(SmartCrop.focus(under, 400, 600, FloatArray(0), FloatArray(0)).title)
        val over = MlKitLines.boxes(listOf(line(200.0, 100.0, 300.0, 48.1)), 400, 600)
        val title = SmartCrop.focus(over, 400, 600, FloatArray(0), FloatArray(0)).title!!
        // The title keeps the grown box: room around its letters.
        assertEquals(over.single().top / 600, title.top, eps)
        assertTrue(title.top < (100.0 - 48.1 / 2) / 600)
    }

    @Test
    fun aRotatedLineKeepsItsLettersSizeNotItsBoxes() {
        val b = MlKitLines.box(line(200.0, 300.0, 200.0, 40.0, degrees = 30.0), 400, 600)!!
        // The box around it is far thicker than its letters.
        assertTrue(min(b.width, b.height) > 100)
        assertEquals(40.0, b.glyph, 1e-6)
        // Grown by its letters' size, not its box's: 5 more on each side.
        assertEquals(200.0 * cos(Math.toRadians(30.0)) + 40.0 * sin(Math.toRadians(30.0)) + 10.0, b.width, 1e-6)
    }

    @Test
    fun aLineReadUpwardsIsAsThickAsItIsWide() {
        // Turned a quarter counterclockwise: tall and narrow in the image, its letters as wide as the box.
        val b = MlKitLines.box(line(200.0, 300.0, 300.0, 60.0, degrees = -90.0), 400, 600)!!
        assertEquals(75.0, b.width, 1e-6)
        assertEquals(315.0, b.height, 1e-6)
        assertEquals(60.0, b.glyph, 1e-6)
    }

    @Test
    fun theThicknessDoesNotDependOnTheCornerTheCornersStartFrom() {
        for (degrees in listOf(0.0, 30.0, -90.0, 90.0, 135.0)) {
            val l = line(200.0, 300.0, 300.0, 40.0, degrees = degrees)
            for (k in 0 until 4) {
                val turned = l.copy(corners = l.corners!!.drop(k) + l.corners.take(k))
                assertEquals("$degrees degrees from corner $k", 40.0, MlKitLines.box(turned, 400, 600)!!.glyph, 1e-6)
            }
        }
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
    fun thicknessNotBoxSizePicksTheTitle() {
        // An upright title, and a thin line turned 45 degrees whose box is far taller than the title's.
        val title = line(200.0, 100.0, 300.0, 60.0)
        val turned = line(200.0, 400.0, 300.0, 50.0, degrees = 45.0)
        val boxes = MlKitLines.boxes(listOf(turned, title), 400, 600)
        assertTrue(min(boxes[0].width, boxes[0].height) > 3 * boxes[1].glyph)
        val focus = SmartCrop.focus(boxes, 400, 600, FloatArray(0), FloatArray(0))
        assertEquals(boxes[1].top / 600, focus.title!!.top, eps)
        // Its letters are near the title's size: it stays with it.
        assertEquals(2, focus.lines.size)
        // By the box rule alone the turned line would be the title.
        val byBox = SmartCrop.focus(boxes.map { it.copy(thickness = null) }, 400, 600, FloatArray(0), FloatArray(0))
        assertEquals(boxes[0].top / 600, byBox.title!!.top, eps)
    }

    @Test
    fun withoutCornersItsBoundsServeAndTheBoxRuleSizesIt() {
        val b = MlKitLines.box(FoundLine(null, 10.0, 20.0, 50.0, 140.0, 0.5f), 400, 600)!!
        assertEquals(SmartCrop.TextBox(5.0, 15.0, 55.0, 145.0, 0.5f, 40.0), b)
        assertEquals(40.0, b.glyph, eps)
        // The bounds within the image size it.
        assertEquals(30.0, MlKitLines.box(FoundLine(null, -10.0, 20.0, 30.0, 140.0, 0.5f), 400, 600)!!.glyph, eps)
    }

    @Test
    fun cornersOtherThanFourOrNotFiniteFallBackToTheBounds() {
        val three = FoundLine(listOf(Corner(0.0, 0.0), Corner(9.0, 0.0), Corner(9.0, 9.0)), 1.0, 2.0, 30.0, 12.0, 0.5f)
        assertEquals(SmartCrop.TextBox(0.0, 0.75, 31.25, 13.25, 0.5f, 10.0), MlKitLines.box(three, 400, 600))
        val nan = line(100.0, 100.0, 50.0, 10.0).let { it.copy(corners = it.corners!!.mapIndexed { i, c -> if (i == 2) Corner(Double.NaN, c.y) else c }) }
        assertEquals(SmartCrop.TextBox(73.75, 93.75, 126.25, 106.25, 0.9f, 10.0), MlKitLines.box(nan, 400, 600))
        assertNull(MlKitLines.box(FoundLine(null, Double.NaN, 0.0, 10.0, 10.0, 0.5f), 400, 600))
    }

    @Test
    fun aLinePartlyOffTheImageIsClampedToItAndKeepsItsThickness() {
        val b = MlKitLines.box(line(20.0, 10.0, 100.0, 40.0), 400, 600)!!
        assertEquals(0.0, b.left, eps)
        assertEquals(0.0, b.top, eps)
        assertEquals(75.0, b.right, eps)
        assertEquals(35.0, b.bottom, eps)
        assertEquals(40.0, b.glyph, eps)
    }

    @Test
    fun linesOffTheImageOrWithoutThicknessAreLeftOut() {
        val off = line(500.0, 100.0, 100.0, 40.0)
        val flat = line(200.0, 100.0, 100.0, 0.0)
        val kept = line(200.0, 300.0, 100.0, 40.0)
        val boxes = MlKitLines.boxes(listOf(off, flat, kept), 400, 600)
        assertEquals(1, boxes.size)
        assertEquals(145.0, boxes[0].left, eps)
    }

    @Test
    fun confidenceBecomesAScoreWithinZeroAndOne() {
        assertEquals(1f, MlKitLines.box(line(200.0, 100.0, 100.0, 40.0, confidence = 1.7f), 400, 600)!!.score)
        assertEquals(0f, MlKitLines.box(line(200.0, 100.0, 100.0, 40.0, confidence = -0.2f), 400, 600)!!.score)
        assertEquals(0f, MlKitLines.box(line(200.0, 100.0, 100.0, 40.0, confidence = Float.NaN), 400, 600)!!.score)
    }
}
