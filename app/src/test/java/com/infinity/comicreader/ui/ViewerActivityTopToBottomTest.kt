package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Paged top to bottom: an upright pager, swiped or tapped top and bottom, one page a screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityTopToBottomTest {

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
        prefs.readingDir = "ttb"
        book = Fixtures.zip(File(tmp.newFolder("shelf"), "Book 01.cbz"), *(0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    private fun ViewerActivity.pager() = findViewById<ViewPager2>(R.id.viewer_pager)

    /** The page image of the pager's current item. */
    private fun ViewPager2.shownImage(): ZoomImageView {
        val item = (getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(currentItem)!!.itemView
        return descendants(item).filterIsInstance<ZoomImageView>().first()
    }

    private fun descendants(v: View): List<View> =
        if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else listOf(v)

    @Test
    fun pagesStandUpright() {
        val pager = launch().pager()
        assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(ViewPager2.ORIENTATION_VERTICAL, pager.orientation)
        assertEquals(View.LAYOUT_DIRECTION_LTR, pager.layoutDirection)
    }

    @Test
    fun theBottomZoneTurnsOnAndTheTopOneBack() {
        val pager = launch().pager()
        pager.shownImage().onTap!!(0.5f, 0.9f)
        idle()
        assertEquals(1, pager.currentItem)
        pager.shownImage().onTap!!(0.5f, 0.1f)
        idle()
        assertEquals(0, pager.currentItem)
    }

    @Test
    fun theDefaultLeftAndRightTurnTopAndBottom() {
        prefs.tapZones = "left_right"
        val pager = launch().pager()
        pager.shownImage().onTap!!(0.9f, 0.5f)
        idle()
        assertEquals(0, pager.currentItem)
        pager.shownImage().onTap!!(0.5f, 0.9f)
        idle()
        assertEquals(1, pager.currentItem)
    }

    @Test
    fun otherZonesSetInSettingsStay() {
        prefs.tapZones = "right_left"
        val pager = launch().pager()
        pager.shownImage().onTap!!(0.5f, 0.9f)
        idle()
        assertEquals(0, pager.currentItem)
        pager.shownImage().onTap!!(0.1f, 0.5f)
        idle()
        assertEquals(1, pager.currentItem)
    }

    @Test
    fun pagesNeverPairAndDragsNeverChangeBrightness() {
        prefs.twoPage = TwoPage.ON
        val pager = launch().pager()
        assertEquals(4, pager.adapter!!.itemCount)
        assertNull(pager.shownImage().verticalDrag)
    }

    @Test
    fun leftToRightPagesSidewaysWithTheBrightnessDrag() {
        prefs.readingDir = "ltr"
        val pager = launch().pager()
        assertEquals(ViewPager2.ORIENTATION_HORIZONTAL, pager.orientation)
        assertNotNull(pager.shownImage().verticalDrag)
    }
}
