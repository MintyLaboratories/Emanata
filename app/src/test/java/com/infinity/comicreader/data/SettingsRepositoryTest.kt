package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        repo = SettingsRepository(context)
    }

    @Test
    fun startsWithStoredValues() {
        assertEquals(ListSettings.from(prefs), repo.list.value)
        assertEquals(ReaderSettings.from(prefs), repo.reader.value)
        assertTrue(repo.list.value.views.getValue(Prefs.TAB_BROWSE).grid)
        assertFalse(repo.list.value.views.getValue(Prefs.TAB_HISTORY).grid)
        assertEquals("paged", repo.reader.value.pageMode)
    }

    @Test
    fun listChangesArePublished() {
        prefs.setGrid(Prefs.TAB_HISTORY, true)
        prefs.setGridSize(Prefs.TAB_HISTORY, 4, 3)
        prefs.thumbnails = false
        val list = repo.list.value
        assertEquals(TabView(grid = true, columns = 4, rows = 3), list.views[Prefs.TAB_HISTORY])
        assertEquals(TabView.defaultFor(Prefs.TAB_BROWSE), list.views[Prefs.TAB_BROWSE])
        assertFalse(list.thumbnails)
    }

    @Test
    fun readerChangesArePublished() {
        prefs.pageMode = "longstrip"
        prefs.fit = "width"
        prefs.twoPage = true
        prefs.defaultAdjustments = Adjustments(brightness = -40, contrast = 10, saturation = 300)
        val reader = repo.reader.value
        assertEquals("longstrip", reader.pageMode)
        assertEquals("width", reader.fit)
        assertTrue(reader.twoPage)
        assertEquals(Adjustments(-40, 10, 100), reader.adjustments)
    }

    @Test
    fun writesFromAnotherPrefsInstanceArePublished() {
        // Each screen makes its own Prefs; they all share one store.
        Prefs(context).readingDir = "rtl"
        assertEquals("rtl", repo.reader.value.readingDir)
    }

    @Test
    fun settingsScreenWritesArePublished() {
        // The preference screen writes the store directly, not through Prefs.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("pref_view_bookmarks_grid", true)
            .putInt("pref_view_bookmarks_columns", 3)
            .apply()
        assertTrue(repo.list.value.views.getValue(Prefs.TAB_BOOKMARKS).grid)
        assertEquals(3, repo.list.value.views.getValue(Prefs.TAB_BOOKMARKS).columns)
    }

    @Test
    fun resetPublishesDefaults() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        prefs.filter = "sepia"
        prefs.reset()
        assertTrue(repo.list.value.views.getValue(Prefs.TAB_BROWSE).grid)
        assertEquals("none", repo.reader.value.filter)
    }

    @Test
    fun importPublishesRestoredValues() {
        prefs.importAll(mapOf("pref_sort" to "date", "pref_night" to true))
        assertEquals(SortLevel(SortKey.ADDED, flipped = true), repo.list.value.sorts.getValue(Prefs.TAB_BROWSE).by)
        assertTrue(repo.reader.value.nightFilter)
    }

    @Test
    fun unrelatedWritesKeepTheSameSnapshot() {
        val before = repo.list.value
        prefs.autoSpeed = 9
        // A data-class snapshot that did not change compares equal, so collectors skip it.
        assertEquals(before, repo.list.value)
        assertEquals(9, repo.reader.value.autoSpeed)
    }

    @Test
    fun linearLightIsOffByDefaultAndReachesBothScreens() {
        assertFalse(repo.reader.value.linearLight)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("pref_linear_light", true).apply()
        assertTrue(repo.reader.value.linearLight)
        assertTrue(repo.list.value.linearLight)
        // Backed up and restored like every other setting.
        assertEquals(true, prefs.exportAll()["pref_linear_light"])
    }

    @Test
    fun getReturnsOneInstance() {
        assertSame(SettingsRepository.get(context), SettingsRepository.get(context))
    }
}
