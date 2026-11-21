package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings show the crop anchor covers use, a stored one no longer offered as the default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCropAnchorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun anchorShownFor(stored: String): ListPreference {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("pref_crop_anchor", stored).commit()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        return fragment.findPreference("pref_crop_anchor")!!
    }

    @Test
    fun theChoicesAreTopAndCenter() {
        val pref = anchorShownFor("top")
        assertEquals(listOf("top", "center"), pref.entryValues.map { it.toString() })
        assertEquals(listOf(R.string.crop_anchor_top, R.string.crop_anchor_center).map { context.getString(it) }, pref.entries.map { it.toString() })
    }

    @Test
    fun unknownStoredAnchorShowsTop() {
        val pref = anchorShownFor("smart")
        assertEquals("top", pref.value)
        assertEquals(context.getString(R.string.crop_anchor_top), pref.summary.toString())
    }

    @Test
    fun offeredStoredAnchorStays() {
        val pref = anchorShownFor("center")
        assertEquals("center", pref.value)
        assertEquals(context.getString(R.string.crop_anchor_center), pref.summary.toString())
    }
}
