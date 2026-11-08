package com.infinity.comicreader.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CalibrationPromptsTest {
    private val now = Calibration.fingerprint(Calibration.VERSION, "brand/device:16/BUILD2")
    private val older = Calibration.fingerprint(Calibration.VERSION, "brand/device:16/BUILD1")

    private fun kept(fingerprint: String, saver: Boolean = false, totalMs: Long? = null) =
        JSONObject().put("fingerprint", fingerprint).put("powerSave", saver).apply { totalMs?.let { put("totalMs", it) } }

    private fun offer(
        kept: JSONObject?,
        loaded: Boolean = true,
        canRun: Boolean = true,
        running: Boolean = false,
        declined: Boolean = false,
        shown: Boolean = false,
        skippedFor: String? = null,
        saverOn: Boolean = false,
        fingerprint: String? = now
    ) = CalibrationPrompts.offer(loaded, canRun, running, declined, shown, kept, fingerprint, skippedFor, saverOn)

    @Test
    fun theFirstCalibrationOpensTheScreenOnceALaunch() {
        assertEquals(CalibrationOffer.SCREEN, offer(null))
        assertEquals(CalibrationOffer.NONE, offer(null, shown = true))
        // Under battery saver too: the screen asks.
        assertEquals(CalibrationOffer.SCREEN, offer(null, saverOn = true))
        // Never skipped: there is nothing old to keep.
        assertEquals(CalibrationOffer.SCREEN, offer(null, skippedFor = now))
    }

    @Test
    fun laterOnesGetTheBannerUntilSkippedForThisBuild() {
        assertEquals(CalibrationOffer.BANNER, offer(kept(older)))
        assertEquals(CalibrationOffer.NONE, offer(kept(older), skippedFor = now))
        // A skip for an earlier build no longer counts.
        assertEquals(CalibrationOffer.BANNER, offer(kept(older), skippedFor = older))
        // A version bump is a new fingerprint as well.
        assertEquals(CalibrationOffer.BANNER, offer(kept(Calibration.fingerprint(Calibration.VERSION - 1, "brand/device:16/BUILD2"))))
        assertEquals(CalibrationOffer.NONE, offer(kept(now)))
    }

    @Test
    fun aRunMadeUnderSaverIsOfferedAgainOnceSaverIsOff() {
        assertEquals(CalibrationOffer.NONE, offer(kept(now, saver = true), saverOn = true))
        assertEquals(CalibrationOffer.BANNER, offer(kept(now, saver = true), saverOn = false))
        // One for an earlier build is due whatever saver does: the screen asks.
        assertEquals(CalibrationOffer.BANNER, offer(kept(older, saver = true), saverOn = true))
    }

    @Test
    fun nothingWhileUnknownRunningDeclinedOrUnable() {
        assertEquals(CalibrationOffer.NONE, offer(null, loaded = false))
        assertEquals(CalibrationOffer.NONE, offer(null, fingerprint = null))
        assertEquals(CalibrationOffer.NONE, offer(null, canRun = false))
        assertEquals(CalibrationOffer.NONE, offer(kept(older), running = true))
        assertEquals(CalibrationOffer.NONE, offer(null, declined = true))
        assertEquals(CalibrationOffer.NONE, offer(kept(older), declined = true))
    }

    @Test
    fun heatAndLowBatteryBlockSaverAsks() {
        assertEquals(CalibrationGate.GO, CalibrationPrompts.gate(saverOn = false, thermalStatus = 0, batteryPercent = 80, saverAccepted = false))
        assertEquals(CalibrationGate.GO, CalibrationPrompts.gate(saverOn = false, thermalStatus = Calibration.LIGHT, batteryPercent = 20, saverAccepted = false))
        assertEquals(CalibrationGate.HOT, CalibrationPrompts.gate(saverOn = false, thermalStatus = Calibration.HOT, batteryPercent = 80, saverAccepted = false))
        assertEquals(CalibrationGate.LOW_BATTERY, CalibrationPrompts.gate(saverOn = false, thermalStatus = 0, batteryPercent = 19, saverAccepted = false))
        // Unknown battery level: no block.
        assertEquals(CalibrationGate.GO, CalibrationPrompts.gate(saverOn = false, thermalStatus = 0, batteryPercent = null, saverAccepted = false))
        assertEquals(CalibrationGate.SAVER, CalibrationPrompts.gate(saverOn = true, thermalStatus = 0, batteryPercent = 80, saverAccepted = false))
        assertEquals(CalibrationGate.GO, CalibrationPrompts.gate(saverOn = true, thermalStatus = 0, batteryPercent = 80, saverAccepted = true))
        // Blocks come before the question.
        assertEquals(CalibrationGate.HOT, CalibrationPrompts.gate(saverOn = true, thermalStatus = 3, batteryPercent = 10, saverAccepted = false))
        assertEquals(CalibrationGate.LOW_BATTERY, CalibrationPrompts.gate(saverOn = true, thermalStatus = 0, batteryPercent = 10, saverAccepted = true))
    }

    @Test
    fun theEstimateIsTheLastRunRoundedUpToFiveSeconds() {
        assertEquals(CalibrationPrompts.DEFAULT_SECONDS, CalibrationPrompts.estimateSeconds(null))
        assertEquals(CalibrationPrompts.DEFAULT_SECONDS, CalibrationPrompts.estimateSeconds(kept(now)))
        assertEquals(20, CalibrationPrompts.estimateSeconds(kept(now, totalMs = 17_400)))
        assertEquals(20, CalibrationPrompts.estimateSeconds(kept(now, totalMs = 20_000)))
        assertEquals(5, CalibrationPrompts.estimateSeconds(kept(now, totalMs = 900)))
    }

    @Test
    fun endsTellHeatAndLeavingApart() {
        assertTrue(CalibrationEnd(false, "${Calibration.TOO_WARM} (thermal status 2) while timing").hot)
        assertTrue(CalibrationEnd(false, Calibration.AWAY).away)
        assertTrue(CalibrationEnd(false, Calibration.READING).away)
        assertFalse(CalibrationEnd(false, "disturbed 31 times").away)
        assertFalse(CalibrationEnd(true, null).hot)
    }
}
