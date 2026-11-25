package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    fun startFoldersFollowAFolderMovedOrRenamed() {
        val prefs = Prefs(context)
        prefs.setStartupFolder("/net/s1/Series/Arc")
        prefs.setLocationStart("/net/s1", "/net/s1/Series/Arc")
        prefs.setLocationStart("/storage/emulated/0", "/storage/emulated/0/Comics")
        prefs.lastPath = "/net/s1/Series"
        prefs.followPath("/net/s1/Series", "/net/s1/Sets/Series Two")
        assertEquals("/net/s1/Sets/Series Two/Arc", prefs.startupPath)
        assertEquals("/net/s1/Sets/Series Two/Arc", prefs.locationStart("/net/s1"))
        assertEquals("/storage/emulated/0/Comics", prefs.locationStart("/storage/emulated/0"))
        assertEquals("/net/s1/Sets/Series Two", prefs.lastPath)
        // A name that only starts the same is another folder.
        prefs.followPath("/storage/emulated/0/Com", "/storage/emulated/0/X")
        assertEquals("/storage/emulated/0/Comics", prefs.locationStart("/storage/emulated/0"))
    }

    @Test
    fun eachLocationKeepsItsOwnStartFolder() {
        val prefs = Prefs(context)
        assertEquals("/storage/emulated/0", prefs.locationStart("/storage/emulated/0"))
        prefs.setLocationStart("/storage/emulated/0", "/storage/emulated/0/Comics")
        prefs.setLocationStart("/net/s1", "/net/s1/Series/Arc")
        assertEquals("/storage/emulated/0/Comics", prefs.locationStart("/storage/emulated/0"))
        assertEquals("/net/s1/Series/Arc", prefs.locationStart("/net/s1"))
        assertEquals("/net/s2", prefs.locationStart("/net/s2"))
        // The top itself, or a folder elsewhere, clears it.
        prefs.setLocationStart("/net/s1", "/net/s1")
        assertEquals("/net/s1", prefs.locationStart("/net/s1"))
        prefs.setLocationStart("/storage/emulated/0", "/net/s1/Series")
        assertEquals("/storage/emulated/0", prefs.locationStart("/storage/emulated/0"))
        assertEquals("/net/s10", Prefs(context).also { it.setLocationStart("/net/s1", "/net/s10/x") }.locationStart("/net/s10"))
        assertEquals("/net/s1", prefs.locationStart("/net/s1"))
    }

    @Test
    fun theStartupResumeAsksByDefault() {
        assertEquals(ResumeMode.ASK, Prefs(context).startupResume)
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
