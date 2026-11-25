package minty.emanata.data

import minty.emanata.ai.ScoreMap
import minty.emanata.reader.Box
import minty.emanata.reader.Wraparound
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTextTest {

    /** A [w] x [h] map, 0 but for [rects] (`[left, top, right, bottom]`, right and bottom exclusive) at [score]. */
    private fun map(w: Int, h: Int, vararg rects: IntArray, score: Float = 0.9f): ScoreMap {
        val v = FloatArray(w * h)
        for (r in rects) for (y in r[1] until r[3]) for (x in r[0] until r[2]) v[y * w + x] = score
        return ScoreMap(w, h, v)
    }

    @Test
    fun inputIsAtMostTheSideInMultiplesOfTheStride() {
        assertArrayEquals(intArrayOf(416, 640), CoverText.inputSize(2000, 3000))
        assertArrayEquals(intArrayOf(640, 320), CoverText.inputSize(4000, 2000))
        // Small covers are not enlarged, only rounded; never below one stride.
        assertArrayEquals(intArrayOf(96, 160), CoverText.inputSize(100, 150))
        assertArrayEquals(intArrayOf(32, 32), CoverText.inputSize(5, 5))
    }

    @Test
    fun boxesAreConnectedRegionsGrownLikeDbUnclip() {
        val boxes = CoverText.boxes(map(100, 100, intArrayOf(10, 20, 50, 30), intArrayOf(60, 70, 64, 74)))
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
        val boxes = CoverText.boxes(map(20, 20, intArrayOf(0, 0, 4, 4), intArrayOf(4, 4, 8, 8)))
        val b = boxes.single()
        assertEquals(0.0, b.left, 1e-9)
        assertEquals(0.0, b.top, 1e-9)
    }

    @Test
    fun linesAreBoxesInThePixelsOfTheImageSearched() {
        val m = map(100, 100, intArrayOf(10, 20, 50, 30))
        assertEquals(CoverText.boxes(m), CoverText.lines(m, 100, 100))
        // A map at half the image's size: its boxes twice as large.
        val line = CoverText.lines(m, 200, 300).single()
        assertEquals(8.0, line.left, 1e-9)
        assertEquals(42.0, line.top, 1e-9)
        assertEquals(112.0, line.right, 1e-9)
        assertEquals(108.0, line.bottom, 1e-9)
        // No text, and no map where the detector never runs.
        assertTrue(CoverText.lines(ScoreMap(0, 0, FloatArray(0)), 96, 160).isEmpty())
    }

    @Test
    fun theVersionNamesTheRulesAndTheDetector() {
        assertEquals("${CoverText.RULES}-a", CoverText.version("a"))
        assertTrue(CoverText.version("a") != CoverText.version("b"))
    }

    @Test
    fun faintThinAndBelowThresholdRegionsAreNotText() {
        assertTrue(CoverText.boxes(map(50, 50, intArrayOf(10, 10, 30, 20), score = 0.5f)).isEmpty())
        assertTrue(CoverText.boxes(map(50, 50, intArrayOf(10, 10, 30, 12))).isEmpty())
        assertTrue(CoverText.boxes(map(50, 50, intArrayOf(10, 10, 30, 20), score = 0.2f)).isEmpty())
    }

    @Test
    fun theTallestTextsLineIsTheFocus() {
        val boxes = listOf(
            // A title of two words on one line, a second title line, a small credit.
            CoverText.TextBox(10.0, 300.0, 100.0, 340.0, 0.9f),
            CoverText.TextBox(110.0, 302.0, 190.0, 338.0, 0.9f),
            CoverText.TextBox(20.0, 350.0, 180.0, 380.0, 0.9f),
            CoverText.TextBox(20.0, 20.0, 120.0, 30.0, 0.9f)
        )
        val f = CoverText.focus(boxes, 200, 400)
        val title = f.title!!
        assertEquals(0.05, title.left, 1e-9)
        assertEquals(0.75, title.top, 1e-9)
        assertEquals(0.95, title.right, 1e-9)
        assertEquals(0.85, title.bottom, 1e-9)
        assertEquals(0.2, f.glyph, 1e-9)
    }

    @Test
    fun smallTextAloneIsNoFocus() {
        val f = CoverText.focus(listOf(CoverText.TextBox(20.0, 20.0, 120.0, 24.0, 0.9f)), 200, 400)
        assertNull(f.title)
        assertEquals(CoverFocus.NONE, f)
    }

    @Test
    fun verticalTextCountsByItsWidth() {
        // A tall, narrow line is one of vertical text: its letters are as large as it is wide.
        val boxes = listOf(CoverText.TextBox(150.0, 10.0, 190.0, 390.0, 0.9f), CoverText.TextBox(10.0, 380.0, 140.0, 395.0, 0.9f))
        assertEquals(0.75, CoverText.focus(boxes, 200, 400).title!!.left, 1e-9)
    }

    @Test
    fun aWraparoundsFrontByTextIsTheHalfWithClearlyTallerLetters() {
        val none = CoverFocus.NONE
        fun titled(h: Double) = CoverFocus(Box(0.1, 0.1, 0.9, 0.1 + h))
        assertEquals(Wraparound.Half.LEFT, CoverText.textFront(titled(0.1), 600, none, 600, 1000))
        assertEquals(Wraparound.Half.RIGHT, CoverText.textFront(titled(0.1), 600, titled(0.13), 600, 1000))
        // Too close to tell, or no title either side.
        assertNull(CoverText.textFront(titled(0.1), 600, titled(0.12), 600, 1000))
        assertNull(CoverText.textFront(none, 600, none, 600, 1000))
    }

    @Test
    fun aWraparoundsFrontGoesByTheTitlesLettersNotItsBox() {
        // Left: a turned title, its box far taller than its letters. Right: an upright one with larger letters.
        val turned = CoverFocus(Box(0.1, 0.1, 0.9, 0.4), glyph = 0.1)
        val upright = CoverFocus(Box(0.1, 0.1, 0.9, 0.25), glyph = 0.15)
        assertEquals(Wraparound.Half.RIGHT, CoverText.textFront(turned, 600, upright, 600, 1000))
        // By their boxes alone the turned one would win.
        val byBox = { f: CoverFocus -> CoverFocus(f.title) }
        assertEquals(Wraparound.Half.LEFT, CoverText.textFront(byBox(turned), 600, byBox(upright), 600, 1000))
    }

    @Test
    fun aFocusHoldsItsTitlesLetterSize() {
        val title = CoverText.TextBox(0.0, 0.0, 100.0, 30.0, 0.9f)
        val tall = CoverText.TextBox(0.0, 60.0, 24.0, 140.0, 0.9f)
        assertEquals(0.3, CoverText.focus(listOf(title, tall), 100, 150).glyph, 1e-9)
        assertEquals(0.24, CoverText.focus(listOf(tall), 100, 150).glyph, 1e-9)
        assertEquals(0.0, CoverText.focus(emptyList(), 100, 150).glyph, 0.0)
    }

    @Test
    fun focusSurvivesEncoding() {
        val f = CoverFocus(Box(0.1, 0.2, 0.3, 0.4), glyph = 0.1234)
        val back = CoverFocus.decode(f.encode())
        assertNotNull(back)
        assertEquals(0.1234, back!!.glyph, 0.0)
        assertEquals(f.title, back.title)
        assertEquals(f, back)
        assertEquals(CoverFocus.NONE, CoverFocus.decode(CoverFocus.NONE.encode()))
        assertTrue(CoverFocus.decode(CoverFocus.NONE.encode())!!.keep)
        assertNull(CoverFocus.decode("nonsense"))
        assertNull(CoverFocus.decode("a,b;0.1"))
        assertNull(CoverFocus.decode(";0.1"))
        assertNull(CoverFocus.decode(f.encode().substringBeforeLast(';') + ";x"))
        // A focus kept under the earlier rules, with each line and the detail, is not one.
        assertNull(CoverFocus.decode("0.1000,0.2000,0.3000,0.4000;0.1000,0.2000,0.3000,0.4000;00ff;00ff;0.1234"))
    }
}
