package minty.emanata.ui

import minty.emanata.reader.Fit
import minty.emanata.reader.Target
import minty.emanata.ui.PageAdapter.Mode
import minty.emanata.ui.PageTargets.Slot
import minty.emanata.ui.ZoomImageView.FitMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTargetsTest {

    @Test
    fun singlePageViewIsTheItemLessItsSpacing() {
        assertArrayEquals(intArrayOf(1848, 2960), PageTargets.viewSize(Mode.PAGED, false, 1848, 2960, 0, Slot.ALONE))
        assertArrayEquals(intArrayOf(1828, 2940), PageTargets.viewSize(Mode.PAGED, false, 1848, 2960, 10, Slot.ALONE))
    }

    @Test
    fun aColumnPageTakesTheWholeWidth() {
        assertArrayEquals(intArrayOf(1848, 2960), PageTargets.viewSize(Mode.CONTINUOUS, false, 1848, 2960, 10, Slot.ALONE))
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
        assertEquals(Target(1848, 2960, banded = true), PageTargets.target(Mode.PAGED, FitMode.SCREEN, 1848, 2960))
        assertEquals(Target(1848, 0, banded = true), PageTargets.target(Mode.PAGED, FitMode.WIDTH, 1848, 2960))
        assertEquals(Target(0, 2960, banded = true), PageTargets.target(Mode.PAGED, FitMode.HEIGHT, 1848, 2960))
        assertEquals(Target(1848, 2960, Fit.STRETCH), PageTargets.target(Mode.PAGED, FitMode.STRETCH, 1848, 2960))
        assertEquals(Target(1848, 0, autoCrop = true, linear = true, banded = true),
            PageTargets.target(Mode.PAGED, FitMode.WIDTH, 1848, 2960, autoCrop = true, linear = true))
    }

    @Test
    fun aColumnFitsTheWidthCroppedWhenAsked() {
        assertEquals(Target(1848, 0, banded = true), PageTargets.target(Mode.CONTINUOUS, FitMode.HEIGHT, 1848, 2960))
        assertEquals(Target(1848, 0, autoCrop = true, banded = true), PageTargets.target(Mode.CONTINUOUS, FitMode.SCREEN, 1848, 2960, autoCrop = true))
    }

    @Test
    fun aVeryTallPageAtFitWidthGetsAStandInForItsBands() {
        val target = PageTargets.target(Mode.CONTINUOUS, FitMode.WIDTH, 1848, 2960)
        // 1:29 at 1848 wide would be over 98 million pixels.
        assertTrue(target.bandsAt(1000.0, 25000.0))
        val plan = target.plan(1000.0, 25000.0)
        assertTrue(plan.width.toLong() * plan.height <= Target.BASE_PIXELS)
        // A page of the screen's shape fits in one bitmap at its size.
        assertFalse(target.bandsAt(1000.0, 1600.0))
        assertEquals(1848, target.plan(1000.0, 1600.0).width)
    }

    @Test
    fun aColumnItemHoldsItsGapBelowThePage() {
        assertEquals(3000, PageTargets.stripHeight(2000, 1.5f))
        assertEquals(3024, PageTargets.stripHeight(2000, 1.5f, gapPx = 24))
        assertEquals(3000, PageTargets.stripHeight(2000, 1.5f, gapPx = -5))
    }

    @Test
    fun fittedPageComesOutAtTheViewSize() {
        // Fit page: the limiting side equals the view, so ZoomImageView's fit scale is 1.
        val plan = PageTargets.target(Mode.PAGED, FitMode.SCREEN, 1480, 1848).plan(2000.0, 3000.0)
        assertEquals(1848, plan.height)
        assertEquals(1232, plan.width)
    }

    @Test
    fun stripItemIsAsTallAsItsBitmap() {
        for (width in intArrayOf(1848, 2960)) {
            for (h in 1..Target.MAX_OUTPUT_SIDE) {
                assertEquals("$width x $h", h, PageTargets.stripHeight(width, h.toFloat() / width))
            }
        }
    }

    @Test
    fun aPairShowsAtTheTallestHeightBothHalvesFit() {
        // Portrait: the wider page limits both.
        assertEquals(1428, PageTargets.pairHeight(2000, 3000, 0.66, 0.70))
        // Landscape: the screen's height does.
        assertEquals(2000, PageTargets.pairHeight(3000, 2000, 0.66, 0.70))
        // An odd width: the right half is a pixel wider.
        assertEquals(1001, PageTargets.pairHeight(2001, 3000, 0.5, 1.0))
    }
}
