package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ModelCatalog
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
class PrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun stored() = PreferenceManager.getDefaultSharedPreferences(context).all

    @Test
    fun sharpeningReachesPagesAndZoomsButNeverThumbnails() {
        val prefs = Prefs(context)
        assertEquals(0, prefs.sharpen)
        assertEquals(0, prefs.pageScaling.sharpen)
        prefs.sharpen = 140
        assertEquals(100, prefs.sharpen)
        prefs.sharpen = 35
        assertEquals(35, prefs.pageScaling.sharpen)
        assertEquals(35, prefs.zoomScaling.sharpen)
        assertEquals(0, prefs.thumbScaling.sharpen)
        assertEquals(35, stored()["pref_sharpen"])
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
    fun pinchToPeekDefaultsToPeekAndOpen() {
        assertEquals(PeekMode.PEEK_OPEN, Prefs(context).pinchPeek)
        Prefs(context).pinchPeek = PeekMode.OFF
        assertEquals(PeekMode.OFF, Prefs(context).pinchPeek)
    }

    @Test
    fun defaultsAreLeftRightMirrored() {
        val prefs = Prefs(context)
        assertEquals("left_right", prefs.tapZones)
        assertTrue(prefs.mirrorTapZones)
    }

    @Test
    fun unsetSettingsReadTheirDefaults() {
        val prefs = Prefs(context)
        assertEquals(ResumeMode.ASK, prefs.resumeMode)
        assertEquals(Adjustments(), prefs.defaultAdjustments)
        assertEquals("auto", prefs.charset)
        assertEquals(TwoPage.OFF, prefs.twoPage)
        assertEquals(TitleDisplay.DOUBLE, prefs.titleDisplay)
        assertEquals(DetailDisplay.COMFORTABLE, prefs.view(Prefs.TAB_HISTORY).detail)
        assertEquals(TabView.defaultFor(Prefs.TAB_BROWSE), prefs.view(Prefs.TAB_BROWSE))
        assertEquals("system", prefs.localeTag)
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, prefs.aiModel(AiStep.UPSCALE))
        assertTrue(stored().isEmpty())
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
    fun aRestoreTakesOnlyKnownKeys() {
        val prefs = Prefs(context)
        prefs.importAll(mapOf("pref_theme" to "dark", "pref_tap_zones" to false, "pref_unknown" to 3))
        assertEquals(mapOf("pref_theme" to "dark"), stored())
    }

    @Test
    fun languageSettingOffersEveryKnownLocale() {
        assertEquals(Prefs.LOCALES, context.resources.getStringArray(R.array.locale_values).toList())
    }

    @Test
    fun languageEntryShowsItsCodeSmallerOnTheSameLine() {
        val entry = context.resources.getTextArray(R.array.locale_entries)[Prefs.LOCALES.indexOf("en-US")]
        assertEquals("English (Simplified)   en-US", entry.toString())
        val spanned = entry as android.text.Spanned
        val small = spanned.getSpans(0, entry.length, android.text.style.RelativeSizeSpan::class.java).single()
        assertEquals("en-US", entry.subSequence(spanned.getSpanStart(small), spanned.getSpanEnd(small)).toString())
    }

    @Test
    fun autoScrollSpeedDefaultsToThreeScreensAMinuteAndSnapsToASpeedOffered() {
        val prefs = Prefs(context)
        assertEquals(3f, prefs.autoSpeed, 0f)
        prefs.autoSpeed = 0.3f
        assertEquals(0.25f, prefs.autoSpeed, 0f)
        assertEquals("0.25", stored()["pref_auto_scroll_speed"])
    }

    @Test
    fun twoPageModes() {
        assertEquals(TwoPage.OFF, TwoPage.of(null))
        assertEquals(TwoPage.OFF, TwoPage.of("sideways"))
        assertTrue(TwoPage.ON.spread(landscape = false))
        assertTrue(TwoPage.LANDSCAPE.spread(landscape = true))
        assertFalse(TwoPage.LANDSCAPE.spread(landscape = false))
        assertFalse(TwoPage.OFF.spread(landscape = true))
        assertEquals(TwoPage.entries.map { it.key }, context.resources.getStringArray(R.array.two_page_values).toList())
    }

    @Test
    fun layoutsAndDirectionsMatchTheirSettings() {
        assertEquals(ReaderLayout.MODES, context.resources.getStringArray(R.array.page_mode_values).toList())
        assertEquals(ReaderLayout.DIRS, context.resources.getStringArray(R.array.reading_dir_values).toList())
    }
}
