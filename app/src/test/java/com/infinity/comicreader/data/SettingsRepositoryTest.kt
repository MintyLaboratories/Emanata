package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
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
import org.robolectric.Shadows.shadowOf
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
        prefs.pageMode = "continuous"
        prefs.fit = "width"
        prefs.twoPage = TwoPage.LANDSCAPE
        prefs.defaultAdjustments = Adjustments(brightness = -40, contrast = 10, saturation = 300)
        val reader = repo.reader.value
        assertEquals("continuous", reader.pageMode)
        assertEquals("width", reader.fit)
        assertEquals(TwoPage.LANDSCAPE, reader.twoPage)
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
        prefs.autoSpeed = 8f
        // A data-class snapshot that did not change compares equal, so collectors skip it.
        assertEquals(before, repo.list.value)
        assertEquals(8f, repo.reader.value.autoSpeed, 0f)
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

    private fun setSaver(on: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(on)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun batterySaverHoldsBackWhatTheBatterySettingsLet() {
        assertEquals(Thrift.NONE, repo.reader.value.thrift)
        setSaver(true)
        assertEquals(Thrift(firstLayerCovers = true, lessReadAhead = true, pausedScans = true), repo.reader.value.thrift)
        assertEquals(repo.reader.value.thrift, repo.list.value.thrift)
        assertEquals(1, repo.reader.value.thrift.readAhead(repo.reader.value.preload))
        prefs.saverCovers = false
        prefs.saverScans = false
        assertEquals(Thrift(lessReadAhead = true), repo.list.value.thrift)
        setSaver(false)
        assertEquals(Thrift.NONE, repo.list.value.thrift)
        assertEquals(2, repo.reader.value.thrift.readAhead(repo.reader.value.preload))
    }

    @Test
    fun lessReadAheadNeverAddsPages() {
        val less = Thrift(lessReadAhead = true)
        assertEquals(0, less.readAhead(0))
        assertEquals(1, less.readAhead(4))
        assertEquals(4, Thrift.NONE.readAhead(4))
        // Two pages a screen: the whole next spread.
        assertEquals(2, less.readAhead(4, perScreen = 2))
        assertEquals(1, less.readAhead(1, perScreen = 2))
        assertEquals(4, Thrift.NONE.readAhead(4, perScreen = 2))
    }
}
