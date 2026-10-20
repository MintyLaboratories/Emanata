package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** "Smooth scrolling (fling)": a quick pan glides on and stops at the page's edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZoomImageViewFlingTest {

    @Test
    fun rangeRunsFromTheFarEdgeToTheNearOne() {
        assertEquals(-2040..0, ZoomImageView.flingRange(content = 5000f, view = 2960f, trans = -300f))
    }

    @Test
    fun contentThatFitsDoesNotMove() {
        assertEquals(12..12, ZoomImageView.flingRange(content = 1800f, view = 1848f, trans = 12f))
        assertEquals(0..0, ZoomImageView.flingRange(content = 1849f, view = 1848f, trans = 0f))
    }

    /** A page as wide as the screen and taller than it, attached so it animates. */
    private fun tallPage(fling: Boolean): ZoomImageView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = ZoomImageView(activity).apply { flingEnabled = fling }
        activity.setContentView(FrameLayout(activity).apply { addView(v, FrameLayout.LayoutParams(1848, 2960)) })
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1848, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2960, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, 1848, 2960)
        v.setImageBitmap(Bitmap.createBitmap(1848, 5000, Bitmap.Config.ARGB_8888))
        v.setFitMode(ZoomImageView.FitMode.WIDTH)
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun transY(v: ZoomImageView) = FloatArray(9).also { v.imageMatrix.getValues(it) }[Matrix.MTRANS_Y]

    /** Drags up 600 px in 48 ms and lifts the finger. */
    private fun flickUp(v: ZoomImageView) {
        val t = SystemClock.uptimeMillis()
        var y = 2000f
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 900f, y, 0))
        repeat(3) {
            y -= 200f
            v.onTouchEvent(MotionEvent.obtain(t, t + 16L * (it + 1), MotionEvent.ACTION_MOVE, 900f, y, 0))
        }
        v.onTouchEvent(MotionEvent.obtain(t, t + 48L, MotionEvent.ACTION_UP, 900f, y, 0))
    }

    @Test
    fun offTheFingerAloneMovesThePage() {
        val v = tallPage(fling = false)
        flickUp(v)
        val lifted = transY(v)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(v.flinging)
        assertEquals(lifted, transY(v), 0f)
    }

    @Test
    fun onThePageGlidesAndStopsAtItsEdge() {
        val v = tallPage(fling = true)
        flickUp(v)
        val lifted = transY(v)
        assertTrue(v.flinging)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertFalse(v.flinging)
        val end = transY(v)
        assertTrue("glided from $lifted to $end", end < lifted - 100f)
        assertTrue("stopped at $end", end >= 2960f - 5000f)
    }

    @Test
    fun aTouchCatchesTheFling() {
        val v = tallPage(fling = true)
        flickUp(v)
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 900f, 1000f, 0))
        assertFalse(v.flinging)
    }
}
