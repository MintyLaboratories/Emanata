package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsUpgradeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun stored() = PreferenceManager.getDefaultSharedPreferences(context).all

    @Test
    fun tapZonesSwitchBecomesALayout() {
        assertEquals(
            mapOf("pref_tap_zone_layout" to "left_right", "pref_tap_zones" to null),
            Prefs.upgrade(mapOf("pref_tap_zones" to true))
        )
        assertEquals(
            mapOf("pref_tap_zone_layout" to "disabled", "pref_tap_zones" to null),
            Prefs.upgrade(mapOf("pref_tap_zones" to false))
        )
    }

    @Test
    fun aLayoutAlreadySetWins() {
        assertEquals(
            mapOf("pref_tap_zones" to null),
            Prefs.upgrade(mapOf("pref_tap_zones" to false, "pref_tap_zone_layout" to "top_bottom"))
        )
    }

    @Test
    fun overlayBrightnessBecomesTheBrightnessAdjustment() {
        assertEquals(
            mapOf("pref_adjust_brightness" to -50, "pref_brightness" to null),
            Prefs.upgrade(mapOf("pref_brightness" to 50))
        )
        assertEquals(0, Prefs.upgrade(mapOf("pref_brightness" to 100))["pref_adjust_brightness"])
        assertEquals(-80, Prefs.upgrade(mapOf("pref_brightness" to 20))["pref_adjust_brightness"])
        assertEquals(
            mapOf("pref_brightness" to null),
            Prefs.upgrade(mapOf("pref_brightness" to 50, "pref_adjust_brightness" to 10))
        )
    }

    @Test
    fun upgradedBrightnessIsTheDefault() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("pref_brightness", 70).commit()
        val prefs = Prefs(context)
        prefs.upgrade()
        assertEquals(Adjustments(brightness = -30), prefs.defaultAdjustments)
        assertFalse("pref_brightness" in stored())
    }

    @Test
    fun currentSettingsNeedNothing() {
        assertTrue(Prefs.upgrade(emptyMap()).isEmpty())
        assertTrue(Prefs.upgrade(mapOf("pref_tap_zone_layout" to "right_left")).isEmpty())
    }

    @Test
    fun upgradeRewritesStoredSettings() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("pref_tap_zones", false).commit()
        val prefs = Prefs(context)
        prefs.upgrade()
        assertEquals("disabled", prefs.tapZones)
        assertFalse("pref_tap_zones" in stored())
    }

    @Test
    fun defaultsAreLeftRightMirrored() {
        val prefs = Prefs(context)
        assertEquals("left_right", prefs.tapZones)
        assertTrue(prefs.mirrorTapZones)
    }

    @Test
    fun writtenChangesKeepEveryTypeAndRemoveNulls() {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit().putString("gone", "x").commit()
        val e = sp.edit()
        Prefs.write(e, mapOf("s" to "v", "b" to false, "i" to 7, "gone" to null, "odd" to 1.5))
        e.commit()
        assertEquals(mapOf("s" to "v", "b" to false, "i" to 7), stored())
    }

    @Test
    fun anOldBackupRestoresIntoTheNewSetting() {
        val prefs = Prefs(context)
        prefs.tapZones = "bottom_top"
        prefs.importAll(mapOf("pref_tap_zones" to false))
        assertEquals("disabled", prefs.tapZones)
        assertFalse("pref_tap_zones" in stored())
    }

    @Test
    fun resumeSwitchesBecomeOneSetting() {
        val gone = mapOf("pref_resume" to null, "pref_ask_resume" to null)
        assertEquals(
            mapOf("pref_resume_mode" to "no") + gone,
            Prefs.upgrade(mapOf("pref_resume" to false, "pref_ask_resume" to true))
        )
        assertEquals(
            mapOf("pref_resume_mode" to "ask") + gone,
            Prefs.upgrade(mapOf("pref_resume" to true, "pref_ask_resume" to true))
        )
        assertEquals(
            mapOf("pref_resume_mode" to "yes") + gone,
            Prefs.upgrade(mapOf("pref_resume" to true, "pref_ask_resume" to false))
        )
    }

    @Test
    fun aMissingResumeSwitchWasOn() {
        assertEquals("yes", Prefs.upgrade(mapOf("pref_ask_resume" to false))["pref_resume_mode"])
        assertEquals("no", Prefs.upgrade(mapOf("pref_resume" to false))["pref_resume_mode"])
        assertEquals("ask", Prefs.upgrade(mapOf("pref_resume" to true))["pref_resume_mode"])
    }

    @Test
    fun aResumeModeAlreadySetWins() {
        assertEquals(
            mapOf("pref_resume" to null, "pref_ask_resume" to null),
            Prefs.upgrade(mapOf("pref_resume" to false, "pref_ask_resume" to false, "pref_resume_mode" to "ask"))
        )
    }

    @Test
    fun upgradedResumeModeIsStoredAndDefaultIsAsk() {
        val prefs = Prefs(context)
        assertEquals(ResumeMode.ASK, prefs.resumeMode)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("pref_resume", true).putBoolean("pref_ask_resume", false).commit()
        prefs.upgrade()
        assertEquals(ResumeMode.YES, prefs.resumeMode)
        assertFalse("pref_resume" in stored())
        assertFalse("pref_ask_resume" in stored())
    }

    @Test
    fun anOldBackupRestoresTheResumeMode() {
        val prefs = Prefs(context)
        prefs.importAll(mapOf("pref_resume" to false, "pref_ask_resume" to true))
        assertEquals(ResumeMode.NO, prefs.resumeMode)
        assertFalse("pref_resume" in stored())
    }
}
