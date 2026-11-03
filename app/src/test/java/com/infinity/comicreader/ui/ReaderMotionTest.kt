package com.infinity.comicreader.ui

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader moves while touched or scrolling and shortly after; a finger resting still stops counting. */
class ReaderMotionTest {
    private var now = 1_000L
    private val motion = ReaderMotion(clock = { now }, settleMs = 250, touchMs = 3_000)

    @Test
    fun stillUntilTouched() {
        assertFalse(motion.moving)
    }

    @Test
    fun aTouchMovesUntilShortlyAfterItsLastFingerIsUp() {
        motion.touched(MotionEvent.ACTION_DOWN)
        now += 500
        motion.touched(MotionEvent.ACTION_MOVE)
        assertTrue(motion.moving)
        motion.touched(MotionEvent.ACTION_UP)
        now += 200
        assertTrue("settling", motion.moving)
        now += 100
        assertFalse(motion.moving)
    }

    @Test
    fun aFingerRestingStillStopsCounting() {
        motion.touched(MotionEvent.ACTION_DOWN)
        now += 2_999
        assertTrue(motion.moving)
        now += 2
        assertFalse(motion.moving)
        motion.touched(MotionEvent.ACTION_MOVE)
        assertTrue(motion.moving)
    }

    @Test
    fun scrollingMovesUntilShortlyAfterItSettles() {
        motion.scrolled(RecyclerView.SCROLL_STATE_SETTLING)
        now += 10_000
        assertTrue(motion.moving)
        motion.scrolled(RecyclerView.SCROLL_STATE_IDLE)
        assertTrue(motion.moving)
        now += 251
        assertFalse(motion.moving)
    }
}
