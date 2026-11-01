package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
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

/** The brightness drag starts only on the page image, never in the system's gesture areas. */
@RunWith(RobolectricTestRunner::class)
// A window of 1000 x 2000 px, the page view filling it: the gesture areas lie at its edges.
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h2000dp-mdpi")
class BrightnessStartTest {
    private val gestures = BrightnessStart.Edges(left = 40, top = 60, right = 40, bottom = 90)

    private fun allowed(x: Float, y: Float, viewTop: Int = 0) =
        BrightnessStart.allowed(x, y, 100f, 200f, 900f, 1800f, 0, viewTop, 1000, 2000, gestures)

    @Test
    fun onlyOnTheImageAndOutsideTheGestureAreas() {
        assertTrue(allowed(500f, 1000f))
        // Margins and letterbox around the image.
        assertFalse(allowed(50f, 1000f))
        assertFalse(allowed(500f, 150f))
        assertFalse(allowed(500f, 1850f))
        // In the window's gesture areas, though on the image: the view lies low in the window.
        assertFalse(allowed(500f, 1700f, viewTop = 250))
        assertTrue(allowed(500f, 1650f, viewTop = 250))
        val wide = BrightnessStart.allowed(20f, 1000f, 0f, 0f, 1000f, 2000f, 0, 0, 1000, 2000, gestures)
        assertFalse(wide)
        assertTrue(BrightnessStart.allowed(20f, 1000f, 0f, 0f, 1000f, 2000f, 0, 0, 1000, 2000, BrightnessStart.Edges.NONE))
        // A window not laid out yet has no gesture areas to keep clear of.
        assertTrue(BrightnessStart.allowed(20f, 1000f, 0f, 0f, 1000f, 2000f, 0, 0, 0, 0, gestures))
    }

    private val drags = ArrayList<String>()

    /** A page view 1000 x 2000 showing a [w] x [h] image fitted to the screen, its brightness drag recorded. */
    private fun page(w: Int, h: Int): ZoomImageView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = ZoomImageView(activity).apply {
            gestureEdges = { gestures }
            verticalDrag = object : ZoomImageView.VerticalDrag {
                override fun start() {
                    drags += "start"
                }

                override fun move(dy: Float) = Unit

                override fun end() {
                    drags += "end"
                }
            }
        }
        activity.setContentView(FrameLayout(activity).apply { addView(v, FrameLayout.LayoutParams(1000, 2000)) })
        v.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1000, 2000)
        v.setImageBitmap(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    /** A finger going down at ([x], [y]) and swiping up 400 px. */
    private fun swipeUp(v: ZoomImageView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        for (i in 1..8) v.onTouchEvent(MotionEvent.obtain(t, t + 10L * i, MotionEvent.ACTION_MOVE, x, y - 50f * i, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, x, y - 400f, 0))
    }

    @Test
    fun aSwipeUpFromTheBottomEdgeLeavesBrightnessAlone() {
        val v = page(1000, 2000)
        swipeUp(v, 500f, 1990f)
        assertEquals(emptyList<String>(), drags)
        swipeUp(v, 10f, 1000f)
        assertEquals(emptyList<String>(), drags)
        swipeUp(v, 500f, 1000f)
        assertEquals(listOf("start", "end"), drags)
    }

    @Test
    fun aSwipeOnTheLetterboxLeavesBrightnessAlone() {
        // A wide page fitted to the screen: 1000 x 500, bands above and below it.
        val v = page(2000, 1000)
        swipeUp(v, 500f, 300f)
        assertEquals(emptyList<String>(), drags)
        swipeUp(v, 500f, 1000f)
        assertEquals(listOf("start", "end"), drags)
    }
}
