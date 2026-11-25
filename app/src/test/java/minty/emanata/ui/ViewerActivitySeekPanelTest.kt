package minty.emanata.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.data.ResumeMode
import minty.emanata.reader.Fixtures
import minty.emanata.reader.ScaleFilter
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** The seek bar's page-preview strip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivitySeekPanelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(pages: Int = 12, page: Int = 5): ViewerActivity {
        val entries = (0 until pages).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.zip(File(tmp.newFolder(), "Book B.cbz"), *entries.toTypedArray())
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, page)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun ViewerActivity.panel() = findViewById<RecyclerView>(R.id.seek_panel)

    private fun ViewerActivity.seek() = findViewById<SeekBar>(R.id.viewer_seek)

    private fun ViewerActivity.pageText() = findViewById<TextView>(R.id.viewer_page_text).text.toString()

    /** Touches the seek bar at its thumb, then [then] before lifting. */
    private fun ViewerActivity.touchThumb(then: (x: Float, y: Float, t: Long) -> Unit) {
        val seek = seek()
        val track = seek.width - seek.paddingLeft - seek.paddingRight
        val x = seek.paddingLeft + track * seek.progress.toFloat() / seek.max
        val y = seek.height / 2f
        val t = SystemClock.uptimeMillis()
        seek.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        then(x, y, t)
    }

    @Test
    fun holdingJustOffTheThumbsCenterInALongBookNeverMovesThePage() {
        val viewer = launch(pages = 400, page = 200)
        val seek = viewer.seek()
        val thumbHalf = (seek.thumb?.intrinsicWidth ?: 0) / 2
        // Off center, yet on the thumb, by more than one item's width of track.
        val off = (thumbHalf - 1).toFloat()
        assertTrue(off * seek.max / (seek.width - seek.paddingLeft - seek.paddingRight) > 1f)
        viewer.touchThumb { x, y, t ->
            seek.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x + off, y, 0))
            idle()
            assertEquals("201 / 400", viewer.pageText())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SeekHold.HOLD_MS + 50))
            assertTrue(viewer.seekPanelShown)
            seek.dispatchTouchEvent(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_UP, x + off, y, 0))
        }
        idle()
        assertEquals("201 / 400", viewer.pageText())
    }

    @Test
    fun aDragFromTheThumbStillSeeks() {
        val viewer = launch(pages = 400, page = 200)
        val seek = viewer.seek()
        viewer.touchThumb { x, y, t ->
            // Past the slop: the kept down goes to the seek bar, which then follows the drag.
            seek.dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, x - 60f, y, 0))
            seek.dispatchTouchEvent(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_UP, x - 60f, y, 0))
        }
        idle()
        assertFalse(viewer.seekPanelShown)
        assertTrue(seek.progress < 199)
    }

    @Test
    fun holdingTheSeekBarStillOpensTheStripAtThePageOnScreen() {
        val viewer = launch()
        assertEquals("6 / 12", viewer.pageText())
        viewer.touchThumb { x, y, t ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SeekHold.HOLD_MS + 50))
            assertTrue(viewer.seekPanelShown)
            // The rest of the touch neither seeks nor closes the strip.
            viewer.seek().dispatchTouchEvent(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_MOVE, x + 200f, y, 0))
            viewer.seek().dispatchTouchEvent(MotionEvent.obtain(t, t + 520, MotionEvent.ACTION_UP, x + 200f, y, 0))
        }
        idle()
        assertTrue(viewer.seekPanelShown)
        assertEquals("6 / 12", viewer.pageText())
        assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.seek_scrim).visibility)
        val strip = viewer.panel().adapter as SeekPreviewAdapter
        assertEquals(12, strip.itemCount)
        assertEquals(5, strip.current)
        assertEquals((1..12).map { "$it" }, strip.cells.map { it.label })
    }

    @Test
    fun theStripTakesANewThumbnailFilter() {
        val viewer = launch()
        viewer.openSeekPanel()
        val first = viewer.panel().adapter
        viewer.onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(viewer.seekPanelShown)
        // Opened again unchanged, the strip keeps its previews.
        viewer.openSeekPanel()
        assertSame(first, viewer.panel().adapter)
        viewer.onBackPressedDispatcher.onBackPressed()
        prefs.scaleThumbs = ScaleFilter.HERMITE
        idle()
        viewer.openSeekPanel()
        assertNotSame(first, viewer.panel().adapter)
    }

    @Test
    fun draggingRightAwaySeeksWithoutTheStrip() {
        val viewer = launch()
        viewer.touchThumb { x, y, t ->
            viewer.seek().dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, x + 100f, y, 0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SeekHold.HOLD_MS + 50))
            viewer.seek().dispatchTouchEvent(MotionEvent.obtain(t, t + 600, MotionEvent.ACTION_UP, x + 100f, y, 0))
        }
        idle()
        assertFalse(viewer.seekPanelShown)
        assertTrue(viewer.pageText() != "6 / 12")
    }

    @Test
    fun backATapOutsideOrHidingTheBarsCloseIt() {
        val viewer = launch()
        viewer.openSeekPanel()
        viewer.onBackPressedDispatcher.onBackPressed()
        assertFalse(viewer.seekPanelShown)
        assertFalse(viewer.isFinishing)

        viewer.openSeekPanel()
        viewer.findViewById<View>(R.id.seek_scrim).performClick()
        assertFalse(viewer.seekPanelShown)
        assertEquals(View.GONE, viewer.findViewById<View>(R.id.seek_scrim).visibility)

        viewer.openSeekPanel()
        shadowOf(viewer).clickMenuItem(R.id.menu_auto_scroll)
        idle()
        assertFalse(viewer.seekPanelShown)

        // Closed, Back leaves the reader as before.
        viewer.onBackPressedDispatcher.onBackPressed()
        assertTrue(viewer.isFinishing)
    }

    @Test
    fun aTapOnTheToolbarClosesItToo() {
        val viewer = launch()
        viewer.openSeekPanel()
        val bar = viewer.findViewById<View>(R.id.viewer_toolbar)
        val at = IntArray(2).also { bar.getLocationOnScreen(it) }
        val x = at[0] + bar.width / 2f
        val y = at[1] + bar.height / 2f
        val t = SystemClock.uptimeMillis()
        viewer.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        viewer.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        idle()
        assertFalse(viewer.seekPanelShown)
        assertEquals(View.GONE, viewer.findViewById<View>(R.id.seek_scrim).visibility)
    }

    @Test
    fun tappingAThumbnailJumpsThere() {
        val viewer = launch()
        viewer.openSeekPanel()
        idle()
        val panel = viewer.panel()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        panel.layout(0, 0, 2000, panel.measuredHeight)
        val holder = (0 until panel.childCount).map { panel.getChildViewHolder(panel.getChildAt(it)) }
            .first { it.bindingAdapterPosition == 7 }
        holder.itemView.performClick()
        idle()
        assertEquals("8 / 12", viewer.pageText())
        assertFalse(viewer.seekPanelShown)
    }

    @Test
    fun theStripFollowsThePageAndTheReadingDirection() {
        prefs.readingDir = "rtl"
        val viewer = launch()
        viewer.openSeekPanel()
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.panel().layoutDirection)
        viewer.findViewById<View>(R.id.btn_next_page).performClick()
        idle()
        assertEquals(6, (viewer.panel().adapter as SeekPreviewAdapter).current)
        assertTrue(viewer.seekPanelShown)
    }
}
