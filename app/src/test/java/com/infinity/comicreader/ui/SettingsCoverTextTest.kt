package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.CoverTextDetectors
import com.infinity.comicreader.ai.PlayServices
import com.infinity.comicreader.data.ListSettings
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.testing.coverText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings › Library: "Use Google ML Kit for cover text", off by default and offered only where Play services is there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCoverTextTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class Play(private val there: Boolean) : PlayServices {
        override fun available() = there

        override fun moduleInstalled(): Boolean? = true

        override fun requestModule(done: (Boolean) -> Unit): () -> Unit {
            done(true)
            return {}
        }
    }

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() = CoverTextDetectors.standIn(null)

    private fun withPlayServices(there: Boolean) {
        val prefs = Prefs(context)
        CoverTextDetectors.standIn(CoverTextDetectors({ prefs.coverMlKit }, Play(there), CoverTextDetectors.MlKit("mlkit") { coverText("mlkit") { null } }, { coverText("ppocr") { null } }))
    }

    private fun settings(): PreferenceFragmentCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
    }

    @Test
    fun offeredOffByDefaultInTheLibrarySectionWherePlayServicesIsThere() {
        withPlayServices(true)
        val fragment = settings()
        val pref = fragment.findPreference<SwitchPreferenceCompat>(Prefs.KEY_COVER_MLKIT)!!
        assertEquals(fragment.findPreference<Preference>("section_library"), pref.parent)
        assertFalse(pref.isChecked)
        assertFalse(Prefs(context).coverMlKit)
        assertEquals(CoverTextDetectors.Use.PP_OCR, CoverTextDetectors.shared(context).status().use)
        pref.performClick()
        assertTrue(Prefs(context).coverMlKit)
        assertTrue(ListSettings.from(Prefs(context)).coverMlKit)
        assertEquals(CoverTextDetectors.Use.ML_KIT, CoverTextDetectors.shared(context).status().use)
    }

    @Test
    fun notOfferedWithoutPlayServices() {
        withPlayServices(false)
        assertNull(settings().findPreference<Preference>(Prefs.KEY_COVER_MLKIT))
    }
}
