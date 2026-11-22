package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.OfflineDownloads
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings changed while the reader is open apply to it without recreating it: changed
 * while it shows (a sheet or dialog over it), or in Settings on top of it and seen on return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityLiveSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Turning AI on asks for its default model: kept off the network. */
    @get:Rule
    val downloads = OfflineDownloads()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private var controller: ActivityController<ViewerActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ActivityController<ViewerActivity> {
        val pages = (0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.zip(File(tmp.newFolder("shelf"), "Book A.cbz"), *pages.toTypedArray())
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val c = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        controller = c
        val viewer = c.get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open and all its open started done") { progress.visibility != View.VISIBLE && viewer.openSettled() }
        return c
    }

    /** Settings on top of the reader: [change] is made while it is stopped, then it shows again. */
    private fun ActivityController<ViewerActivity>.away(change: () -> Unit) {
        pause().stop()
        change()
        start().resume()
        idle()
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    /** The page images in the paged list. */
    private fun ViewerActivity.pages(): List<ZoomImageView> =
        descendants(findViewById<ViewPager2>(R.id.viewer_pager)).filterIsInstance<ZoomImageView>().also { assertTrue("no page shown", it.isNotEmpty()) }

    // ---------------------------------------------------------------- reader gestures

    @Test
    fun doubleTapTurnedOffWhileReadingAppliesToThePagesShown() {
        val viewer = launch().get()
        val adapter = viewer.pageAdapter
        val shown = viewer.pages()
        assertTrue(shown.all { it.doubleTapEnabled })
        prefs.doubleTapZoom = false
        idle()
        // The same pages, changed in place.
        assertSame(adapter, viewer.pageAdapter)
        assertFalse(shown.any { it.doubleTapEnabled })
        assertFalse(viewer.pages().any { it.doubleTapEnabled })
    }

    @Test
    fun doubleTapChangedInSettingsAppliesOnReturnEachWay() {
        val c = launch()
        c.away { prefs.doubleTapZoom = false }
        assertFalse(c.get().pages().any { it.doubleTapEnabled })
        c.away { prefs.doubleTapZoom = true }
        assertTrue(c.get().pages().all { it.doubleTapEnabled })
    }

    @Test
    fun momentumScrollingFollowsItsSettingInPlace() {
        val c = launch()
        val adapter = c.get().pageAdapter
        assertTrue(c.get().pages().all { it.flingEnabled })
        c.away { prefs.fling = false }
        assertSame(adapter, c.get().pageAdapter)
        assertFalse(c.get().pages().any { it.flingEnabled })
    }

    @Test
    fun brightnessGestureFollowsItsSettingInPlace() {
        val c = launch()
        val adapter = c.get().pageAdapter
        assertTrue(c.get().pages().all { it.verticalDrag != null })
        prefs.brightnessGesture = false
        idle()
        assertSame(adapter, c.get().pageAdapter)
        assertTrue(c.get().pages().all { it.verticalDrag == null })
        c.away { prefs.brightnessGesture = true }
        assertTrue(c.get().pages().all { it.verticalDrag != null })
    }

    // ---------------------------------------------------------------- reader layout

    @Test
    fun fitAndPageGapApplyWhileReading() {
        val viewer = launch().get()
        val adapter = viewer.pageAdapter!!
        assertEquals(ZoomImageView.FitMode.SCREEN, adapter.fitMode)
        prefs.fit = "width"
        prefs.pageSpacing = 16
        idle()
        assertSame(adapter, viewer.pageAdapter)
        assertEquals(ZoomImageView.FitMode.WIDTH, adapter.fitMode)
        assertEquals((16 * viewer.resources.displayMetrics.density).toInt(), adapter.spacingPx)
    }

    @Test
    fun continuousLayoutChosenInSettingsShowsOnReturn() {
        val c = launch()
        val vertical = c.get().findViewById<RecyclerView>(R.id.viewer_vertical)
        assertEquals(View.GONE, vertical.visibility)
        // Continuous reads top to bottom only.
        c.away {
            prefs.readingDir = "ttb"
            prefs.pageMode = "continuous"
        }
        assertEquals(View.VISIBLE, vertical.visibility)
        assertEquals(View.GONE, c.get().findViewById<View>(R.id.viewer_pager).visibility)
    }

    @Test
    fun readAheadChangedInSettingsRebuildsThePages() {
        val c = launch()
        val before = c.get().pageAdapter
        c.away { prefs.preload = 0 }
        assertNotNull(c.get().pageAdapter)
        assertTrue("built anew for the new read-ahead", before !== c.get().pageAdapter)
        // Away and back with nothing changed: the pages stay as they are.
        val now = c.get().pageAdapter
        c.away {}
        assertSame(now, c.get().pageAdapter)
    }

    // ---------------------------------------------------------------- AI

    /** Counts the adapter's whole-range rebinds: [PageAdapter.aiChanged] rebinds every item. */
    private class Rebinds(private val adapter: RecyclerView.Adapter<*>) : RecyclerView.AdapterDataObserver() {
        var count = 0
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            if (positionStart == 0 && itemCount == adapter.itemCount) count++
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onItemRangeChanged(positionStart, itemCount, null)
    }

    @Test
    fun aiTurnedOnInSettingsProcessesThePagesShownOnReturn() {
        val c = launch()
        val adapter = c.get().pageAdapter!!
        val rebinds = Rebinds(adapter).also { adapter.registerAdapterDataObserver(it) }
        c.away { prefs.aiEnabled = true }
        // The same pages, bound again to go through the chain now chosen.
        assertSame(adapter, c.get().pageAdapter)
        assertEquals(1, rebinds.count)
        c.away { prefs.aiEnabled = false }
        assertEquals(2, rebinds.count)
    }

    @Test
    fun aiDotTurnedOnInSettingsShowsOnReturn() {
        val c = launch()
        val dot = c.get().findViewById<View>(R.id.ai_dot)
        assertEquals(View.GONE, dot.visibility)
        c.away { prefs.aiDot = true }
        assertEquals(View.INVISIBLE, dot.visibility)
    }
}
