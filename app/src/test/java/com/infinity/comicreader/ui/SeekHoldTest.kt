package com.infinity.comicreader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekHoldTest {

    @Test
    fun aStillTouchHolds() {
        val h = SeekHold(slop = 10f)
        h.down(100f, 20f)
        assertTrue(h.move(105f, 24f))
        assertTrue(h.held())
        // Once: the strip opens a single time per touch.
        assertFalse(h.held())
    }

    @Test
    fun movingPastTheSlopNeverHoldsAgain() {
        val h = SeekHold(slop = 10f)
        h.down(100f, 20f)
        assertFalse(h.move(120f, 20f))
        assertFalse(h.move(100f, 20f))
        assertFalse(h.held())
    }

    @Test
    fun liftingBeforeTheTimeIsUpDoesNotHold() {
        val h = SeekHold(slop = 10f)
        h.down(0f, 0f)
        h.up()
        assertFalse(h.held())
    }

    @Test
    fun aTouchOnTheThumbKeepsItsItem() {
        // 1000 px for 100 steps: 10 px a step; the thumb reaches 16 px either side.
        assertTrue(SeekHold.onThumb(from = 40, to = 41, thumbHalf = 16, track = 1000, max = 100))
        assertTrue(SeekHold.onThumb(from = 40, to = 40, thumbHalf = 16, track = 1000, max = 100))
        assertFalse(SeekHold.onThumb(from = 40, to = 42, thumbHalf = 16, track = 1000, max = 100))
        assertFalse(SeekHold.onThumb(from = 40, to = 10, thumbHalf = 16, track = 1000, max = 100))
        // A one-page book has nowhere else to go.
        assertTrue(SeekHold.onThumb(from = 0, to = 0, thumbHalf = 16, track = 1000, max = 0))
    }

    @Test
    fun aNewTouchStartsOver() {
        val h = SeekHold(slop = 10f)
        h.down(0f, 0f)
        h.move(50f, 0f)
        h.down(50f, 0f)
        assertTrue(h.held())
    }
}
