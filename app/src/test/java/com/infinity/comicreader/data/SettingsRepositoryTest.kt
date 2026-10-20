package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
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
        assertFalse(repo.list.value.grid)
        assertEquals("paged", repo.reader.value.pageMode)
    }

    @Test
    fun listChangesArePublished() {
        prefs.listGrid = true
        prefs.gridColumns = 4
        prefs.gridRows = 3
        prefs.thumbnails = false
        val list = repo.list.value
        assertTrue(list.grid)
        assertEquals(4, list.gridColumns)
        assertEquals(3, list.gridRows)
        assertFalse(list.thumbnails)
    }

    @Test
    fun readerChangesArePublished() {
        prefs.pageMode = "longstrip"
        prefs.fit = "width"
        prefs.twoPage = true
        prefs.brightness = 60
        val reader = repo.reader.value
        assertEquals("longstrip", reader.pageMode)
        assertEquals("width", reader.fit)
        assertTrue(reader.twoPage)
        assertEquals(60, reader.brightness)
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
            .putBoolean("pref_grid", true)
            .putString("pref_grid_columns", "3")
            .apply()
        assertTrue(repo.list.value.grid)
        assertEquals(3, repo.list.value.gridColumns)
    }

    @Test
    fun resetPublishesDefaults() {
        prefs.listGrid = true
        prefs.filter = "sepia"
        prefs.reset()
        assertFalse(repo.list.value.grid)
        assertEquals("none", repo.reader.value.filter)
    }

    @Test
    fun importPublishesRestoredValues() {
        prefs.importAll(mapOf("pref_sort" to "date", "pref_night" to true))
        assertEquals("date", repo.list.value.sort)
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
    fun getReturnsOneInstance() {
        assertSame(SettingsRepository.get(context), SettingsRepository.get(context))
    }
}
