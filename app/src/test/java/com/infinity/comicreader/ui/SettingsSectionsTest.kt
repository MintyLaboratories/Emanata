package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Keep it simple hides advanced settings; sections other than General fold, and stay folded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSectionsTest {

    private lateinit var context: Context

    private val advanced = listOf(
        "pref_name_charset", "pref_scale_pages", "pref_scale_zoom", "pref_scale_thumbs", "pref_ring_up", "pref_ring_down",
        "pref_network_timeout", "pref_tall_threshold"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open(): Pair<ActivityController<SettingsActivity>, PreferenceFragmentCompat> {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        return controller to fragment
    }

    private fun PreferenceFragmentCompat.pref(key: String) = findPreference<Preference>(key)!!

    @Test
    fun keepItSimpleComesFirstAndHidesAdvancedSettings() {
        val (_, fragment) = open()
        assertEquals("pref_keep_simple", fragment.preferenceScreen.getPreference(0).key)
        assertTrue((fragment.pref("pref_keep_simple") as TwoStatePreference).isChecked)
        for (key in advanced) assertFalse(key, fragment.pref(key).isVisible)
        assertTrue(fragment.pref("pref_theme").isVisible)
        assertTrue(fragment.pref("pref_linear_light").isVisible)
    }

    @Test
    fun theTallThresholdShowsWhileItIsDragged() {
        val (_, fragment) = open()
        val pref = fragment.pref("pref_tall_threshold") as androidx.preference.SeekBarPreference
        assertTrue(pref.updatesContinuously)
        pref.value = 150
        assertTrue(pref.summary.toString().startsWith("1.50"))
    }

    @Test
    fun turnedOffAdvancedSettingsShowTagged() {
        val (_, fragment) = open()
        fragment.pref("pref_keep_simple").performClick()
        assertFalse(Prefs(context).keepSimple)
        val tertiary = MaterialColors.getColor(fragment.requireContext(), com.google.android.material.R.attr.colorTertiary, 0)
        for (key in advanced) {
            val pref = fragment.pref(key)
            assertTrue(key, pref.isVisible)
            val title = pref.title as Spanned
            assertTrue(title.toString().endsWith(context.getString(R.string.advanced_tag)))
            assertEquals(1, title.getSpans(0, title.length, TagSpan::class.java).size)
            val color = title.getSpans(0, title.length, ForegroundColorSpan::class.java).single().foregroundColor
            // The tertiary color, muted.
            assertEquals(tertiary and 0xFFFFFF, color and 0xFFFFFF)
            assertTrue(color ushr 24 < 0xFF)
        }
        assertEquals(context.getString(R.string.pref_theme), fragment.pref("pref_theme").title.toString())
        fragment.pref("pref_keep_simple").performClick()
        for (key in advanced) assertFalse(key, fragment.pref(key).isVisible)
    }

    @Test
    fun sectionsFoldAndStayFolded() {
        val (controller, fragment) = open()
        assertFalse(fragment.pref("section_general") is CollapsibleCategory)
        val reader = fragment.pref("section_reader") as CollapsibleCategory
        assertFalse(reader.collapsed)
        reader.performClick()
        assertTrue(reader.collapsed)
        assertFalse(fragment.pref("pref_fullscreen").isVisible)
        assertTrue(fragment.pref("pref_theme").isVisible)
        assertEquals(setOf("section_reader"), Prefs(context).collapsedSettings)
        controller.pause().stop().destroy()

        val (_, again) = open()
        val folded = again.pref("section_reader") as CollapsibleCategory
        assertTrue(folded.collapsed)
        assertFalse(again.pref("pref_fullscreen").isVisible)
        // Unfolded, advanced settings stay hidden while Keep it simple is on.
        folded.performClick()
        assertTrue(again.pref("pref_fullscreen").isVisible)
        assertFalse(again.pref("pref_scale_pages").isVisible)
        assertEquals(emptySet<String>(), Prefs(context).collapsedSettings)
    }
}
