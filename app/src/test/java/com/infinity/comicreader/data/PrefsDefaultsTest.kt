package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
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
    fun spreadsMergeAndTurnTheScreen() {
        val prefs = Prefs(context)
        assertEquals(SpreadDisplay.MERGE, prefs.spreads)
        assertTrue(prefs.rotateSpreads)
        // The settings screen writes the same defaults.
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        assertEquals(SpreadDisplay.MERGE, Prefs(context).spreads)
        assertTrue(Prefs(context).rotateSpreads)
    }
}
