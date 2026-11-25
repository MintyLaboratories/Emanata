package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.ai.AiThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The AI setting's two handles: stored as set, read back within their ranges, part of backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsAiThresholdsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun stored(key: String, value: Int) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(key, value).commit()

    @Test
    fun defaultsUpscaleAboveFullSizeAndCleanUpAboveHalf() {
        assertEquals(AiThresholds(100, 50), Prefs(context).aiThresholds)
    }

    @Test
    fun handlesPersistIndependentlyAndMayCross() {
        Prefs(context).aiThresholds = AiThresholds(upscalePct = 60, cleanupPct = 150)
        assertEquals(AiThresholds(60, 150), Prefs(context).aiThresholds)
        Prefs(context).aiThresholds = AiThresholds(upscalePct = 50, cleanupPct = AiThresholds.ALWAYS)
        assertEquals(AiThresholds(50, AiThresholds.ALWAYS), Prefs(context).aiThresholds)
    }

    @Test
    fun storedValuesOutOfRangeReadWithinIt() {
        stored(Prefs.KEY_AI_UPSCALE_ABOVE, 30)
        stored(Prefs.KEY_AI_CLEANUP_ABOVE, 10)
        assertEquals(AiThresholds(AiThresholds.UPSCALE_FLOOR, AiThresholds.ALWAYS), Prefs(context).aiThresholds)
        stored(Prefs.KEY_AI_UPSCALE_ABOVE, 900)
        stored(Prefs.KEY_AI_CLEANUP_ABOVE, 900)
        assertEquals(AiThresholds(AiThresholds.MAX_PCT, AiThresholds.MAX_PCT), Prefs(context).aiThresholds)
    }

    @Test
    fun backupCarriesBothHandlesAndResetClearsThem() {
        val prefs = Prefs(context)
        prefs.aiThresholds = AiThresholds(70, AiThresholds.ALWAYS)
        val backup = prefs.exportAll()
        assertEquals(70, backup[Prefs.KEY_AI_UPSCALE_ABOVE])
        assertEquals(AiThresholds.ALWAYS, backup[Prefs.KEY_AI_CLEANUP_ABOVE])

        prefs.reset()
        assertEquals(AiThresholds(), prefs.aiThresholds)
        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).contains(Prefs.KEY_AI_UPSCALE_ABOVE))

        prefs.importAll(backup)
        assertEquals(AiThresholds(70, AiThresholds.ALWAYS), prefs.aiThresholds)
    }
}
