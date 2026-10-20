package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.ui.PageAdapter.Mode
import com.infinity.comicreader.ui.PageTargets.Slot
import com.infinity.comicreader.ui.ZoomImageView.FitMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTargetsTest {

    @Test
    fun singlePageViewIsTheItemLessItsSpacing() {
        assertArrayEquals(intArrayOf(1848, 2960), PageTargets.viewSize(Mode.PAGED, false, 1848, 2960, 0, Slot.ALONE))
        assertArrayEquals(intArrayOf(1828, 2940), PageTargets.viewSize(Mode.PAGED, false, 1848, 2960, 10, Slot.ALONE))
        assertArrayEquals(intArrayOf(1828, 2940), PageTargets.viewSize(Mode.VERTICAL, false, 1848, 2960, 10, Slot.ALONE))
    }

    @Test
    fun longStripsAreNeverPadded() {
        assertArrayEquals(intArrayOf(1848, 2960), PageTargets.viewSize(Mode.LONG_STRIP, false, 1848, 2960, 10, Slot.ALONE))
    }

    @Test
    fun spreadHalvesSplitTheRowAndALonePageTakesItAll() {
        assertArrayEquals(intArrayOf(1480, 1848), PageTargets.viewSize(Mode.PAGED, true, 2960, 1848, 10, Slot.LEFT))
        assertArrayEquals(intArrayOf(1480, 1848), PageTargets.viewSize(Mode.PAGED, true, 2960, 1848, 10, Slot.RIGHT))
        // An odd width leaves the extra pixel to the right half.
        assertArrayEquals(intArrayOf(1480, 1848), PageTargets.viewSize(Mode.PAGED, true, 2961, 1848, 0, Slot.LEFT))
        assertArrayEquals(intArrayOf(1481, 1848), PageTargets.viewSize(Mode.PAGED, true, 2961, 1848, 0, Slot.RIGHT))
        assertArrayEquals(intArrayOf(2960, 1848), PageTargets.viewSize(Mode.PAGED, true, 2960, 1848, 10, Slot.ALONE))
    }

    @Test
    fun targetFollowsTheFitMode() {
        assertEquals(Target(1848, 2960), PageTargets.target(Mode.PAGED, FitMode.SCREEN, 1848, 2960))
        assertEquals(Target(1848, 0), PageTargets.target(Mode.PAGED, FitMode.WIDTH, 1848, 2960))
        assertEquals(Target(0, 2960), PageTargets.target(Mode.PAGED, FitMode.HEIGHT, 1848, 2960))
        assertEquals(Target(1848, 2960, Fit.STRETCH), PageTargets.target(Mode.PAGED, FitMode.STRETCH, 1848, 2960))
        assertEquals(Target(1848, 0, autoCrop = true, linear = true),
            PageTargets.target(Mode.VERTICAL, FitMode.WIDTH, 1848, 2960, autoCrop = true, linear = true))
    }

    @Test
    fun longStripsFitTheWidthUncropped() {
        assertEquals(Target(1848, 0), PageTargets.target(Mode.LONG_STRIP, FitMode.HEIGHT, 1848, 2960, autoCrop = true))
    }

    @Test
    fun fittedPageComesOutAtTheViewSize() {
        // Fit page: the limiting side equals the view, so ZoomImageView's fit scale is 1.
        val plan = PageTargets.target(Mode.PAGED, FitMode.SCREEN, 1480, 1848).plan(2000.0, 3000.0)
        assertEquals(1848, plan.height)
        assertEquals(1232, plan.width)
    }
}
