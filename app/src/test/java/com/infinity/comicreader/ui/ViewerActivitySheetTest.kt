package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.data.WidePages
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The reader settings sheet: one choice always selected, and each control reaching the reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivitySheetTest {

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
        book = Fixtures.zip(File(tmp.newFolder("shelf"), "Book 01.cbz"), *(0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
    }

    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    private val groups = listOf(R.id.sheet_scope, R.id.sheet_page_mode, R.id.sheet_dir, R.id.sheet_fit, R.id.sheet_two_page, R.id.sheet_orientation, R.id.sheet_wide_pages)

    @Test
    fun everySingleChoiceShowsOneSelection() {
        val sheet = ReaderSheet(launch())
        for (id in groups) assertNotEquals(View.NO_ID, sheet.view<MaterialButtonToggleGroup>(id).checkedButtonId)
        assertEquals(R.id.chip_filter_none, sheet.view<ChipGroup>(R.id.sheet_filter).checkedChipId)
    }

    @Test
    fun aFreshSheetMergesSpreadsAndRotatesWidePages() {
        val sheet = ReaderSheet(launch())
        assertEquals(R.id.btn_spreads_merge, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_spreads).checkedButtonId)
        assertEquals(R.id.btn_wide_image, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_wide_pages).checkedButtonId)
    }

    @Test
    fun theSheetPicksWhatWidePagesDo() {
        val sheet = ReaderSheet(launch())
        sheet.tap(R.id.btn_wide_orientation)
        assertEquals(WidePages.ORIENTATION, prefs.widePages)
        sheet.tap(R.id.btn_wide_nothing)
        assertEquals(WidePages.NOTHING, prefs.widePages)
        assertEquals(R.id.btn_wide_nothing, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_wide_pages).checkedButtonId)
    }

    @Test
    fun tappingTheSelectedFilterAgainKeepsIt() {
        val sheet = ReaderSheet(launch())
        sheet.tap(R.id.chip_filter_invert)
        assertEquals(Filters.INVERT, prefs.filter)
        sheet.tap(R.id.chip_filter_invert)
        assertTrue(sheet.view<Chip>(R.id.chip_filter_invert).isChecked)
        assertEquals(Filters.INVERT, prefs.filter)
        sheet.tap(R.id.chip_filter_none)
        assertEquals(Filters.NONE, prefs.filter)
        assertFalse(sheet.view<Chip>(R.id.chip_filter_invert).isChecked)
    }

    @Test
    fun tappingTheSelectedSegmentAgainKeepsIt() {
        val sheet = ReaderSheet(launch())
        sheet.tap(R.id.btn_fit_height).tap(R.id.btn_fit_height)
        assertEquals("height", prefs.fit)
        assertEquals(R.id.btn_fit_height, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_fit).checkedButtonId)
    }

    @Test
    fun aNewFitKeepsThePagesAdapter() {
        val viewer = launch()
        val pager = viewer.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewer_pager)
        val adapter = pager.adapter
        ReaderSheet(viewer).tap(R.id.btn_fit_height).tap(R.id.btn_fit_width).tap(R.id.btn_fit_stretch)
        assertEquals("stretch", prefs.fit)
        assertTrue(pager.adapter === adapter)
    }

    @Test
    fun continuousIsOfferedTopToBottomOnly() {
        val viewer = launch()
        val sheet = ReaderSheet(viewer)
        assertTrue(sheet.view<View>(R.id.btn_two_on).isEnabled)
        assertFalse(sheet.view<View>(R.id.btn_mode_continuous).isEnabled)
        sheet.tap(R.id.btn_dir_ttb)
        assertEquals(FolderLayout.Direction("ttb"), Library(context).folderLayout(book.parent!!))
        // Paged top to bottom: an upright pager, no two-page or split spreads.
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(ViewPager2.ORIENTATION_VERTICAL, pager.orientation)
        assertFalse(sheet.view<View>(R.id.btn_two_on).isEnabled)
        assertFalse(sheet.view<View>(R.id.btn_spreads_split).isEnabled)
        assertTrue(sheet.view<View>(R.id.btn_mode_continuous).isEnabled)
        sheet.tap(R.id.btn_mode_continuous)
        assertEquals("continuous", prefs.pageMode)
        assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.viewer_vertical).visibility)
        assertFalse(sheet.view<View>(R.id.btn_fit_screen).isEnabled)
        // A column crops and spaces its pages as it is told.
        assertTrue(sheet.view<View>(R.id.sheet_auto_crop).isEnabled)
        assertTrue(sheet.view<View>(R.id.sheet_spacing).isEnabled)
        // Fit screen reads as fit width in a column.
        assertEquals(R.id.btn_fit_width, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_fit).checkedButtonId)
        // Left to right pages again, keeping continuous for books read top to bottom.
        sheet.tap(R.id.btn_dir_ltr)
        assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(ViewPager2.ORIENTATION_HORIZONTAL, pager.orientation)
        assertEquals("continuous", prefs.pageMode)
        assertEquals(R.id.btn_mode_paged, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_page_mode).checkedButtonId)
    }

    @Test
    fun twoPageAndOrientationReachTheReader() {
        val viewer = launch()
        val sheet = ReaderSheet(viewer)
        sheet.tap(R.id.btn_two_landscape)
        assertEquals(TwoPage.LANDSCAPE, prefs.twoPage)
        sheet.tap(R.id.btn_orient_landscape)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, viewer.requestedOrientation)
        sheet.tap(R.id.sheet_keep_on)
        assertTrue(prefs.keepScreenOn)
    }

    @Test
    fun thePageShowsUndimmedBehindTheSheet() {
        val sheet = ReaderSheet(launch())
        assertEquals(0f, sheet.dialog.window!!.attributes.dimAmount, 0f)
    }

    @Test
    fun theSheetKeepsHalfTheScreenWhenTheTabletTurns() {
        val viewer = launch()
        val sheet = ReaderSheet(viewer)
        val portrait = sheet.dialog.behavior.peekHeight
        RuntimeEnvironment.setQualifiers("land")
        viewer.onConfigurationChanged(viewer.resources.configuration)
        val half = viewer.resources.displayMetrics.heightPixels / 2
        assertNotEquals(portrait, half)
        assertEquals(half, sheet.dialog.behavior.peekHeight)
    }
}
