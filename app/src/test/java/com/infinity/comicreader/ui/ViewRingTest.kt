package com.infinity.comicreader.ui

import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.TabView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ViewRingTest {

    @Test
    fun everyTabGetsTheCommonFivePlusItsOwn() {
        val common = listOf(RingOption.LAYOUT, RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT)
        assertEquals(common + RingOption.FOLDERS_FIRST, ViewRing.options(Prefs.TAB_BROWSE))
        assertEquals(common + RingOption.SECTIONS, ViewRing.options(Prefs.TAB_FAVORITES))
        assertEquals(common, ViewRing.options(Prefs.TAB_HISTORY))
        assertEquals(common, ViewRing.options(Prefs.TAB_BOOKMARKS))
    }

    @Test
    fun gridStepsFromTheColumnsShownAndRowsWhenSet() {
        val auto = TabView(grid = true)
        assertEquals(auto.copy(columns = 4), ViewRing.larger(auto, columns = 5))
        assertEquals(auto.copy(columns = 6), ViewRing.smaller(auto, columns = 5))
        val set = TabView(grid = true, columns = 4, rows = 3)
        assertEquals(set.copy(columns = 3, rows = 2), ViewRing.larger(set, 4))
        assertEquals(set.copy(columns = 5, rows = 4), ViewRing.smaller(set, 4))
    }

    @Test
    fun gridStopsAtItsEnds() {
        assertNull(ViewRing.larger(TabView(grid = true, columns = 1), 1))
        assertNull(ViewRing.smaller(TabView(grid = true), 6))
        assertNull(ViewRing.smaller(TabView(grid = true, columns = 6, rows = 6), 6))
        // One side at its end stops both, keeping the shape.
        assertNull(ViewRing.larger(TabView(grid = true, columns = 1, rows = 3), 1))
        assertNull(ViewRing.smaller(TabView(grid = true, columns = 6, rows = 3), 6))
        assertNull(ViewRing.larger(TabView(grid = true, columns = 3, rows = 1), 3))
        assertEquals(TabView(grid = true, columns = 2, rows = 2), ViewRing.larger(TabView(grid = true, columns = 3, rows = 3), 3))
    }

    @Test
    fun listStepsDensity() {
        val normal = TabView(grid = false, density = ListDensity.NORMAL)
        assertEquals(ListDensity.LARGE, ViewRing.larger(normal, 3)?.density)
        assertEquals(ListDensity.COMPACT, ViewRing.smaller(normal, 3)?.density)
        assertNull(ViewRing.smaller(normal.copy(density = ListDensity.TINY), 3))
        assertNull(ViewRing.larger(normal.copy(density = ListDensity.HUGE), 3))
    }

    @Test
    fun detailGoesRound() {
        assertEquals(DetailDisplay.DENSE, ViewRing.nextDetail(DetailDisplay.COMFORTABLE))
        assertEquals(DetailDisplay.HIDDEN, ViewRing.nextDetail(DetailDisplay.DENSE))
        assertEquals(DetailDisplay.COMFORTABLE, ViewRing.nextDetail(DetailDisplay.HIDDEN))
    }

    @Test
    fun anglesRunFromUpTowardTheInside() {
        assertEquals(listOf(90.0, 112.5, 135.0, 157.5, 180.0), RingGeometry.angles(5, left = true))
        assertEquals(listOf(90.0, 67.5, 45.0, 22.5, 0.0), RingGeometry.angles(5, left = false))
        assertEquals(listOf(135.0), RingGeometry.angles(1, left = true))
    }

    @Test
    fun radiusKeepsNeighborsApart() {
        for (n in 2..6) {
            val r = RingGeometry.radius(n, spacing = 84f, min = 0f)
            val (x0, y0) = RingGeometry.spot(90.0, r)
            val (x1, y1) = RingGeometry.spot(90.0 + 90.0 / (n - 1), r)
            assertEquals(84.0, hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()), 0.01)
        }
        assertEquals(136f, RingGeometry.radius(2, 84f, 136f))
    }

    @Test
    fun spotsGoUpAndLeft() {
        val (ux, uy) = RingGeometry.spot(90.0, 100f)
        assertEquals(0f, ux, 0.001f)
        assertEquals(-100f, uy, 0.001f)
        val (lx, ly) = RingGeometry.spot(180.0, 100f)
        assertEquals(-100f, lx, 0.001f)
        assertEquals(0f, ly, 0.001f)
    }

    @Test
    fun labelsSitOutwardWithTheirNearSideAtReach() {
        // Straight up: centered above, its bottom edge at reach.
        val (ux, uy) = RingGeometry.labelCenter(90.0, 36f, 100f, 40f)
        assertEquals(0f, ux, 0.001f)
        assertEquals(-56f, uy, 0.001f)
        // Straight left: its right edge at reach.
        val (lx, ly) = RingGeometry.labelCenter(180.0, 36f, 100f, 40f)
        assertEquals(-86f, lx, 0.001f)
        assertEquals(0f, ly, 0.001f)
        // Diagonal: its near corner on the diagonal, no nearer than reach.
        val (dx, dy) = RingGeometry.labelCenter(135.0, 36f, 100f, 40f)
        val cornerX = dx + 50f
        val cornerY = dy + 20f
        assertEquals(cornerX, cornerY, 0.001f)
        assertTrue(hypot(cornerX.toDouble(), cornerY.toDouble()) >= 36.0 - 0.01)
        assertTrue(abs(cornerX) > 0f)
    }

    @Test
    fun labelSidesRunOutwardNearestFirstInwardLast() {
        assertEquals(listOf(162.0, 180.0, 90.0, 270.0, 0.0, 342.0), RingGeometry.sides(162.0))
        assertEquals(listOf(90.0, 0.0, 180.0, 270.0), RingGeometry.sides(90.0))
    }

    @Test
    fun aRoundButtonOverlapsABoxByDepth() {
        val box = RingGeometry.Box(0f, 0f, 100f, 40f)
        assertEquals(0f, box.overlap(150f, 20f, 28f), 0f)
        assertEquals(6f, box.overlap(122f, 20f, 28f), 0.001f)
        // Off the corner: clear along the diagonal though its bounding square would touch.
        assertEquals(0f, box.overlap(120f, 60f, 28f), 0f)
        assertTrue(box.overlap(50f, 20f, 28f) > 28f)
    }

    @Test
    fun roomStopsAtTheBoundsOnTheSideTheRingGrowsTo() {
        val bounds = RingGeometry.Box(8f, 32f, 392f, 792f)
        assertEquals(264f, RingGeometry.room(180.0, 300f, 500f, bounds, 0f, 0f, 56f, 56f), 0.01f)
        // A label 100 wide whose center sits 86 left of the spot.
        assertEquals(156f, RingGeometry.room(180.0, 300f, 500f, bounds, -86f, 0f, 100f, 40f), 0.01f)
        // Straight up, the top inset counts; the sides do not.
        assertEquals(440f, RingGeometry.room(90.0, 300f, 500f, bounds, 0f, 0f, 56f, 56f), 0.01f)
    }

    /** Six labels of typical widths and one at the label's widest, all two lines tall (dp). */
    private val labels = listOf(96f, 132f, 132f, 140f, 150f, 200f).map { it to 44f }

    private fun arrange(width: Float, height: Float, hubX: Float, hubY: Float, top: Float = 0f, left: Boolean = hubX > width / 2) =
        RingGeometry.Box(8f, top + 8f, width - 8f, height - 8f).let { bounds ->
            val angles = RingGeometry.angles(6, left)
            Triple(angles, bounds, RingGeometry.arrange(angles, hubX, hubY, bounds, 56f, RingGeometry.radius(6, 112f, 136f), 64f, 36f, 4f, labels))
        }

    /** Every button and label of [p] lies within [bounds], and no label covers a round button, the hub or another label. */
    private fun assertClear(angles: List<Double>, hubX: Float, hubY: Float, bounds: RingGeometry.Box, p: RingGeometry.Placement) {
        val buttons = listOf(hubX to hubY) + angles.map { a -> RingGeometry.spot(a, p.radius).let { (dx, dy) -> (hubX + dx) to (hubY + dy) } }
        for ((x, y) in buttons) assertTrue("$x, $y", RingGeometry.Box.around(x, y, 56f, 56f).within(bounds))
        for ((i, l) in p.boxes.withIndex()) {
            assertTrue("label $i $l", l.within(bounds))
            for ((x, y) in buttons) assertEquals("label $i on $x, $y", 0f, l.overlap(x, y, 28f), 0f)
            for (o in p.boxes.drop(i + 1)) assertEquals("label $i on $o", 0f, l.overlap(o), 0f)
        }
        assertEquals(0f, p.overlap, 0f)
    }

    @Test
    fun aRoomyWindowKeepsTheFullRingWithEveryLabelOutward() {
        val (angles, bounds, p) = arrange(800f, 1280f, 744f, 1180f)
        assertEquals(RingGeometry.radius(6, 112f, 136f), p.radius, 0.01f)
        assertEquals(0, p.aside)
        assertClear(angles, 744f, 1180f, bounds, p)
    }

    @Test
    fun aNarrowWindowShrinksTheRingSoLabelsClearTheButtons() {
        for (left in listOf(true, false)) {
            val hubX = if (left) 356f else 44f
            val (angles, bounds, p) = arrange(400f, 800f, hubX, 700f, left = left)
            assertTrue("$left ${p.radius}", p.radius < RingGeometry.radius(6, 112f, 136f))
            // Neighbors stay apart.
            assertTrue("$left ${p.radius}", p.radius >= RingGeometry.radius(6, 64f, 0f) - 0.01f)
            assertClear(angles, hubX, 700f, bounds, p)
        }
    }

    @Test
    fun landscapeKeepsEverythingBelowTheStatusBar() {
        val (angles, bounds, p) = arrange(1280f, 400f, 1224f, 344f, top = 24f)
        assertClear(angles, 1224f, 344f, bounds, p)
    }

    @Test
    fun placingAgainKeepsTheRadiusAndMovesAGrownLabelAside() {
        val (angles, bounds, p) = arrange(400f, 800f, 356f, 700f)
        val wider = labels.toMutableList().apply { this[5] = 200f to 66f }
        val again = RingGeometry.place(angles, 356f, 700f, bounds, 56f, p.radius, 36f, 4f, wider)
        assertEquals(p.radius, again.radius, 0f)
        assertTrue(again.boxes.all { it.within(bounds) })
    }

    @Test
    fun theNearestAngleWinsAtAnyDistanceAndAroundTheCircle() {
        val right = RingGeometry.angles(5, left = false)
        assertEquals(listOf(90.0, 67.5, 45.0, 22.5, 0.0), right)
        // Straight up, near and far.
        assertEquals(0, RingGeometry.nearest(right, 0f, -10f))
        assertEquals(0, RingGeometry.nearest(right, 0.5f, -5000f))
        assertEquals(2, RingGeometry.nearest(right, 100f, -100f))
        // Straight down is nearer 0 than 90; down-left is nearer 90 across 180.
        assertEquals(4, RingGeometry.nearest(right, 1f, 100f))
        assertEquals(0, RingGeometry.nearest(right, -100f, -10f))
        val left = RingGeometry.angles(5, left = true)
        assertEquals(4, RingGeometry.nearest(left, -100f, 1f))
        assertEquals(4, RingGeometry.nearest(left, -100f, 30f))
        assertEquals(-1, RingGeometry.nearest(emptyList(), 1f, 1f))
    }

    private val all = { _: Int -> true }

    @Test
    fun aFingerThatStaysOnTheHubOrWithinTheSlopIsATap() {
        val g = RingGesture(RingGeometry.angles(5, left = true), hub = 28f, slop = 8f, x0 = 0f, y0 = 0f)
        assertFalse(g.move(20f, 0f, all))
        assertFalse(g.move(-27f, 0f, all))
        assertFalse(g.dragging)
        // Pressed at the hub's edge: a few pixels out is still within the slop.
        val edge = RingGesture(RingGeometry.angles(5, left = true), hub = 28f, slop = 8f, x0 = -26f, y0 = 0f)
        assertFalse(edge.move(-31f, 0f, all))
        assertFalse(edge.dragging)
        assertEquals(-1, edge.picked)
    }

    @Test
    fun draggingPicksByAngleAndBackOnTheHubPicksNone() {
        val g = RingGesture(RingGeometry.angles(5, left = true), hub = 28f, slop = 8f, x0 = 0f, y0 = 0f)
        assertTrue(g.move(0f, -100f, all))
        assertTrue(g.dragging)
        assertEquals(0, g.picked)
        // The same button again changes nothing.
        assertFalse(g.move(3f, -300f, all))
        assertTrue(g.move(-100f, -100f, all))
        assertEquals(2, g.picked)
        assertTrue(g.move(-5000f, 10f, all))
        assertEquals(4, g.picked)
        assertTrue(g.move(5f, 5f, all))
        assertEquals(-1, g.picked)
        // Dragging stays dragging back on the hub.
        assertTrue(g.dragging)
    }

    @Test
    fun aGrayedButtonIsNeverPicked() {
        val g = RingGesture(RingGeometry.angles(5, left = false), hub = 28f, slop = 8f, x0 = 0f, y0 = 0f)
        val notTwo = { i: Int -> i != 2 }
        assertFalse(g.move(100f, -100f, notTwo))
        assertTrue(g.dragging)
        assertEquals(-1, g.picked)
        assertTrue(g.move(0f, -100f, notTwo))
        assertEquals(0, g.picked)
        assertTrue(g.move(100f, -100f, notTwo))
        assertEquals(-1, g.picked)
    }
}
