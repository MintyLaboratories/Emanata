package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsStartupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ui().edit().clear().commit()
    }

    private fun ui() = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    @Test
    fun defaultsToLastSeenOnBrowse() {
        val prefs = Prefs(context)
        assertEquals(StartupLocation.LAST, prefs.startupLocation)
        assertEquals(Prefs.TAB_BROWSE, prefs.startupTab)
    }

    @Test
    fun anOlderHomeFolderIsACustomLocation() {
        ui().edit().putString("home_path", "/home").commit()
        val prefs = Prefs(context)
        assertEquals(StartupLocation.CUSTOM, prefs.startupLocation)
        assertEquals("/home", prefs.startupPath)
    }

    @Test
    fun writingMovesTheOlderHomeFolder() {
        ui().edit().putString("home_path", "/home").commit()
        val prefs = Prefs(context)
        prefs.startupLocation = StartupLocation.FIRST_FAVORITE
        assertFalse(ui().contains("home_path"))
        assertEquals(StartupLocation.FIRST_FAVORITE, prefs.startupLocation)
        assertEquals("/home", prefs.startupPath)
    }

    @Test
    fun startupFolderIsCustom() {
        val prefs = Prefs(context)
        prefs.setStartupFolder("/x")
        assertEquals(StartupLocation.CUSTOM, prefs.startupLocation)
        assertEquals("/x", prefs.startupPath)
    }

    @Test
    fun startupSettingsAreBackedUp() {
        val prefs = Prefs(context)
        prefs.startupTab = Prefs.TAB_FAVORITES
        prefs.setStartupFolder("/x")
        val exported = prefs.exportAll()
        assertEquals("favorites", exported["pref_startup_tab"])
        assertEquals("custom", exported["pref_startup_location"])
        assertEquals("/x", exported["pref_startup_path"])
    }
}
