package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollTest {

    @Test
    fun speedsRiseAndKeepSlowStepsBelowOneScreenAMinute() {
        val speeds = AutoScroll.SPEEDS
        assertTrue((1 until speeds.size).all { speeds[it] > speeds[it - 1] })
        assertEquals(3, speeds.count { it < 1f })
        assertEquals(45f, speeds.last(), 0f)
        assertTrue(speeds.any { it == AutoScroll.DEFAULT })
    }

    @Test
    fun aPageStaysOneScreenHeightsWorthOfTime() {
        assertEquals(20_000L, AutoScroll.pageMs(3f))
        assertEquals(240_000L, AutoScroll.pageMs(0.25f))
        assertEquals(1_333L, AutoScroll.pageMs(45f))
    }

    @Test
    fun aStripScrollsItsSpeedInScreenHeightsPerMinute() {
        val clock = AutoScrollClock()
        var px = 0
        // One minute of 16 ms frames at 2 screens a minute on a 2960 px screen.
        repeat(3750) { px += clock.scroll(16, 2f, 2960) }
        assertEquals(2 * 2960.0, px.toDouble(), 1.0)
    }

    @Test
    fun slowScrollingCarriesFractionsAcrossFrames() {
        val clock = AutoScrollClock()
        // 0.25 screens a minute on 1848 px: 0.0077 px per ms, so no frame alone moves a pixel.
        assertEquals(0, clock.scroll(16, 0.25f, 1848))
        var px = 0
        repeat(1000) { px += clock.scroll(16, 0.25f, 1848) }
        assertEquals(123, px)
    }

    @Test
    fun pagesTurnExactlyWhenAScreenHeightWouldHaveScrolledPast() {
        val clock = AutoScrollClock()
        // 3 screens a minute: a turn every 20 s, the frames' overshoot carried on.
        var turns = 0
        var at = 0L
        val times = ArrayList<Long>()
        while (at < 60_000L) {
            at += 16
            if (clock.turn(16, 3f)) {
                turns++
                times += at
            }
        }
        assertEquals(3, turns)
        assertEquals(listOf(20_000L, 40_000L, 60_000L), times)
    }

    @Test
    fun progressFillsOverThePageAndStartsOverAfterATurn() {
        val clock = AutoScrollClock()
        assertFalse(clock.turn(5_000, 3f))
        assertEquals(0.25f, clock.progress(3f), 1e-6f)
        assertFalse(clock.turn(10_000, 3f))
        assertEquals(0.75f, clock.progress(3f), 1e-6f)
        assertTrue(clock.turn(6_000, 3f))
        assertEquals(0.05f, clock.progress(3f), 1e-6f)
        clock.reset()
        assertEquals(0f, clock.progress(3f), 0f)
    }

    @Test
    fun aFasterSpeedMidPageTurnsOnceAndLeavesNoBacklog() {
        val clock = AutoScrollClock()
        assertFalse(clock.turn(50_000, 1f))
        assertTrue(clock.turn(16, 30f))
        assertFalse(clock.turn(16, 30f))
    }

    @Test
    fun pagedTicksComeOncePerBarPixelAFewTimesASecond() {
        val clock = AutoScrollClock()
        // 0.25 screens a minute: 240 s a page, a pixel of a 1848 px bar every 129 ms.
        assertEquals(129L, clock.nextTickMs(0.25f, 1848))
        // Fast speeds tick at most 10 times a second; narrow bars and unknown widths 4 times.
        assertEquals(AutoScroll.MIN_TICK_MS, clock.nextTickMs(45f, 1848))
        assertEquals(AutoScroll.MAX_TICK_MS, clock.nextTickMs(0.25f, 100))
        assertEquals(AutoScroll.MAX_TICK_MS, clock.nextTickMs(3f, 0))
        // The last tick lands on the turn.
        clock.turn(19_950, 3f)
        assertEquals(50L, clock.nextTickMs(3f, 1848))
    }

    @Test
    fun ticksAtTheirOwnPaceStillTurnOnTime() {
        val clock = AutoScrollClock()
        var at = 0L
        var ticks = 0
        val times = ArrayList<Long>()
        while (at < 40_000L) {
            val dt = clock.nextTickMs(3f, 1848)
            at += dt
            ticks++
            if (clock.turn(dt, 3f)) times += at
        }
        assertEquals(listOf(20_000L, 40_000L), times)
        // About 10 ticks a second, against 2500 frames at 16 ms.
        assertEquals(400, ticks)
    }

    @Test
    fun oldStepsMapToTheStripSpeedTheyHadOnATablet() {
        // 30 dp/s per step on a screen 1480 dp tall.
        assertEquals(1f, AutoScroll.fromOldStep(1), 0f)
        assertEquals(6f, AutoScroll.fromOldStep(5), 0f)
        assertEquals(12f, AutoScroll.fromOldStep(10), 0f)
        assertEquals(12f, AutoScroll.fromOldStep(99), 0f)
    }

    @Test
    fun storedSpeedsRoundTripAndOddOnesFallBack() {
        for (speed in AutoScroll.SPEEDS) assertEquals(speed, AutoScroll.parse(AutoScroll.format(speed)), 0f)
        assertEquals("0.25", AutoScroll.format(0.25f))
        assertEquals("3", AutoScroll.format(3f))
        assertEquals(1.5f, AutoScroll.parse("1.4"), 0f)
        assertEquals(AutoScroll.DEFAULT, AutoScroll.parse("fast"), 0f)
        assertEquals(AutoScroll.DEFAULT, AutoScroll.parse("-2"), 0f)
        assertEquals(AutoScroll.DEFAULT, AutoScroll.parse(null), 0f)
    }
}
