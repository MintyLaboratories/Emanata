package com.infinity.comicreader.ui

import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.TabView
import org.junit.Assert.assertEquals
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
        // One side at its end, the other still steps.
        assertEquals(TabView(grid = true, columns = 1, rows = 2), ViewRing.larger(TabView(grid = true, columns = 1, rows = 3), 1))
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
}
