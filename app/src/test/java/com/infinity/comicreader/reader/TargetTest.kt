package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTest {

    @Test
    fun notEnlargedKeepsTheSpanAtItsOwnSize() {
        val enlarging = Target(1848, 2960).plan(620.0, 876.0)
        val kept = enlarging.notEnlarged()
        assertEquals(620, kept.width)
        assertEquals(876, kept.height)
        assertTrue(kept.isCrop())
        val shrinking = Target(300, 450).plan(620.0, 876.0)
        assertEquals(shrinking, shrinking.notEnlarged())
    }

    private fun size(plan: Plan) = plan.width to plan.height

    @Test
    fun insideLandsTheLimitingAxisOnTheBox() {
        // A portrait page on a portrait screen fills the width.
        assertEquals(1848 to 2772, size(Target(1848, 2960).plan(4000.0, 6000.0)))
        // A wide page fills the width; a tall one fills the height.
        assertEquals(1848 to 1232, size(Target(1848, 2960).plan(6000.0, 4000.0)))
        assertEquals(1480 to 2960, size(Target(1848, 2960).plan(2000.0, 4000.0)))
    }

    @Test
    fun insideEnlargesSmallImages() {
        assertEquals(1848 to 2772, size(Target(1848, 2960).plan(800.0, 1200.0)))
    }

    @Test
    fun freeAxisFollowsTheOther() {
        assertEquals(1848 to 9240, size(Target(1848, 0).plan(800.0, 4000.0)))
        assertEquals(1973 to 2960, size(Target(0, 2960).plan(2000.0, 3000.0)))
    }

    @Test
    fun bothFreeKeepTheSource() {
        val plan = Target.NATIVE.plan(4000.0, 6000.0)
        assertTrue(plan.isIdentity(4000, 6000))
    }

    @Test
    fun shrinkNeverEnlarges() {
        assertTrue(Target(2400, 3200, Fit.SHRINK).plan(800.0, 1200.0).isIdentity(800, 1200))
        assertEquals(2133 to 3200, size(Target(2400, 3200, Fit.SHRINK).plan(4000.0, 6000.0)))
    }

    @Test
    fun coverAnchoredAtTheTopCropsOnlyTheBottom() {
        val plan = Target(300, 300, Fit.COVER, anchorTop = true).plan(2000.0, 3000.0, left = 10.0, top = 20.0)
        assertEquals(20.0, plan.y, 0.0)
        assertEquals(2000.0, plan.spanH, 1e-9)
        // Too wide: the sides still go evenly.
        val wide = Target(200, 300, Fit.COVER, anchorTop = true).plan(600.0, 300.0)
        assertEquals(200.0, wide.x, 1e-9)
        assertEquals(0.0, wide.y, 0.0)
    }

    @Test
    fun coverFillsTheBoxAndCropsEvenly() {
        // A 2:3 page in a square cell loses its top and bottom sixths.
        val plan = Target(300, 300, Fit.COVER).plan(2000.0, 3000.0)
        assertEquals(300 to 300, size(plan))
        assertEquals(0.0, plan.x, 0.0)
        assertEquals(500.0, plan.y, 1e-9)
        assertEquals(2000.0, plan.spanW, 0.0)
        assertEquals(2000.0, plan.spanH, 1e-9)
        // A wide image loses its sides instead.
        val wide = Target(200, 300, Fit.COVER).plan(600.0, 300.0)
        assertEquals(200.0, wide.x, 1e-9)
        assertEquals(200.0, wide.spanW, 1e-9)
        assertEquals(300.0, wide.spanH, 0.0)
    }

    @Test
    fun coverAtScaleOneCropsOnWholePixels() {
        val tall = Target(576, 336, Fit.COVER).plan(576.0, 337.0)
        assertEquals(0.0, tall.y, 0.0)
        assertTrue(tall.isCrop())
        val wide = Target(336, 576, Fit.COVER).plan(339.0, 576.0, left = 4.0)
        assertEquals(5.0, wide.x, 0.0)
        assertTrue(wide.isCrop())
    }

    @Test
    fun coverOfACropStaysInsideIt() {
        val plan = Target(100, 100, Fit.COVER).plan(400.0, 200.0, left = 50.0, top = 30.0)
        assertEquals(150.0, plan.x, 1e-9)
        assertEquals(30.0, plan.y, 0.0)
        assertEquals(200.0, plan.spanW, 1e-9)
    }

    @Test
    fun stretchIgnoresTheAspectRatio() {
        assertEquals(1848 to 2960, size(Target(1848, 2960, Fit.STRETCH).plan(4000.0, 4000.0)))
        // Without both dimensions it fits instead.
        assertEquals(1848 to 1848, size(Target(1848, 0, Fit.STRETCH).plan(4000.0, 4000.0)))
    }

    @Test
    fun resultIsCappedAspectKept() {
        // Too many pixels: both sides shrink by the same factor.
        val wide = Target(10000, 0).plan(1000.0, 1500.0)
        assertTrue(wide.width.toLong() * wide.height <= Target.MAX_OUTPUT_PIXELS)
        assertEquals(1.5, wide.height.toDouble() / wide.width, 0.001)
        // A tall strip page stops at the longest side a texture can have.
        val tall = Target(1848, 0).plan(800.0, 30000.0)
        assertTrue(tall.height in 16380..Target.MAX_OUTPUT_SIDE)
        assertEquals(37.5, tall.height.toDouble() / tall.width, 0.1)
    }

    @Test
    fun tallStripsStayDrawable() {
        // Portrait fit width and landscape fit width, aspects up to the side cap.
        for (box in intArrayOf(1848, 2960)) {
            for (h in intArrayOf(5000, 6000, 7400, 9000, 12000, 20000, 60000)) {
                val plan = Target(box, 0).plan(720.0, h.toDouble())
                assertTrue("$box / 720x$h", plan.width.toLong() * plan.height * 4 <= Target.MAX_DRAW_BYTES)
                assertTrue(plan.height <= Target.MAX_OUTPUT_SIDE)
            }
        }
    }

    @Test
    fun shrinksOnlyWhenNoAxisEnlarges() {
        assertTrue(Target(1848, 2960).plan(4000.0, 6000.0).shrinks())
        assertTrue(Target.NATIVE.plan(300.0, 200.0).shrinks())
        assertFalse(Target(1848, 2960).plan(800.0, 1200.0).shrinks())
        assertFalse(Target(1848, 2960, Fit.STRETCH).plan(4000.0, 2000.0).shrinks())
    }

    @Test
    fun cropAtScaleOneNeedsNoResampling() {
        assertTrue(Target.NATIVE.plan(300.0, 200.0, left = 10.0, top = 20.0).isCrop())
        assertFalse(Target(150, 0).plan(300.0, 200.0).isCrop())
    }

    @Test
    fun detailIsTheWholeContentAtFullSize() {
        val shown = Target(1848, 2960, Fit.STRETCH, autoCrop = true, linear = true)
        val detail = shown.detail(intArrayOf(4000, 6000), 96_000_000L)
        assertEquals(Target(autoCrop = true, linear = true), detail)
        assertEquals(Target(), Target(1848, 0).detail(null, 1L))
    }

    @Test
    fun detailOverBudgetShrinksEvenlyToFit() {
        val detail = Target(1848, 2960).detail(intArrayOf(4000, 6000), 24_000_000L)
        assertEquals(Fit.SHRINK, detail.fit)
        assertEquals(2000 to 3000, detail.width to detail.height)
        val plan = detail.plan(4000.0, 6000.0)
        assertTrue(plan.width.toLong() * plan.height * 4 <= 24_000_000L)
        // A cropped page fits the same box, never enlarged.
        assertEquals(1000 to 1500, size(detail.plan(1000.0, 1500.0)))
    }

    @Test
    fun halvesSplitWhatAutoCropLeaves() {
        assertEquals(listOf(0, 0, 2000, 1500), Target().area(2000, 1500).toList())
        assertEquals(listOf(0, 0, 1000, 1500), Target(half = Half.LEFT).area(2000, 1500).toList())
        assertEquals(listOf(1000, 0, 2000, 1500), Target(half = Half.RIGHT).area(2000, 1500).toList())
        // Cropped first: the halves meet in the middle of the content, the left taking the floor.
        val bounds = intArrayOf(101, 20, 1902, 1480)
        assertEquals(listOf(101, 20, 1001, 1480), Target(half = Half.LEFT).area(2000, 1500, bounds).toList())
        assertEquals(listOf(1001, 20, 1902, 1480), Target(half = Half.RIGHT).area(2000, 1500, bounds).toList())
    }

    @Test
    fun aHalfKeepsItsSideAtFullDetail() {
        assertEquals(Half.RIGHT, Target(800, 600, half = Half.RIGHT).detail(intArrayOf(4000, 3000), Long.MAX_VALUE).half)
    }

    @Test
    fun boxesCoverTheWholePixelsTheyTouch() {
        assertEquals(listOf(100, 200, 900, 1800), Box(0.1, 0.1, 0.9, 0.9).pixels(1000, 2000).toList())
        assertEquals(listOf(0, 0, 1000, 2000), Box.FULL.pixels(1000, 2000).toList())
        assertEquals(listOf(100, 200, 901, 1801), Box(0.1004, 0.1001, 0.9004, 0.9001).pixels(1000, 2000).toList())
        assertEquals(Box(0.25, 0.5, 0.75, 1.0), Box.of(intArrayOf(100, 200, 300, 400), 400, 400))
        assertEquals(Box.FULL, Box.of(null, 400, 400))
    }

    @Test
    fun aBoxTakesThePlaceOfTheAutoCrop() {
        val t = Target(autoCrop = true, box = Box(0.1, 0.1, 0.9, 0.9))
        assertEquals(listOf(100, 100, 900, 900), t.area(1000, 1000, intArrayOf(0, 0, 500, 500)).toList())
        assertEquals(listOf(500, 100, 900, 900), t.copy(half = Half.RIGHT).area(1000, 1000).toList())
        assertEquals(t.box, t.detail(intArrayOf(1000, 1000), Long.MAX_VALUE).box)
    }
}
