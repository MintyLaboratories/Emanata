package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.net.Remotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsDefaultsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun autoNextChapterIsOn() {
        assertTrue(Prefs(context).autoNext)
    }

    @Test
    fun momentumScrollingIsOn() {
        assertTrue(Prefs(context).fling)
    }

    @Test
    fun spreadsMergeAndWidePagesRotateTheImage() {
        val prefs = Prefs(context)
        assertEquals(SpreadDisplay.MERGE, prefs.spreads)
        assertEquals(WidePages.IMAGE, prefs.widePages)
        assertTrue(prefs.joinSpreads)
        // The settings screen writes the same defaults.
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        assertEquals(SpreadDisplay.MERGE, Prefs(context).spreads)
        assertEquals(WidePages.IMAGE, Prefs(context).widePages)
        assertTrue(Prefs(context).joinSpreads)
    }

    @Test
    fun networkRequestsWaitThirtySecondsWithinBounds() {
        val prefs = Prefs(context)
        assertEquals(30_000L, prefs.networkTimeoutMs)
        assertEquals(Remotes.DEFAULT_ANSWER_MS, prefs.networkTimeoutMs)
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        assertEquals(30_000L, Prefs(context).networkTimeoutMs)
        prefs.networkTimeoutMs = 60_000
        assertEquals(60_000L, prefs.networkTimeoutMs)
        assertEquals(5_000L, Prefs.timeoutMsOf("1"))
        assertEquals(600_000L, Prefs.timeoutMsOf("99999"))
        assertEquals(30_000L, Prefs.timeoutMsOf("soon"))
    }
}
