package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upstream's settings read into today's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpstreamBackupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun stored() = PreferenceManager.getDefaultSharedPreferences(context).all

    /** Prefs restored from upstream's [values], as a backup restore does. */
    private fun restored(vararg values: Pair<String, Any>): Prefs {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.importAll(values.toMap())
        UpstreamBackup.restore(values.toMap(), prefs)
        return prefs
    }

    @Test
    fun theSortGoesToBrowseAndFavorites() {
        val p = restored("pref_sort" to "size", "pref_sort_dir" to "desc")
        assertEquals(SortLevel(SortKey.SIZE), p.sort(Prefs.TAB_BROWSE).by)
        assertEquals(SortLevel(SortKey.SIZE), p.sort(Prefs.TAB_FAVORITES).by)
        assertEquals(TabSort.defaultFor(Prefs.TAB_HISTORY), p.sort(Prefs.TAB_HISTORY))
        assertEquals(SortLevel(SortKey.ADDED, flipped = true), restored("pref_sort" to "date").sort(Prefs.TAB_BROWSE).by)
        // Upstream's type order was folder, image, archive, PDF: ascending put images first.
        assertEquals(SortLevel(SortKey.TYPE, flipped = true), restored("pref_sort" to "type", "pref_sort_dir" to "asc").sort(Prefs.TAB_BROWSE).by)
        assertEquals(SortLevel(SortKey.TYPE), restored("pref_sort" to "type", "pref_sort_dir" to "desc").sort(Prefs.TAB_BROWSE).by)
        assertEquals(SortLevel(SortKey.NAME, flipped = true), restored("pref_sort_dir" to "desc").sort(Prefs.TAB_FAVORITES).by)
    }

    @Test
    fun theDefaultSortLeavesTheTabsAtTheirDefaults() {
        val p = restored("pref_sort" to "name", "pref_sort_dir" to "asc")
        assertEquals(TabSort.defaultFor(Prefs.TAB_BROWSE), p.sort(Prefs.TAB_BROWSE))
        assertFalse(stored().keys.any { it.startsWith("pref_sort") })
    }

    @Test
    fun tapZonesSwitchBecomesALayout() {
        assertEquals("left_right", restored("pref_tap_zones" to true).tapZones)
        assertEquals("disabled", restored("pref_tap_zones" to false).tapZones)
        assertFalse("pref_tap_zones" in stored())
    }

    @Test
    fun overlayBrightnessBecomesTheBrightnessAdjustment() {
        assertEquals(Adjustments(brightness = -50), restored("pref_brightness" to 50).defaultAdjustments)
        assertEquals(Adjustments(), restored("pref_brightness" to 100).defaultAdjustments)
        assertEquals(Adjustments(brightness = -80), restored("pref_brightness" to 10).defaultAdjustments)
    }

    @Test
    fun resumeSwitchesBecomeOneSetting() {
        assertEquals(ResumeMode.NO, restored("pref_resume" to false, "pref_ask_resume" to true).resumeMode)
        assertEquals(ResumeMode.ASK, restored("pref_resume" to true, "pref_ask_resume" to true).resumeMode)
        assertEquals(ResumeMode.YES, restored("pref_resume" to true, "pref_ask_resume" to false).resumeMode)
        // A missing switch was on.
        assertEquals(ResumeMode.YES, restored("pref_ask_resume" to false).resumeMode)
        assertEquals(ResumeMode.NO, restored("pref_resume" to false).resumeMode)
    }

    @Test
    fun theGridGoesToBrowseAndFavorites() {
        val p = restored("pref_grid" to false, "pref_grid_columns" to "4")
        assertEquals(TabView(grid = false, columns = 4), p.view(Prefs.TAB_BROWSE))
        assertEquals(TabView(grid = false, columns = 4), p.view(Prefs.TAB_FAVORITES))
        assertEquals(TabView.defaultFor(Prefs.TAB_HISTORY), p.view(Prefs.TAB_HISTORY))
        // A missing grid switch was off.
        assertFalse(restored("pref_grid_columns" to "3").view(Prefs.TAB_BROWSE).grid)
    }

    @Test
    fun hiddenFileNamesBecomeAHiddenTitle() {
        val p = restored("pref_show_filenames" to false)
        assertEquals(TitleDisplay.HIDDEN, p.titleDisplay)
        for (tab in Prefs.TABS) assertEquals(DetailDisplay.HIDDEN, p.view(tab).detail)
        val shown = restored("pref_show_filenames" to true)
        assertEquals(TitleDisplay.DOUBLE, shown.titleDisplay)
        assertEquals(DetailDisplay.COMFORTABLE, shown.view(Prefs.TAB_HISTORY).detail)
    }

    @Test
    fun theCharsetMovesUnlessUtf8() {
        assertEquals("Shift_JIS", restored("pref_charset" to "Shift_JIS").charset)
        // Auto reads UTF-8 names as UTF-8 does.
        assertEquals("auto", restored("pref_charset" to "UTF-8").charset)
    }

    @Test
    fun twoPageSwitchBecomesOnOrOff() {
        assertEquals(TwoPage.ON, restored("pref_two_page" to true).twoPage)
        assertEquals(TwoPage.OFF, restored("pref_two_page" to false).twoPage)
    }

    @Test
    fun topToBottomModesBecomeTopToBottom() {
        val strip = restored("pref_page_mode" to "longstrip", "pref_reading_dir" to "ltr", "pref_auto_crop" to true)
        assertEquals("continuous", strip.pageMode)
        assertEquals("ttb", strip.readingDir)
        // The long strip never cropped.
        assertFalse(strip.autoCrop)
        val vertical = restored("pref_page_mode" to "vertical", "pref_reading_dir" to "rtl")
        assertEquals("paged", vertical.pageMode)
        assertEquals("ttb", vertical.readingDir)
        assertEquals("rtl", restored("pref_page_mode" to "paged", "pref_reading_dir" to "rtl").readingDir)
    }

    @Test
    fun untranslatedLanguagesFallBackToSystem() {
        assertEquals("en-US", restored("pref_locale" to "en").localeTag)
        assertEquals("system", restored("pref_locale" to "ru").localeTag)
        assertEquals("system", restored("pref_locale" to "system").localeTag)
    }

    @Test
    fun settingsUnderTodaysKeysRestoreAsTheyAre() {
        val p = restored("pref_fit" to "width", "pref_fullscreen" to false)
        assertEquals("width", p.fit)
        assertFalse(p.fullscreen)
    }
}
