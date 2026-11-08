package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PanelPageTest {
    private val page = PanelPage.TUNER
    private val m = 0.035
    private val g = 0.016

    private fun close(expected: Double, actual: Double, what: String = "") = assertEquals(what, expected, actual, 1e-9)

    /** The slope of the edge from [a] to [b]: dy/dx across, dx/dy upright. */
    private fun slope(a: PanelPage.Point, b: PanelPage.Point, across: Boolean) = if (across) (b.y - a.y) / (b.x - a.x) else (b.x - a.x) / (b.y - a.y)

    /** Twice the signed area: positive for corners in clockwise order on a page with y down. */
    private fun area(q: List<PanelPage.Point>) = q.indices.sumOf { i -> val a = q[i]; val b = q[(i + 1) % q.size]; a.x * b.y - b.x * a.y }

    @Test
    fun fivePanelsThreeLeftTwoRight() {
        val q = page.quads()
        assertEquals(5, q.size)
        // Left column: from the frame's left side; right column: to its right side.
        for (i in 0..2) q[i].let { close(m, it[0].x); close(m, it[3].x) }
        for (i in 3..4) q[i].let { close(1 - m, it[1].x); close(1 - m, it[2].x) }
        // Top and bottom panels touch the frame.
        for (i in listOf(0, 3)) q[i].let { close(m, it[0].y); close(m, it[1].y) }
        for (i in listOf(2, 4)) q[i].let { close(1 - m, it[2].y); close(1 - m, it[3].y) }
        for (p in q) assertTrue(area(p) > 0)
    }

    @Test
    fun cornersMatchThePrototype() {
        // Panel 1's top right corner at rest: the spine's left side x = 0.429 + 0.12 y meets the frame's top y = 0.035.
        val tr = page.quads()[0][1]
        close(0.505 - 0.12 * 0.5 - g + 0.12 * m, tr.x)
        close(m, tr.y)
    }

    @Test
    fun guttersKeepTheirWidthAndAngleWhereverTheyMove() {
        for (offsets in listOf(DoubleArray(4), doubleArrayOf(0.07, 0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.06, -0.06, 0.08))) {
            val q = page.quads(offsets)
            // The spine: panel 1's right edge and panel 4's left edge, 2 half gutters apart across, both at slope 0.12.
            close(0.12, slope(q[0][1], q[0][2], across = false))
            close(0.12, slope(q[3][0], q[3][3], across = false))
            val y = q[0][1].y
            val leftX = q[0][1].x
            val rightX = q[3][0].x + 0.12 * (y - q[3][0].y)
            close(2 * g, rightX - leftX, "spine width")
            // The left column's upper gutter: panel 1's bottom and panel 2's top, at slope 0.09, 2 half gutters apart down.
            close(0.09, slope(q[0][3], q[0][2], across = true))
            close(0.09, slope(q[1][0], q[1][1], across = true))
            val x = q[1][0].x
            val above = q[0][3].y + 0.09 * (x - q[0][3].x)
            close(2 * g, q[1][0].y - above, "gutter width")
            close(-0.11, slope(q[3][3], q[3][2], across = true))
            close(-0.07, slope(q[2][0], q[2][1], across = true))
        }
    }

    @Test
    fun aGutterMovesOnlyThePanelsBesideIt() {
        val rest = page.quads()
        val moved = page.quads(doubleArrayOf(0.0, 0.0, 0.0, 0.08))
        for (i in 0..2) assertEquals(rest[i], moved[i])
        assertTrue(moved[3][2].y > rest[3][2].y)
        assertTrue(moved[4][0].y > rest[4][0].y)
    }

    @Test
    fun aPanelNeedsLevelTopsAndUprightSides() {
        val bad = runCatching {
            PanelPage(
                listOf(PanelPage.Gutter(vertical = true, base = 0.5, slope = 0.0, amp = 0.0)),
                listOf(PanelPage.Panel(PanelPage.Edge.Of(0, 1), PanelPage.Edge.Side(PanelPage.Edge.Frame.RIGHT), PanelPage.Edge.Side(PanelPage.Edge.Frame.BOTTOM), PanelPage.Edge.Side(PanelPage.Edge.Frame.LEFT))),
                0.0, 0.0
            )
        }
        assertTrue(bad.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun oneGutterAtATimeOutHoldBackRest() {
        val motion = GutterMotion(4)
        fun shares(t: Double) = motion.shares(t).toList()
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), shares(0.0))
        // Out over 40 % of a 4 s slot, held to 55 %, back by 95 %, at rest after.
        close(0.5, shares(0.8)[0])
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0), shares(1.6))
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0), shares(2.1))
        close(0.5, shares(3.0)[0])
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), shares(3.9))
        // The next gutter's slot, and round again after the last.
        assertEquals(listOf(0.0, 1.0, 0.0, 0.0), shares(4 + 1.6))
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0), shares(12 + 2.0))
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0), shares(16 + 2.0))
        for (i in 0 until 400) assertTrue(shares(i * 0.05).count { it != 0.0 } <= 1)
        // Offsets scale by each gutter's amp, the sign included.
        close(-0.06, motion.offsets(page, 8 + 2.0)[2])
    }

    @Test
    fun easingIsCubicInAndOut() {
        close(0.0, GutterMotion.ease(0.0))
        close(0.5, GutterMotion.ease(0.5))
        close(1.0, GutterMotion.ease(1.0))
        close(4 * 0.25 * 0.25 * 0.25, GutterMotion.ease(0.25))
        close(1 - GutterMotion.ease(0.25), GutterMotion.ease(0.75))
    }

    @Test
    fun reachCoversEveryFrame() {
        val reach = page.reach()
        val motion = GutterMotion(4)
        for (i in 0 until 16 * 20) {
            val q = page.quads(motion.offsets(page, i / 20.0))
            for ((p, quad) in q.withIndex()) for (c in quad) {
                val r = reach[p]
                assertTrue(c.x >= r[0] - 1e-12 && c.y >= r[1] - 1e-12 && c.x <= r[2] + 1e-12 && c.y <= r[3] + 1e-12)
            }
        }
        // And no more than the page.
        for (r in reach) assertTrue(r[0] >= m - 1e-12 && r[1] >= m - 1e-12 && r[2] <= 1 - m + 1e-12 && r[3] <= 1 - m + 1e-12)
    }

    @Test
    fun halftoneDotsFollowTheScreenAndGrowAcrossThePanel() {
        val size = 1240
        val step = Halftone.step(size)
        close(20.0, step)
        assertEquals(6.0, Halftone.step(100), 0.0)
        val ink = PrintStyle.FOUR_COLOR.panels[1]
        val box = floatArrayOf(100f, 200f, 500f, 600f)
        val dots = Halftone.dots(size, ink, 0.25, 0.3, box)
        assertTrue(dots.size > 100)
        for (d in dots) {
            assertTrue(d.x >= box[0] - step && d.x <= box[2] + step && d.y >= box[1] - step && d.y <= box[3] + step)
            assertTrue(d.r >= step * 0.08 - 1e-3 && d.r <= step * 0.5 + 1e-3)
        }
        // Yellow's screen is at 0 degrees: dots on a square grid, a pitch apart.
        val x0 = dots[0].x
        val y0 = dots[0].y
        assertTrue(dots.all { abs((it.x - x0) / step - Math.round((it.x - x0) / step)) < 1e-3 && abs((it.y - y0) / step - Math.round((it.y - y0) / step)) < 1e-3 })
        // Growing along +x: the rightmost dots are larger than the leftmost.
        val left = dots.filter { it.x < 200 }.map { it.r }.average()
        val right = dots.filter { it.x > 400 }.map { it.r }.average()
        assertTrue(right > left)
    }

    @Test
    fun fourColorScreensAtPrintAngles() {
        assertEquals(listOf(15.0, 0.0, 75.0, 45.0, 75.0), PrintStyle.FOUR_COLOR.panels.map { it.angle })
        assertEquals(page.panels.size, PrintStyle.FOUR_COLOR.panels.size)
    }
}
