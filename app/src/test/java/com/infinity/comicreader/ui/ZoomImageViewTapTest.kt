package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** A tap turns the page as the finger lifts, unless double-tap zoom makes it wait for a second tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZoomImageViewTapTest {
    private val taps = ArrayList<Pair<Float, Float>>()

    private fun page(doubleTap: Boolean): ZoomImageView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = ZoomImageView(activity).apply {
            doubleTapEnabled = doubleTap
            onTap = { x, y -> taps += x to y }
        }
        activity.setContentView(FrameLayout(activity).apply { addView(v, FrameLayout.LayoutParams(1000, 2000)) })
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, 1000, 2000)
        v.setImageBitmap(Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888))
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun tap(v: ZoomImageView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun withoutDoubleTapATapActsAsTheFingerLifts() {
        val v = page(doubleTap = false)
        tap(v, 900f, 1000f)
        assertEquals(listOf(0.9f to 0.5f), taps)
        pass(ViewConfiguration.getDoubleTapTimeout() + 100L)
        assertEquals(1, taps.size)
    }

    @Test
    fun withoutDoubleTapQuickTapsEachAct() {
        val v = page(doubleTap = false)
        tap(v, 900f, 1000f)
        pass(80)
        tap(v, 900f, 1000f)
        assertEquals(2, taps.size)
    }

    @Test
    fun withDoubleTapATapWaitsForNoSecondOne() {
        val v = page(doubleTap = true)
        tap(v, 900f, 1000f)
        assertEquals(0, taps.size)
        pass(ViewConfiguration.getDoubleTapTimeout() + 100L)
        assertEquals(1, taps.size)
    }

    @Test
    fun doubleTapTurnedOffLaterActsAtOnce() {
        val v = page(doubleTap = true)
        v.doubleTapEnabled = false
        tap(v, 100f, 1000f)
        assertEquals(listOf(0.1f to 0.5f), taps)
    }
}
