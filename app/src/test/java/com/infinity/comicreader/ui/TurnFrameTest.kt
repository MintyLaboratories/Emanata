package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A page turned to fill a portrait screen: laid out at the screen's size swapped and
 * rotated, its top to the right (or left); pinches and pans follow the picture as shown,
 * the brightness drag and the pager's swipes follow the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h2000dp-mdpi")
class TurnFrameTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** A parent that records whether a child keeps touches from it, as the pager would be kept. */
    private class Parent(context: Application) : FrameLayout(context) {
        var disallowed = false

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowed = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    private class RecordingDrag : ZoomImageView.VerticalDrag {
        val events = ArrayList<String>()
        override fun start() { events += "start" }
        override fun move(dy: Float) { events += "move $dy" }
        override fun end() { events += "end" }
    }

    private class Recorder(context: Application) : View(context) {
        var at: Pair<Float, Float>? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) at = event.x to event.y
            return true
        }
    }

    /**
     * [child] in a [TurnFrame] turned [turn], in a 1000 x 2000 (portrait) [Parent], laid
     * out; [shown] in an activity's window, which the brightness drag looks at.
     */
    private fun framed(child: View, turn: Int, shown: Boolean = false): Parent {
        val frame = TurnFrame(context).apply { this.turn = turn }
        frame.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val parent = Parent(context)
        parent.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (shown) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            activity.setContentView(parent, ViewGroup.LayoutParams(1000, 2000))
            shadowOf(Looper.getMainLooper()).idle()
        }
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 1000, 2000)
        return parent
    }

    /** Where [child]'s own point ([x], [y]) shows in its frame. */
    private fun shownAt(child: View, x: Float, y: Float): Pair<Float, Float> {
        val p = floatArrayOf(x, y)
        child.matrix.mapPoints(p)
        return (p[0] + child.left) to (p[1] + child.top)
    }

    private fun assertAt(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.5f)
        assertEquals(expected.second, actual.second, 0.5f)
    }

    private fun event(t: Long, action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(t, t, action, x, y, 0)

    @Test
    fun aTurnedChildFillsTheFrameAtItsSizeSwappedItsTopToTheRight() {
        val child = View(context)
        framed(child, turn = 1)
        assertEquals(2000, child.width)
        assertEquals(1000, child.height)
        // Its top-left corner at the frame's top right, its bottom-right at the bottom left.
        assertAt(1000f to 0f, shownAt(child, 0f, 0f))
        assertAt(0f to 2000f, shownAt(child, 2000f, 1000f))
    }

    @Test
    fun turnedTheOtherWayItsTopGoesToTheLeft() {
        val child = View(context)
        framed(child, turn = -1)
        assertAt(0f to 2000f, shownAt(child, 0f, 0f))
        assertAt(1000f to 0f, shownAt(child, 2000f, 1000f))
    }

    @Test
    fun uprightItFillsTheFrameAsItIs() {
        val child = View(context)
        val parent = framed(child, turn = 1)
        val frame = parent.getChildAt(0) as TurnFrame
        frame.turn = 0
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 1000, 2000)
        assertEquals(1000, child.width)
        assertEquals(2000, child.height)
        assertEquals(0f, child.rotation, 0f)
        assertAt(0f to 0f, shownAt(child, 0f, 0f))
    }

    @Test
    fun aTouchReachesTheTurnedChildInItsOwnCoordinates() {
        val child = Recorder(context)
        val parent = framed(child, turn = 1)
        val t = SystemClock.uptimeMillis()
        // Near the frame's top right: near the child's own top left.
        parent.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 900f, 100f))
        assertAt(100f to 100f, child.at!!)
    }

    @Test
    fun deviceAxesOfAMoveInATurnedView() {
        // The child's right is the device's down when turned clockwise, its down the device's left.
        assertEquals(0f, TurnFrame.deviceX(1, 10f, 0f), 0f)
        assertEquals(10f, TurnFrame.deviceY(1, 10f, 0f), 0f)
        assertEquals(-10f, TurnFrame.deviceX(1, 0f, 10f), 0f)
        assertEquals(0f, TurnFrame.deviceY(-1, 0f, 10f), 0f)
        assertEquals(10f, TurnFrame.deviceX(-1, 0f, 10f), 0f)
        assertEquals(-10f, TurnFrame.deviceY(-1, 10f, 0f), 0f)
        assertEquals(10f, TurnFrame.deviceX(0, 10f, 5f), 0f)
        assertEquals(5f, TurnFrame.deviceY(0, 10f, 5f), 0f)
    }

    /** A wide page fitted in a turned frame, as "Rotate image" shows one. */
    private fun turnedPage(turn: Int): Pair<Parent, ZoomImageView> {
        val image = ZoomImageView(context).apply {
            this.turn = turn
            gestureEdges = { BrightnessStart.Edges.NONE }
        }
        val parent = framed(image, turn, shown = true)
        image.setImageBitmap(Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888))
        image.setFitMode(ZoomImageView.FitMode.SCREEN)
        return parent to image
    }

    @Test
    fun aDragDownTheDeviceMovesBrightnessOnATurnedPage() {
        for (turn in listOf(1, -1)) {
            val (parent, image) = turnedPage(turn)
            val drag = RecordingDrag()
            image.verticalDrag = drag
            val t = SystemClock.uptimeMillis()
            parent.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 500f, 1000f))
            parent.dispatchTouchEvent(event(t + 16, MotionEvent.ACTION_MOVE, 500f, 1060f))
            parent.dispatchTouchEvent(event(t + 32, MotionEvent.ACTION_MOVE, 500f, 1090f))
            parent.dispatchTouchEvent(event(t + 48, MotionEvent.ACTION_UP, 500f, 1090f))
            assertEquals("turn $turn", listOf("start", "move 30.0", "end"), drag.events)
        }
    }

    @Test
    fun aDragAcrossTheDeviceGoesToThePagerOnATurnedPage() {
        for (turn in listOf(1, -1)) {
            val (parent, image) = turnedPage(turn)
            val drag = RecordingDrag()
            image.verticalDrag = drag
            val t = SystemClock.uptimeMillis()
            parent.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 500f, 1000f))
            parent.dispatchTouchEvent(event(t + 16, MotionEvent.ACTION_MOVE, 540f, 1005f))
            parent.dispatchTouchEvent(event(t + 32, MotionEvent.ACTION_MOVE, 600f, 1010f))
            assertFalse("turn $turn", parent.disallowed)
            assertTrue("turn $turn", drag.events.isEmpty())
        }
    }

    @Test
    fun withoutABrightnessDragAFittedTurnedPageLeavesEveryTouchToThePager() {
        val (parent, _) = turnedPage(1)
        val t = SystemClock.uptimeMillis()
        parent.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 500f, 1000f))
        assertFalse(parent.disallowed)
    }

    @Test
    fun aPanMovesTheTurnedPictureWithTheFinger() {
        val (parent, image) = turnedPage(1)
        image.zoomTo(3f, 1000f, 500f)
        val before = FloatArray(9).also { image.imageMatrix.getValues(it) }
        val t = SystemClock.uptimeMillis()
        parent.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 500f, 1000f))
        parent.dispatchTouchEvent(event(t + 16, MotionEvent.ACTION_MOVE, 550f, 1000f))
        parent.dispatchTouchEvent(event(t + 32, MotionEvent.ACTION_MOVE, 600f, 1000f))
        parent.dispatchTouchEvent(event(t + 48, MotionEvent.ACTION_UP, 600f, 1000f))
        val after = FloatArray(9).also { image.imageMatrix.getValues(it) }
        // 100 px right on the device is 100 px up the turned picture.
        assertEquals(before[Matrix.MTRANS_X], after[Matrix.MTRANS_X], 0.5f)
        assertEquals(before[Matrix.MTRANS_Y] - 100f, after[Matrix.MTRANS_Y], 0.5f)
    }
}
