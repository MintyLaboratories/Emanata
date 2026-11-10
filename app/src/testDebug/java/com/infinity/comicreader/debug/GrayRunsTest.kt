package com.infinity.comicreader.debug

import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.GrayPages
import com.infinity.comicreader.ai.ModelCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `gray` run's screentone page and color measures ([GrayRuns]). */
class GrayRunsTest {
    private fun rgb(r: Int, g: Int, b: Int, a: Int = 0xFF) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int) = rgb(v, v, v)

    @Test
    fun screentoneIsGrayOpaqueAndTheSameEachTime() {
        val px = GrayRuns.screentone(400, 600)
        assertEquals(400 * 600, px.size)
        assertTrue(px.all { GrayPages.spreadOf(it) == 0 })
        assertTrue(px.all { it ushr 24 == 0xFF })
        assertArrayEquals(px, GrayRuns.screentone(400, 600))
    }

    @Test
    fun screentoneHoldsPaperInkAndSoftDotEdges() {
        val w = 400
        val h = 600
        val levels = GrayRuns.screentone(w, h).map { it and 0xFF }
        // Paper with grain, solid ink, and the edges of dots in between.
        assertTrue(levels.any { it >= 252 })
        assertTrue(levels.any { it == 0 })
        assertTrue(levels.count { it in 40..215 } > w * h / 50)
        // The margin is paper.
        assertTrue((0 until w).all { levels[it] >= 252 })
    }

    @Test
    fun screentoneDotsDarkenAlongTheGradedScreens() {
        val w = 600
        val h = 900
        val px = GrayRuns.screentone(w, h)
        fun mean(x0: Int, x1: Int, y0: Int, y1: Int): Double {
            var sum = 0L
            for (y in y0 until y1) for (x in x0 until x1) sum += px[y * w + x] and 0xFF
            return sum.toDouble() / ((x1 - x0) * (y1 - y0))
        }
        // Top panel: light on the left, dark on the right.
        assertTrue(mean(60, 120, 80, 200) > mean(480, 540, 80, 200) + 100)
        // Bottom panel: light at its top, dark at its bottom.
        assertTrue(mean(100, 500, 640, 680) > mean(100, 500, 820, 860) + 80)
    }

    @Test
    fun screentoneOfTinyOrEmptySizes() {
        assertEquals(0, GrayRuns.screentone(0, 10).size)
        assertEquals(0, GrayRuns.screentone(10, -1).size)
        assertTrue(GrayRuns.screentone(1, 1).all { GrayPages.spreadOf(it) == 0 })
        assertTrue(GrayRuns.screentone(7, 3).all { GrayPages.spreadOf(it) == 0 })
    }

    @Test
    fun grayPixelsHaveNoSpread() {
        val s = GrayRuns.spread(IntArray(12) { gray(it * 20) }, 4)
        assertEquals(GrayRuns.Spread(0, 0.0, 0, 0, -1, -1), s)
        assertEquals(GrayRuns.Spread(0, 0.0, 0, 0, -1, -1), GrayRuns.spread(IntArray(0), 4))
    }

    @Test
    fun spreadFindsTheMostColoredPixelAndCountsTheRest() {
        val px = IntArray(12) { gray(128) }
        px[1] = rgb(100, 102, 101) // 2: not over 2
        px[5] = rgb(10, 13, 10) // 3
        px[6] = rgb(200, 100, 150) // 100, the most: x 2, y 1 in rows 4 wide
        px[11] = rgb(0, 0, 17) // 17
        px[9] = rgb(0, 100, 0) // 100 again, later: the first stays
        val s = GrayRuns.spread(px, 4)
        assertEquals(100, s.max)
        assertEquals(2, s.atX)
        assertEquals(1, s.atY)
        assertEquals(4, s.over2)
        assertEquals(3, s.over16)
        assertEquals((2 + 3 + 100 + 17 + 100) / 12.0, s.mean, 1e-9)
    }

    @Test
    fun spreadIgnoresAlpha() {
        val s = GrayRuns.spread(intArrayOf(rgb(50, 50, 50, 0), rgb(9, 9, 9, 0x80)), 2)
        assertEquals(0, s.max)
    }

    @Test
    fun lumaDiffIsZeroForTheKotlinCollapse() {
        val raw = intArrayOf(rgb(200, 100, 150), rgb(0, 0, 255), gray(77), rgb(255, 255, 0))
        val collapsed = raw.map(GrayPages::luma).toIntArray()
        assertEquals(0, GrayRuns.lumaDiff(raw, collapsed))
        assertEquals(0, GrayRuns.spread(collapsed, 2).max)
    }

    @Test
    fun lumaDiffMeasuresTheWorstChannel() {
        val raw = intArrayOf(gray(100), rgb(200, 100, 150))
        val l = GrayPages.luma(raw[1]) and 0xFF
        // One step off on one channel, and a pixel left colored.
        val collapsed = intArrayOf(rgb(100, 100, 101), rgb(l, l, l))
        assertEquals(1, GrayRuns.lumaDiff(raw, collapsed))
        assertEquals(50, GrayRuns.lumaDiff(intArrayOf(rgb(200, 100, 150)), intArrayOf(rgb(l, l + 50, l))))
        assertEquals(255, GrayRuns.lumaDiff(raw, IntArray(1)))
    }

    @Test
    fun defaultModelsAreTheCatalogsDescreenModels() {
        val descreen = ModelCatalog.all.filter { it.step == AiStep.DESCREEN }.map { it.id }
        assertEquals(descreen.sorted(), GrayRuns.DESCREEN_IDS.sorted())
    }
}
