package minty.emanata.ui

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GridPickerTest {

    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Emanata)
    private val picker = GridPicker(context)
    private var picked: Pair<Int, Int>? = null

    /** The grid's side and the picker's full height, the label below the grid. */
    private var grid = 0
    private var height = 0
    private val width = 1000

    /** Left edge of the centered grid. */
    private val left get() = (width - grid) / 2f

    @Before
    fun setUp() {
        picker.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        grid = picker.measuredWidth
        height = picker.measuredHeight
        picker.layout(0, 0, width, height)
        picker.onPick = { c, r -> picked = c to r }
    }

    private fun touch(action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        return picker.onTouchEvent(event).also { event.recycle() }
    }

    private fun tap(x: Float, y: Float) {
        if (touch(MotionEvent.ACTION_DOWN, x, y)) touch(MotionEvent.ACTION_UP, x, y)
    }

    @Test
    fun tappingACellPicksIt() {
        // The middle of seven cells is the fourth: 3 x 3.
        tap(left + grid / 2f, grid / 2f)
        assertEquals(3 to 3, picked)
        tap(left + grid - 1f, grid - 1f)
        assertEquals(6 to 6, picked)
    }

    @Test
    fun tapsOnTheLabelOrBesideTheGridPickNothing() {
        tap(left + grid / 2f, grid + (height - grid) / 2f)
        tap(5f, grid / 2f)
        tap(width - 5f, grid / 2f)
        assertNull(picked)
        assertEquals(0, picker.columns)
        assertEquals(0, picker.rows)
    }

    @Test
    fun releasingOutsideTheGridCancels() {
        touch(MotionEvent.ACTION_DOWN, left + grid / 2f, grid / 2f)
        touch(MotionEvent.ACTION_MOVE, left + grid / 2f, grid + 10f)
        touch(MotionEvent.ACTION_UP, left + grid / 2f, grid + 10f)
        assertNull(picked)
    }
}
