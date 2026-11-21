package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.Slider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.AutoScroll
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.idleUntil
import org.junit.Assert.assertEquals
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

/** Paged auto-scroll turns at the speed's pace, a bar along the bottom filling meanwhile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityAutoScrollTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        book = Fixtures.zip(File(tmp.newFolder("shelf"), "Book 01.cbz"), *(0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
    }

    /** Opens the book and waits until everything its open started is done: a late result would land mid-run on the fake clock. */
    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open and all its open started done") { progress.visibility != View.VISIBLE && viewer.openSettled() }
        assertTrue("book still opening", progress.visibility != View.VISIBLE && viewer.openSettled())
        return viewer
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aPageTurnsOnceAScreenHeightsTimeIsUpWithTheBarFillingMeanwhile() {
        prefs.autoSpeed = 6f
        val viewer = launch()
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        val bar = viewer.findViewById<View>(R.id.auto_progress)
        shadowOf(viewer).clickMenuItem(R.id.menu_auto_scroll)

        // 6 screens a minute: 10 s a page.
        pass(5_000)
        assertEquals(0, pager.currentItem)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(0.5f, bar.scaleX, 0.01f)
        pass(5_100)
        assertEquals(1, pager.currentItem)
        assertEquals(0f, bar.scaleX, 0.02f)

        // A page turned by hand gets its full time.
        pass(6_000)
        viewer.findViewById<View>(R.id.btn_prev_page).performClick()
        pass(5_000)
        assertEquals(0, pager.currentItem)
        assertEquals(0.5f, bar.scaleX, 0.02f)
        pass(5_200)
        assertEquals(1, pager.currentItem)

        shadowOf(viewer).clickMenuItem(R.id.menu_auto_scroll)
        assertEquals(View.GONE, bar.visibility)
    }

    @Test
    fun theLastPageGetsItsTimeBeforeTheRunEnds() {
        prefs.autoSpeed = 45f
        val viewer = launch()
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        val bar = viewer.findViewById<View>(R.id.auto_progress)
        shadowOf(viewer).clickMenuItem(R.id.menu_auto_scroll)
        pass(2 * AutoScroll.pageMs(45f) + 100)
        assertEquals(2, pager.currentItem)
        assertEquals(View.VISIBLE, bar.visibility)
        pass(AutoScroll.pageMs(45f))
        assertEquals(View.GONE, bar.visibility)
    }

    @Test
    fun theSheetSlidesThroughTheSpeedsAndNamesThePace() {
        val sheet = ReaderSheet(launch())
        val slider = sheet.view<Slider>(R.id.sheet_auto_speed)
        assertEquals((AutoScroll.SPEEDS.size - 1).toFloat(), slider.valueTo, 0f)
        assertEquals(AutoScroll.indexOf(AutoScroll.DEFAULT).toFloat(), slider.value, 0f)
        assertEquals("Auto-scroll speed · 3 screens/min · 20 s a page", sheet.view<TextView>(R.id.sheet_auto_speed_value).text.toString())
    }

    @Test
    fun paceTextReadsInSecondsOrMinutes() {
        val res = context.resources
        assertEquals("1 screen/min · 1 min a page", AutoScrollText.of(res, 1f))
        assertEquals("0.75 screens/min · 1 min 20 s a page", AutoScrollText.of(res, 0.75f))
        assertEquals("45 screens/min · 1.3 s a page", AutoScrollText.of(res, 45f))
        assertEquals("8 screens/min · 7.5 s a page", AutoScrollText.of(res, 8f))
        assertEquals("1.5 screens/min · 40 s a page", AutoScrollText.of(res, 1.5f))
    }
}
