package com.infinity.comicreader.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PanelTunerViewTest {
    /** The animator duration scale, 0 when animations are off ("Remove animations"). */
    private fun scale(f: Float) {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, f)
    }

    @After
    fun tearDown() = scale(1f)

    private fun shown(): Pair<org.robolectric.android.controller.ActivityController<Activity>, PanelTunerView> {
        val c = Robolectric.buildActivity(Activity::class.java).create()
        val view = PanelTunerView(c.get())
        c.get().setContentView(FrameLayout(c.get()).apply { addView(view, FrameLayout.LayoutParams(600, 400)) })
        c.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return c to view
    }

    @Test
    fun squareAtTheSmallerSide() {
        val (_, view) = shown()
        assertEquals(400, view.measuredWidth)
        assertEquals(400, view.measuredHeight)
    }

    @Test
    fun movesOnlyWhileShownAndNotHeld() {
        val (c, view) = shown()
        // The window shows (Robolectric's windows stay hidden: what the system tells the view, told by hand).
        view.onVisibilityAggregated(true)
        assertTrue(view.playing)
        view.held = true
        assertFalse(view.playing)
        view.held = false
        assertTrue(view.playing)
        view.onVisibilityAggregated(false)
        assertFalse(view.playing)
        view.onVisibilityAggregated(true)
        assertTrue(view.playing)
        c.get().setContentView(View(c.get()))
        assertFalse(view.playing)
    }

    @Test
    fun framesComeAtTheCap() {
        val (_, view) = shown()
        view.onVisibilityAggregated(true)
        val canvas = Canvas(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888))
        val looper = shadowOf(Looper.getMainLooper())
        repeat(3) {
            view.draw(canvas)
            assertFalse(view.isDirty)
            // The next frame is asked for a frame's length after this one began, not sooner.
            looper.idleFor(Duration.ofMillis(1000L / PanelTunerView.FPS - 10))
            assertFalse(view.isDirty)
            looper.idleFor(Duration.ofMillis(20))
            assertTrue(view.isDirty)
        }
        // Held: no frame asked for.
        view.draw(canvas)
        view.held = true
        looper.idleFor(Duration.ofMillis(200))
        assertFalse(view.isDirty)
    }

    @Test
    fun stillWhereAnimationsAreOff() {
        scale(0f)
        val (_, view) = shown()
        view.onVisibilityAggregated(true)
        assertFalse(view.playing)
    }
}
