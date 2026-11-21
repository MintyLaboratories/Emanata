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

/** A fresh settings screen shows spreads merged and the screen turning for them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSpreadDefaultsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun spreadsShowMergeAndWidePagesRotateTheImage() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val spreads = fragment.findPreference<ListPreference>("pref_spreads")!!
        assertEquals("merge", spreads.value)
        assertEquals(context.getString(R.string.spreads_merge), spreads.summary.toString())
        val wide = fragment.findPreference<ListPreference>("pref_wide_pages")!!
        assertEquals("image", wide.value)
        assertEquals(
            context.getString(R.string.wide_pages_image) + "\n" + context.getString(R.string.wide_pages_image_desc),
            wide.summary.toString()
        )
    }
}
