package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Fixtures
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
import org.robolectric.Shadows.shadowOf
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
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    private val groups = listOf(R.id.sheet_scope, R.id.sheet_page_mode, R.id.sheet_dir, R.id.sheet_fit, R.id.sheet_two_page, R.id.sheet_orientation)

    @Test
    fun everySingleChoiceShowsOneSelection() {
        val sheet = ReaderSheet(launch())
        for (id in groups) assertNotEquals(View.NO_ID, sheet.view<MaterialButtonToggleGroup>(id).checkedButtonId)
        assertEquals(R.id.chip_filter_none, sheet.view<ChipGroup>(R.id.sheet_filter).checkedChipId)
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
    fun aLongStripOffersNoSpreadsCropOrSpacing() {
        val sheet = ReaderSheet(launch())
        assertTrue(sheet.view<View>(R.id.btn_two_on).isEnabled)
        sheet.tap(R.id.btn_mode_longstrip)
        assertEquals("longstrip", prefs.pageMode)
        assertFalse(sheet.view<View>(R.id.btn_two_on).isEnabled)
        assertFalse(sheet.view<View>(R.id.btn_fit_screen).isEnabled)
        assertFalse(sheet.view<View>(R.id.sheet_auto_crop).isEnabled)
        assertFalse(sheet.view<View>(R.id.sheet_spacing).isEnabled)
        // Fit screen reads as fit width in a strip.
        assertEquals(R.id.btn_fit_width, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_fit).checkedButtonId)
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
}
