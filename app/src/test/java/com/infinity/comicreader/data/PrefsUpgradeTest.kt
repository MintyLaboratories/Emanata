package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsUpgradeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun stored() = PreferenceManager.getDefaultSharedPreferences(context).all

    @Test
    fun singleSortMovesToBrowseAndFavorites() {
        assertEquals(
            mapOf(
                "pref_sort_browse_by" to "size",
                "pref_sort_favorites_by" to "size",
                "pref_sort" to null,
                "pref_sort_dir" to null
            ),
            Prefs.upgrade(mapOf("pref_sort" to "size", "pref_sort_dir" to "desc"))
        )
        assertEquals("added:flipped", Prefs.upgrade(mapOf("pref_sort" to "date"))["pref_sort_browse_by"])
        assertEquals("type", Prefs.upgrade(mapOf("pref_sort" to "type", "pref_sort_dir" to "asc"))["pref_sort_browse_by"])
        assertEquals("name:flipped", Prefs.upgrade(mapOf("pref_sort_dir" to "desc"))["pref_sort_favorites_by"])
    }

    @Test
    fun defaultSingleSortOnlyDropsTheOldKeys() {
        assertEquals(
            mapOf("pref_sort" to null, "pref_sort_dir" to null),
            Prefs.upgrade(mapOf("pref_sort" to "name", "pref_sort_dir" to "asc"))
        )
    }

    @Test
    fun aTabSortAlreadySetWins() {
        val changes = Prefs.upgrade(mapOf("pref_sort" to "size", "pref_sort_browse_by" to "year"))
        assertFalse("pref_sort_browse_by" in changes)
        assertEquals("size:flipped", changes["pref_sort_favorites_by"])
    }

    @Test
    fun sortsAndFiltersPerTab() {
        val prefs = Prefs(context)
        assertEquals(TabSort.defaultFor(Prefs.TAB_HISTORY), prefs.sort(Prefs.TAB_HISTORY))
        val sort = TabSort(SortLevel(SortKey.YEAR, flipped = true), SortLevel(SortKey.LENGTH), foldersFirst = false)
        prefs.setSort(Prefs.TAB_BROWSE, sort)
        assertEquals(sort, prefs.sort(Prefs.TAB_BROWSE))
        assertEquals(TabSort.defaultFor(Prefs.TAB_FAVORITES), prefs.sort(Prefs.TAB_FAVORITES))
        prefs.setSort(Prefs.TAB_BROWSE, sort.copy(then = null))
        assertEquals(null, prefs.sort(Prefs.TAB_BROWSE).then)

        val filter = LibraryFilter(statuses = setOf(ReadStatus.UNREAD), minYear = 2000)
        prefs.setFilter(Prefs.TAB_FAVORITES, filter)
        assertEquals(filter, prefs.filter(Prefs.TAB_FAVORITES))
        assertEquals(LibraryFilter.NONE, prefs.filter(Prefs.TAB_BROWSE))
        prefs.setFilter(Prefs.TAB_FAVORITES, LibraryFilter.NONE)
        assertFalse(stored().keys.any { it.startsWith("pref_library_filter") })
    }

    @Test
    fun tapZonesSwitchBecomesALayout() {
        assertEquals(
            mapOf("pref_tap_zone_layout" to "left_right", "pref_tap_zones" to null),
            Prefs.upgrade(mapOf("pref_tap_zones" to true))
        )
        assertEquals(
            mapOf("pref_tap_zone_layout" to "disabled", "pref_tap_zones" to null),
            Prefs.upgrade(mapOf("pref_tap_zones" to false))
        )
    }

    @Test
    fun aLayoutAlreadySetWins() {
        assertEquals(
            mapOf("pref_tap_zones" to null),
            Prefs.upgrade(mapOf("pref_tap_zones" to false, "pref_tap_zone_layout" to "top_bottom"))
        )
    }

    @Test
    fun overlayBrightnessBecomesTheBrightnessAdjustment() {
        assertEquals(
            mapOf("pref_adjust_brightness" to -50, "pref_brightness" to null),
            Prefs.upgrade(mapOf("pref_brightness" to 50))
        )
        assertEquals(0, Prefs.upgrade(mapOf("pref_brightness" to 100))["pref_adjust_brightness"])
        assertEquals(-80, Prefs.upgrade(mapOf("pref_brightness" to 20))["pref_adjust_brightness"])
        assertEquals(
            mapOf("pref_brightness" to null),
            Prefs.upgrade(mapOf("pref_brightness" to 50, "pref_adjust_brightness" to 10))
        )
    }

    @Test
    fun upgradedBrightnessIsTheDefault() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("pref_brightness", 70).commit()
        val prefs = Prefs(context)
        prefs.upgrade()
        assertEquals(Adjustments(brightness = -30), prefs.defaultAdjustments)
        assertFalse("pref_brightness" in stored())
    }

    @Test
    fun currentSettingsNeedNothing() {
        assertTrue(Prefs.upgrade(emptyMap()).isEmpty())
        assertTrue(Prefs.upgrade(mapOf("pref_tap_zone_layout" to "right_left")).isEmpty())
    }

    @Test
    fun upgradeRewritesStoredSettings() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("pref_tap_zones", false).commit()
        val prefs = Prefs(context)
        prefs.upgrade()
        assertEquals("disabled", prefs.tapZones)
        assertFalse("pref_tap_zones" in stored())
    }

    @Test
    fun defaultsAreLeftRightMirrored() {
        val prefs = Prefs(context)
        assertEquals("left_right", prefs.tapZones)
        assertTrue(prefs.mirrorTapZones)
    }

    @Test
    fun writtenChangesKeepEveryTypeAndRemoveNulls() {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit().putString("gone", "x").commit()
        val e = sp.edit()
        Prefs.write(e, mapOf("s" to "v", "b" to false, "i" to 7, "gone" to null, "odd" to 1.5))
        e.commit()
        assertEquals(mapOf("s" to "v", "b" to false, "i" to 7), stored())
    }

    @Test
    fun anOldBackupRestoresIntoTheNewSetting() {
        val prefs = Prefs(context)
        prefs.tapZones = "bottom_top"
        prefs.importAll(mapOf("pref_tap_zones" to false))
        assertEquals("disabled", prefs.tapZones)
        assertFalse("pref_tap_zones" in stored())
    }

    @Test
    fun resumeSwitchesBecomeOneSetting() {
        val gone = mapOf("pref_resume" to null, "pref_ask_resume" to null)
        assertEquals(
            mapOf("pref_resume_mode" to "no") + gone,
            Prefs.upgrade(mapOf("pref_resume" to false, "pref_ask_resume" to true))
        )
        assertEquals(
            mapOf("pref_resume_mode" to "ask") + gone,
            Prefs.upgrade(mapOf("pref_resume" to true, "pref_ask_resume" to true))
        )
        assertEquals(
            mapOf("pref_resume_mode" to "yes") + gone,
            Prefs.upgrade(mapOf("pref_resume" to true, "pref_ask_resume" to false))
        )
    }

    @Test
    fun aMissingResumeSwitchWasOn() {
        assertEquals("yes", Prefs.upgrade(mapOf("pref_ask_resume" to false))["pref_resume_mode"])
        assertEquals("no", Prefs.upgrade(mapOf("pref_resume" to false))["pref_resume_mode"])
        assertEquals("ask", Prefs.upgrade(mapOf("pref_resume" to true))["pref_resume_mode"])
    }

    @Test
    fun aResumeModeAlreadySetWins() {
        assertEquals(
            mapOf("pref_resume" to null, "pref_ask_resume" to null),
            Prefs.upgrade(mapOf("pref_resume" to false, "pref_ask_resume" to false, "pref_resume_mode" to "ask"))
        )
    }

    @Test
    fun upgradedResumeModeIsStoredAndDefaultIsAsk() {
        val prefs = Prefs(context)
        assertEquals(ResumeMode.ASK, prefs.resumeMode)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("pref_resume", true).putBoolean("pref_ask_resume", false).commit()
        prefs.upgrade()
        assertEquals(ResumeMode.YES, prefs.resumeMode)
        assertFalse("pref_resume" in stored())
        assertFalse("pref_ask_resume" in stored())
    }

    @Test
    fun anOldBackupRestoresTheResumeMode() {
        val prefs = Prefs(context)
        prefs.importAll(mapOf("pref_resume" to false, "pref_ask_resume" to true))
        assertEquals(ResumeMode.NO, prefs.resumeMode)
        assertFalse("pref_resume" in stored())
    }

    @Test
    fun theGridSettingGoesToBrowseAndFavorites() {
        val gone = mapOf("pref_grid" to null, "pref_grid_columns" to null, "pref_grid_rows" to null)
        assertEquals(
            mapOf(
                "pref_view_browse_grid" to false, "pref_view_browse_columns" to 4, "pref_view_browse_rows" to 2,
                "pref_view_favorites_grid" to false, "pref_view_favorites_columns" to 4, "pref_view_favorites_rows" to 2
            ) + gone,
            Prefs.upgrade(mapOf("pref_grid" to false, "pref_grid_columns" to "4", "pref_grid_rows" to "2"))
        )
    }

    @Test
    fun aMissingGridSwitchWasOff() {
        val out = Prefs.upgrade(mapOf("pref_grid_columns" to "3"))
        assertEquals(false, out["pref_view_browse_grid"])
        assertEquals(false, out["pref_view_favorites_grid"])
        assertEquals(3, out["pref_view_browse_columns"])
        assertFalse("pref_view_browse_rows" in out)
    }

    @Test
    fun aTabViewAlreadySetWinsAndUnsetPartsStay() {
        val out = Prefs.upgrade(mapOf("pref_grid" to true, "pref_view_favorites_grid" to false))
        assertEquals(true, out["pref_view_browse_grid"])
        assertFalse("pref_view_favorites_grid" in out)
        assertFalse("pref_view_browse_columns" in out)
        assertFalse("pref_view_history_grid" in out)
    }

    @Test
    fun upgradedGridKeepsHistoryAndBookmarksAtTheirDefaults() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("pref_grid", false).putString("pref_grid_columns", "5").commit()
        val prefs = Prefs(context)
        prefs.upgrade()
        assertEquals(TabView(grid = false, columns = 5), prefs.view(Prefs.TAB_BROWSE))
        assertEquals(TabView(grid = false, columns = 5), prefs.view(Prefs.TAB_FAVORITES))
        assertEquals(TabView.defaultFor(Prefs.TAB_HISTORY), prefs.view(Prefs.TAB_HISTORY))
        assertFalse("pref_grid" in stored())
    }

    @Test
    fun tabViewsAreBackedUp() {
        val prefs = Prefs(context)
        prefs.setGridSize(Prefs.TAB_BOOKMARKS, 3, 1)
        val saved = prefs.exportAll()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs.importAll(saved)
        assertEquals(3, prefs.view(Prefs.TAB_BOOKMARKS).columns)
        assertEquals(1, prefs.view(Prefs.TAB_BOOKMARKS).rows)
    }

    @Test
    fun anOldBackupRestoresTheGridIntoBrowse() {
        val prefs = Prefs(context)
        prefs.importAll(mapOf("pref_grid" to false, "pref_grid_rows" to "3"))
        assertFalse(prefs.view(Prefs.TAB_BROWSE).grid)
        assertEquals(3, prefs.view(Prefs.TAB_FAVORITES).rows)
        assertFalse("pref_grid" in stored())
    }

    @Test
    fun hiddenFileNamesBecomeAHiddenTitle() {
        val hidden = Prefs.TABS.associate { "pref_view_${it}_detail" to "hidden" }
        assertEquals(
            mapOf("pref_title_display" to "hidden") + hidden + mapOf("pref_show_filenames" to null),
            Prefs.upgrade(mapOf("pref_show_filenames" to false))
        )
        assertEquals(DetailDisplay.COMFORTABLE, Prefs(context).view(Prefs.TAB_HISTORY).detail)
        assertEquals(mapOf("pref_show_filenames" to null), Prefs.upgrade(mapOf("pref_show_filenames" to true)))
        assertEquals(TitleDisplay.DOUBLE, Prefs(context).titleDisplay)
    }
}
