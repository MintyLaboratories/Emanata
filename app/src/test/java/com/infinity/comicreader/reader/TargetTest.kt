package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTest {

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
        val plan = Target(4000, 0).plan(100.0, 100_000.0)
        assertTrue(plan.width.toLong() * plan.height <= Target.MAX_OUTPUT_PIXELS)
        assertEquals(1000.0, plan.height.toDouble() / plan.width, 10.0)
    }

    @Test
    fun cropAtScaleOneNeedsNoResampling() {
        assertTrue(Target.NATIVE.plan(300.0, 200.0, left = 10.0, top = 20.0).isCrop())
        assertFalse(Target(150, 0).plan(300.0, 200.0).isCrop())
    }
}
