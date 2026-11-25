package minty.emanata.ai

import minty.emanata.reader.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiThresholdsTest {

    /** A 1000 x 1500 source shown at [pct] % of its side. */
    private fun shown(pct: Int) = Plan(10 * pct, 15 * pct, 0.0, 0.0, 1000.0, 1500.0)

    @Test
    fun defaultsUpscaleAboveFullSizeAndCleanUpAboveHalf() {
        val t = AiThresholds()
        assertEquals(100, t.upscalePct)
        assertEquals(50, t.cleanupPct)
        assertTrue(t.upscales(101.0))
        assertFalse(t.upscales(100.0))
        assertTrue(t.cleansUp(51.0))
        assertFalse(t.cleansUp(50.0))
    }

    @Test
    fun upscaleHandleStopsAtTheFloor() {
        assertEquals(50, AiThresholds.of(49, 50).upscalePct)
        assertEquals(50, AiThresholds.of(0, 50).upscalePct)
        assertThrows(IllegalArgumentException::class.java) { AiThresholds(49, 50) }
        assertTrue(AiThresholds(50, 50).upscales(51.0))
        assertFalse(AiThresholds(50, 50).upscales(50.0))
    }

    @Test
    fun cleanupHandleGoesDownToAlways() {
        val always = AiThresholds.of(100, 10)
        assertEquals(AiThresholds.ALWAYS, always.cleanupPct)
        assertTrue(always.cleansUp(1.0))
        assertEquals(25, AiThresholds.of(100, 25).cleanupPct)
        assertThrows(IllegalArgumentException::class.java) { AiThresholds(100, 10) }
    }

    @Test
    fun handlesMayCross() {
        val t = AiThresholds(upscalePct = 60, cleanupPct = 150)
        assertTrue(t.runs(AiStep.UPSCALE, 100.0))
        assertFalse(t.runs(AiStep.ARTIFACTS, 100.0))
    }

    @Test
    fun descreenRunsAtEverySize() {
        val t = AiThresholds(100, 200)
        for (pct in listOf(1.0, 25.0, 50.0, 100.0, 300.0)) assertTrue(t.runs(AiStep.DESCREEN, pct))
    }

    @Test
    fun shownPercentIsSideLengthOnTheLargerAxis() {
        assertEquals(100.0, AiThresholds.shownPercent(shown(100)), 1e-9)
        assertEquals(50.0, AiThresholds.shownPercent(shown(50)), 1e-9)
        assertEquals(200.0, AiThresholds.shownPercent(shown(200)), 1e-9)
        assertEquals(80.0, AiThresholds.shownPercent(Plan(800, 600, 0.0, 0.0, 1000.0, 1000.0)), 1e-9)
    }

    @Test
    fun shownPercentCountsEarlierModelsScale() {
        // Shown at 150 % of the source: 75 % of a 2x model's output.
        assertEquals(75.0, AiThresholds.shownPercent(shown(150), scale = 2), 1e-9)
        assertFalse(AiThresholds().upscales(AiThresholds.shownPercent(shown(150), scale = 2)))
    }

    @Test
    fun defaultUpscaleMatchesPlanShrinks() {
        for (pct in listOf(49, 50, 99, 100, 101, 200)) {
            val plan = shown(pct)
            assertEquals("$pct %", !plan.shrinks(), AiThresholds().upscales(AiThresholds.shownPercent(plan)))
        }
    }
}
