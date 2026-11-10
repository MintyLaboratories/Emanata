package com.infinity.comicreader.ui

import android.app.Application
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The AI size slider: two independent handles that may cross, the upscale one floored at 50 %, the cleanup one down to "Always". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiSizeSliderTest {

    private lateinit var slider: AiSizeSlider
    private val changes = mutableListOf<Pair<AiThresholds, Boolean>>()

    @Before
    fun setUp() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_ComicReader)
        slider = AiSizeSlider(context)
        slider.onChange = { t, done -> changes += t to done }
        slider.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        slider.layout(0, 0, 1000, slider.measuredHeight)
    }

    private val top get() = 2f
    private val bottom get() = slider.height - 2f

    private fun pct(handle: AiSizeSlider.Handle) =
        if (handle == AiSizeSlider.Handle.UPSCALE) slider.thresholds.upscalePct else slider.thresholds.cleanupPct

    /** The x where [handle] lands on [pct], found by tapping along the axis; the handle is left there. */
    private fun xOf(pct: Int, handle: AiSizeSlider.Handle): Float {
        val y = if (handle == AiSizeSlider.Handle.UPSCALE) top else bottom
        for (x in 0..1000) {
            tap(x.toFloat(), y)
            if (pct(handle) == pct) return x.toFloat()
        }
        error("no x for $pct")
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private fun tap(x: Float, y: Float) {
        slider.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        slider.onTouchEvent(event(MotionEvent.ACTION_UP, x, y))
    }

    private fun drag(fromX: Float, toX: Float, y: Float, release: Boolean = true) {
        slider.onTouchEvent(event(MotionEvent.ACTION_DOWN, fromX, y))
        for (i in 1..10) slider.onTouchEvent(event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 10, y))
        if (release) slider.onTouchEvent(event(MotionEvent.ACTION_UP, toX, y))
    }

    @Test
    fun startsAtTheDefaults() {
        assertEquals(AiThresholds(100, 50), slider.thresholds)
    }

    @Test
    fun touchAboveTheTrackMovesUpscaleBelowMovesCleanup() {
        assertEquals(AiSizeSlider.Handle.UPSCALE, slider.handleAt(top))
        assertEquals(AiSizeSlider.Handle.CLEANUP, slider.handleAt(bottom))
        tap(999f, top)
        assertEquals(AiThresholds(200, 50), slider.thresholds)
        tap(999f, bottom)
        assertEquals(AiThresholds(200, 200), slider.thresholds)
    }

    @Test
    fun upscaleHandleStopsAtTheFloor() {
        drag(xOf(100, AiSizeSlider.Handle.UPSCALE), 0f, top)
        assertEquals(AiThresholds.UPSCALE_FLOOR, slider.thresholds.upscalePct)
        assertFalse(slider.step(AiSizeSlider.Handle.UPSCALE, -1))
        assertEquals(AiThresholds.UPSCALE_FLOOR, slider.thresholds.upscalePct)
    }

    @Test
    fun cleanupHandleGoesDownToAlways() {
        drag(xOf(50, AiSizeSlider.Handle.CLEANUP), 0f, bottom)
        assertEquals(AiThresholds.ALWAYS, slider.thresholds.cleanupPct)
        assertTrue(slider.step(AiSizeSlider.Handle.CLEANUP, 1))
        assertEquals(25, slider.thresholds.cleanupPct)
    }

    @Test
    fun handlesCrossEitherWay() {
        val clean150 = xOf(150, AiSizeSlider.Handle.CLEANUP)
        val up60 = xOf(60, AiSizeSlider.Handle.UPSCALE)
        assertEquals(AiThresholds(60, 150), slider.thresholds)
        drag(clean150, 0f, bottom)
        assertEquals(AiThresholds(60, AiThresholds.ALWAYS), slider.thresholds)
        drag(up60, 999f, top)
        assertEquals(AiThresholds(200, AiThresholds.ALWAYS), slider.thresholds)
    }

    @Test
    fun aDragReportsLiveAndDoneOnceOnRelease() {
        val from = xOf(100, AiSizeSlider.Handle.UPSCALE)
        changes.clear()
        drag(from, from + 200f, top, release = false)
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.none { it.second })
        slider.onTouchEvent(event(MotionEvent.ACTION_UP, from + 200f, top))
        assertEquals(slider.thresholds to true, changes.last())
        assertEquals(1, changes.count { it.second })
    }

    @Test
    fun aMostlyVerticalMoveIsNoDrag() {
        val x = xOf(100, AiSizeSlider.Handle.UPSCALE)
        changes.clear()
        slider.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, top))
        slider.onTouchEvent(event(MotionEvent.ACTION_MOVE, x + 30f, top + 60f))
        assertTrue(changes.isEmpty())
    }

    @Test
    fun turnedOffItIgnoresTouches() {
        slider.isEnabled = false
        tap(999f, top)
        assertEquals(AiThresholds(), slider.thresholds)
    }

    @Test
    fun arrowKeysStepTheUpscaleHandle() {
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(105, slider.thresholds.upscalePct)
        assertEquals(slider.thresholds to true, changes.last())
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(95, slider.thresholds.upscalePct)
    }

    @Test
    fun eachHandleIsAnAccessibleSlider() {
        val provider = slider.accessibilityNodeProvider!!
        val up = provider.createAccessibilityNodeInfo(AiSizeSlider.Handle.UPSCALE.ordinal)!!
        assertEquals("android.widget.SeekBar", up.className)
        assertEquals("Upscale", up.contentDescription)
        assertEquals("Above 100%", up.stateDescription)
        assertEquals(50f, up.rangeInfo!!.min, 0f)
        assertEquals(100f, up.rangeInfo!!.current, 0f)
        assertTrue(provider.performAction(AiSizeSlider.Handle.UPSCALE.ordinal, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(95, slider.thresholds.upscalePct)

        slider.thresholds = AiThresholds(100, AiThresholds.ALWAYS)
        val clean = provider.createAccessibilityNodeInfo(AiSizeSlider.Handle.CLEANUP.ordinal)!!
        assertEquals("Clean up", clean.contentDescription)
        assertEquals("Always", clean.stateDescription)
        assertFalse(provider.performAction(AiSizeSlider.Handle.CLEANUP.ordinal, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
    }
}
