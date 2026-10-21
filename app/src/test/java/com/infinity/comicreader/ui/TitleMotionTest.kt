package com.infinity.comicreader.ui

import com.infinity.comicreader.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleMotionTest {

    private val speed = 0.06f // 30 dp/s at 2 px/dp, in px/ms

    @Test
    fun movingTitlesHoldStillWithoutAnimations() {
        assertEquals(TitleDisplay.MIDDLE, TitleMotion.effective(TitleDisplay.BOUNCE, animations = false))
        assertEquals(TitleDisplay.MIDDLE, TitleMotion.effective(TitleDisplay.MARQUEE, animations = false))
        assertEquals(TitleDisplay.MARQUEE, TitleMotion.effective(TitleDisplay.MARQUEE, animations = true))
        assertEquals(TitleDisplay.DOUBLE, TitleMotion.effective(TitleDisplay.DOUBLE, animations = false))
    }

    @Test
    fun aGlideTakesItsLengthOverTheSpeed() {
        assertEquals(1000L, TitleMotion.travelMs(60f, speed))
        assertEquals(0L, TitleMotion.travelMs(0f, speed))
        assertEquals(6000L, TitleMotion.bounceCycleMs(1000L))
    }

    @Test
    fun aBounceRestsGlidesRestsAndReturns() {
        val travel = 1000L
        fun at(t: Long) = TitleMotion.bounceOffset(t, 60f, speed, travel)
        assertEquals(0f, at(0), 0f)
        assertEquals(0f, at(1999), 0f)
        assertEquals(30f, at(2500), 0.01f)
        assertEquals(60f, at(3000), 0.01f)
        assertEquals(60f, at(4999), 0.01f)
        assertEquals(30f, at(5500), 0.01f)
        assertEquals(0f, at(5999), 0.1f)
    }

    @Test
    fun aShortGlideWaitsForTheLongestThenAllReturnTogether() {
        // 30 px of a cycle made for 1000 ms glides: there at 2500, waiting until 5000.
        fun at(t: Long) = TitleMotion.bounceOffset(t, 30f, speed, 1000L)
        assertEquals(30f, at(2500), 0.01f)
        assertEquals(30f, at(4000), 0.01f)
        assertEquals(30f, at(5000), 0.01f)
        assertEquals(0f, at(5500), 0.01f)
    }

    @Test
    fun aMarqueeLoopsLeftward() {
        assertEquals(0f, TitleMotion.marqueeOffset(0, speed, 100f), 0f)
        assertEquals(60f, TitleMotion.marqueeOffset(1000, speed, 100f), 0.01f)
        assertEquals(20f, TitleMotion.marqueeOffset(2000, speed, 100f), 0.01f)
    }
}
