package com.infinity.comicreader.ui

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
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
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
