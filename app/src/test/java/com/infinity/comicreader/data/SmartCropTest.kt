package com.infinity.comicreader.data

import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.reader.Box
import com.infinity.comicreader.reader.Wraparound
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCropTest {

    /** A [w] x [h] map, 0 but for [rects] (`[left, top, right, bottom]`, right and bottom exclusive) at [score]. */
    private fun map(w: Int, h: Int, vararg rects: IntArray, score: Float = 0.9f): ScoreMap {
        val v = FloatArray(w * h)
        for (r in rects) for (y in r[1] until r[3]) for (x in r[0] until r[2]) v[y * w + x] = score
        return ScoreMap(w, h, v)
    }

    private fun flat(n: Int = SmartCrop.BINS) = FloatArray(n) { 1f / n }

    @Test
    fun inputIsAtMostTheSideInMultiplesOfTheStride() {
        assertArrayEquals(intArrayOf(416, 640), SmartCrop.inputSize(2000, 3000))
        assertArrayEquals(intArrayOf(640, 320), SmartCrop.inputSize(4000, 2000))
        // Small covers are not enlarged, only rounded; never below one stride.
        assertArrayEquals(intArrayOf(96, 160), SmartCrop.inputSize(100, 150))
        assertArrayEquals(intArrayOf(32, 32), SmartCrop.inputSize(5, 5))
    }

    @Test
    fun boxesAreConnectedRegionsGrownLikeDbUnclip() {
        val boxes = SmartCrop.boxes(map(100, 100, intArrayOf(10, 20, 50, 30), intArrayOf(60, 70, 64, 74)))
        assertEquals(2, boxes.size)
        val line = boxes.single { it.top < 50 }
        // 40 x 10: grows by 1.5 * 400 / 100 = 6 on each side.
        assertEquals(4.0, line.left, 1e-9)
        assertEquals(14.0, line.top, 1e-9)
        assertEquals(56.0, line.right, 1e-9)
        assertEquals(36.0, line.bottom, 1e-9)
        assertEquals(22.0, line.glyph, 1e-9)
    }

    @Test
    fun diagonalNeighborsJoinAndGrowthStopsAtTheEdges() {
        val boxes = SmartCrop.boxes(map(20, 20, intArrayOf(0, 0, 4, 4), intArrayOf(4, 4, 8, 8)))
        val b = boxes.single()
        assertEquals(0.0, b.left, 1e-9)
        assertEquals(0.0, b.top, 1e-9)
    }

    @Test
    fun linesAreBoxesInThePixelsOfTheImageSearched() {
        val m = map(100, 100, intArrayOf(10, 20, 50, 30))
        assertEquals(SmartCrop.boxes(m), SmartCrop.lines(m, 100, 100))
        // A map at half the image's size: its boxes twice as large.
        val line = SmartCrop.lines(m, 200, 300).single()
        assertEquals(8.0, line.left, 1e-9)
        assertEquals(42.0, line.top, 1e-9)
        assertEquals(112.0, line.right, 1e-9)
        assertEquals(108.0, line.bottom, 1e-9)
        // No text, and no map where the detector never runs.
        assertTrue(SmartCrop.lines(ScoreMap(0, 0, FloatArray(0)), 96, 160).isEmpty())
    }

    @Test
    fun theVersionNamesTheRulesAndTheDetector() {
        assertEquals("${SmartCrop.RULES}-a", SmartCrop.version("a"))
        assertTrue(SmartCrop.version("a") != SmartCrop.version("b"))
    }

    @Test
    fun faintThinAndBelowThresholdRegionsAreNotText() {
        assertTrue(SmartCrop.boxes(map(50, 50, intArrayOf(10, 10, 30, 20), score = 0.5f)).isEmpty())
        assertTrue(SmartCrop.boxes(map(50, 50, intArrayOf(10, 10, 30, 12))).isEmpty())
        assertTrue(SmartCrop.boxes(map(50, 50, intArrayOf(10, 10, 30, 20), score = 0.2f)).isEmpty())
    }

    @Test
    fun theTallestTextAndLinesOfItsSizeAreTheFocus() {
        val boxes = listOf(
            // A title of two words on one line, a second title line, a small credit.
            SmartCrop.TextBox(10.0, 300.0, 100.0, 340.0, 0.9f),
            SmartCrop.TextBox(110.0, 302.0, 190.0, 338.0, 0.9f),
            SmartCrop.TextBox(20.0, 350.0, 180.0, 380.0, 0.9f),
            SmartCrop.TextBox(20.0, 20.0, 120.0, 30.0, 0.9f)
        )
        val f = SmartCrop.focus(boxes, 200, 400, flat(), flat())
        val title = f.title!!
        assertEquals(0.05, title.left, 1e-9)
        assertEquals(0.75, title.top, 1e-9)
        assertEquals(0.95, title.right, 1e-9)
        assertEquals(0.85, title.bottom, 1e-9)
        assertEquals(3, f.lines.size)
        val text = f.text!!
        assertEquals(0.75, text.top, 1e-9)
        assertEquals(0.95, text.bottom, 1e-9)
    }

    @Test
    fun smallTextAloneIsNoFocus() {
        val f = SmartCrop.focus(listOf(SmartCrop.TextBox(20.0, 20.0, 120.0, 24.0, 0.9f)), 200, 400, flat(), flat())
        assertNull(f.title)
        assertNull(f.text)
    }

    @Test
    fun verticalTextCountsByItsWidth() {
        // A tall, narrow line is one of vertical text: its letters are as large as it is wide.
        val boxes = listOf(SmartCrop.TextBox(150.0, 10.0, 190.0, 390.0, 0.9f), SmartCrop.TextBox(10.0, 380.0, 140.0, 395.0, 0.9f))
        assertEquals(0.75, SmartCrop.focus(boxes, 200, 400, flat(), flat()).title!!.left, 1e-9)
    }

    private fun focus(title: Box?, vararg others: Box, rows: FloatArray = flat(), columns: FloatArray = flat()) =
        CoverFocus(title, listOfNotNull(title) + others, rows, columns)

    @Test
    fun titleAtTheTopKeepsTheTop() {
        assertEquals(0.0, SmartCrop.offset(focus(Box(0.1, 0.05, 0.9, 0.2)), 1500.0, 1000.0, vertical = true), 1e-9)
    }

    @Test
    fun titleBelowTheTopWindowMovesItJustEnough() {
        // Title from 1200 to 1350 of 1500, a 1000 span: it ends 20 (2% of the span) above the crop's bottom.
        val y = SmartCrop.offset(focus(Box(0.1, 0.8, 0.9, 0.9)), 1500.0, 1000.0, vertical = true)
        assertEquals(370.0, y, 1e-9)
    }

    @Test
    fun aTitleTooTallWithItsPeersKeepsItsOwnLine() {
        // Lines from 100 to 1350 cannot all stay in 1000; the title line (1200..1350) does.
        val y = SmartCrop.offset(focus(Box(0.1, 0.8, 0.9, 0.9), Box(0.0, 1 / 15.0, 0.05, 1100 / 1500.0)), 1500.0, 1000.0, vertical = true)
        assertEquals(370.0, y, 1e-9)
    }

    @Test
    fun aLineNearlyAsTallInTheTopCropDoesNotHoldItFromTheTallest() {
        // One nearly as tall sits in the top crop; the tallest, low, cannot stay with it.
        val y = SmartCrop.offset(focus(Box(0.1, 0.8, 0.9, 0.9), Box(0.1, 0.05, 0.9, 0.13)), 1500.0, 1000.0, vertical = true)
        assertEquals(370.0, y, 1e-9)
        // When both fit, both stay.
        val both = SmartCrop.offset(focus(Box(0.1, 0.8, 0.9, 0.9), Box(0.1, 0.3, 0.9, 0.38)), 1500.0, 1000.0, vertical = true)
        assertEquals(370.0, both, 1e-9)
        assertTrue(both <= 0.3 * 1500 - 20)
    }

    @Test
    fun theTopStaysWhenItKeepsTheTallestLine() {
        // A peer below is lost, but the tallest's line is whole.
        val y = SmartCrop.offset(focus(Box(0.1, 0.05, 0.9, 0.15), Box(0.1, 0.8, 0.9, 0.88)), 1500.0, 1000.0, vertical = true)
        assertEquals(0.0, y, 1e-9)
    }

    @Test
    fun aTitleLargerThanTheCropKeepsItsMiddle() {
        val y = SmartCrop.offset(focus(Box(0.0, 0.2, 1.0, 0.95)), 1500.0, 1000.0, vertical = true)
        assertEquals(((0.2 + 0.95) * 1500 - 1000) / 2, y, 1e-9)
    }

    @Test
    fun acrossTheCropStaysCenteredWhereTheTitleAllows() {
        assertEquals(250.0, SmartCrop.offset(focus(Box(0.3, 0.1, 0.7, 0.2)), 1500.0, 1000.0, vertical = false), 1e-9)
        // A title on the right moves the crop right, as little as keeps it whole.
        assertEquals(490.0, SmartCrop.offset(focus(Box(0.7, 0.1, 0.98, 0.2)), 1500.0, 1000.0, vertical = false), 1e-9)
    }

    @Test
    fun withoutTextTheMostDetailedPartWinsWhenClearlyMore() {
        val rows = FloatArray(SmartCrop.BINS).also { for (i in 24 until 32) it[i] = 1f / 8 }
        assertEquals(500.0, SmartCrop.offset(focus(null, rows = rows), 1500.0, 1000.0, vertical = true), 1e-9)
        // About even detail keeps the top.
        val even = FloatArray(SmartCrop.BINS) { if (it >= 16) 1.05f else 1f }.let { v -> v.map { it / v.sum() }.toFloatArray() }
        assertEquals(0.0, SmartCrop.offset(focus(null, rows = even), 1500.0, 1000.0, vertical = true), 1e-9)
        // A blank cover keeps the top, and stays centered across.
        assertEquals(0.0, SmartCrop.offset(CoverFocus.NONE, 1500.0, 1000.0, vertical = true), 1e-9)
        assertEquals(250.0, SmartCrop.offset(CoverFocus.NONE, 1500.0, 1000.0, vertical = false), 1e-9)
    }

    @Test
    fun aWraparoundsFrontByTextIsTheHalfWithClearlyTallerLetters() {
        val none = CoverFocus.NONE
        fun titled(h: Double) = focus(Box(0.1, 0.1, 0.9, 0.1 + h))
        assertEquals(Wraparound.Half.LEFT, SmartCrop.textFront(titled(0.1), 600, none, 600, 1000))
        assertEquals(Wraparound.Half.RIGHT, SmartCrop.textFront(titled(0.1), 600, titled(0.13), 600, 1000))
        // Too close to tell, or no title either side.
        assertNull(SmartCrop.textFront(titled(0.1), 600, titled(0.12), 600, 1000))
        assertNull(SmartCrop.textFront(none, 600, none, 600, 1000))
    }

    @Test
    fun aWraparoundsFrontGoesByTheTitlesLettersNotItsBox() {
        // Left: a turned title, its box far taller than its letters. Right: an upright one with larger letters.
        val turned = CoverFocus(Box(0.1, 0.1, 0.9, 0.4), listOf(Box(0.1, 0.1, 0.9, 0.4)), flat(), flat(), glyph = 0.1)
        val upright = CoverFocus(Box(0.1, 0.1, 0.9, 0.25), listOf(Box(0.1, 0.1, 0.9, 0.25)), flat(), flat(), glyph = 0.15)
        assertEquals(Wraparound.Half.RIGHT, SmartCrop.textFront(turned, 600, upright, 600, 1000))
        // By their boxes alone the turned one would win.
        val byBox = { f: CoverFocus -> CoverFocus(f.title, f.lines, f.rows, f.columns) }
        assertEquals(Wraparound.Half.LEFT, SmartCrop.textFront(byBox(turned), 600, byBox(upright), 600, 1000))
    }

    @Test
    fun aFocusHoldsItsTitlesLetterSize() {
        val title = SmartCrop.TextBox(0.0, 0.0, 100.0, 30.0, 0.9f)
        val turned = SmartCrop.TextBox(0.0, 60.0, 100.0, 140.0, 0.9f, thickness = 24.0)
        assertEquals(0.3, SmartCrop.focus(listOf(title, turned), 100, 150, flat(), flat()).glyph, 1e-9)
        assertEquals(0.24, SmartCrop.focus(listOf(turned), 100, 150, flat(), flat()).glyph, 1e-9)
        assertEquals(0.0, SmartCrop.focus(emptyList(), 100, 150, flat(), flat()).glyph, 0.0)
    }

    @Test
    fun noCropNoOffset() {
        assertEquals(0.0, SmartCrop.offset(focus(Box(0.0, 0.8, 1.0, 0.9)), 1000.0, 1000.0, vertical = true), 1e-9)
    }

    @Test
    fun withinSumsPartialBins() {
        val p = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)
        assertEquals(1.0, SmartCrop.within(p, 0.0, 1.0), 1e-6)
        assertEquals(0.375, SmartCrop.within(p, 0.125, 0.5), 1e-6)
    }

    @Test
    fun detailFollowsEdges() {
        // Flat top half, striped bottom half.
        val w = 64
        val h = 64
        val luma = ByteArray(w * h) { i -> if (i / w >= 32 && (i % w) % 2 == 0) 200.toByte() else 0 }
        val (rows, columns) = SmartCrop.detail(luma, w, h).let { it[0] to it[1] }
        assertEquals(1.0, rows.sum().toDouble(), 1e-4)
        assertEquals(1.0, columns.sum().toDouble(), 1e-4)
        assertTrue(rows.take(SmartCrop.BINS / 2 - 1).all { it == 0f })
        assertTrue(rows.drop(SmartCrop.BINS / 2).all { it > 0f })
        assertTrue(SmartCrop.detail(ByteArray(w * h), w, h)[0].all { it == 0f })
    }

    @Test
    fun aFocusWithTextHoldsNoDetail() {
        val box = SmartCrop.TextBox(0.0, 0.0, 100.0, 30.0, 0.9f)
        val f = SmartCrop.focus(listOf(box), 100, 150, flat(), flat())
        assertNotNull(f.title)
        assertEquals(0, f.rows.size)
        assertEquals(0, f.columns.size)
    }

    @Test
    fun focusSurvivesEncoding() {
        val f = CoverFocus(Box(0.1, 0.2, 0.3, 0.4), listOf(Box(0.1, 0.2, 0.3, 0.4), Box(0.05, 0.4, 0.95, 0.5)), flat(), flat(), glyph = 0.1234)
        val back = CoverFocus.decode(f.encode())
        assertNotNull(back)
        assertEquals(0.1234, back!!.glyph, 0.0)
        assertEquals(f.title, back.title)
        assertEquals(f.lines, back.lines)
        assertEquals(Box(0.05, 0.2, 0.95, 0.5), back.text)
        assertEquals(CoverFocus.NONE, CoverFocus.decode(CoverFocus.NONE.encode()))
        // Detail to a byte a bin, its share of the axis as before.
        val rows = FloatArray(SmartCrop.BINS) { (it + 1).toFloat() }.let { v -> FloatArray(v.size) { v[it] / v.sum() } }
        val untitled = CoverFocus(null, emptyList(), rows, FloatArray(SmartCrop.BINS))
        val line = untitled.encode()
        assertTrue(line, line.length <= 2 + 4 * SmartCrop.BINS + 2)
        val read = CoverFocus.decode(line)!!
        assertNull(read.title)
        for (i in rows.indices) assertEquals(rows[i], read.rows[i], rows[i] * 0.03f)
        assertTrue(read.columns.all { it == 0f })
        assertNull(CoverFocus.decode("nonsense"))
        assertNull(CoverFocus.decode("a,b;;;;"))
        assertNull(CoverFocus.decode(f.encode().substringBeforeLast(';') + ";x"))
    }
}
