package com.infinity.comicreader.ui

import com.infinity.comicreader.ai.AiThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSizeScaleTest {

    @Test
    fun doublingsAreEvenlyApartAfterTheAlwaysSlot() {
        assertEquals(0f, AiSizeScale.fraction(AiThresholds.ALWAYS), 0f)
        assertEquals(1f, AiSizeScale.fraction(200), 1e-6f)
        val octave = AiSizeScale.fraction(200) - AiSizeScale.fraction(100)
        assertEquals(octave, AiSizeScale.fraction(100) - AiSizeScale.fraction(50), 1e-6f)
        assertEquals(octave, AiSizeScale.fraction(50) - AiSizeScale.fraction(25), 1e-6f)
        assertTrue(AiSizeScale.fraction(25) > 0f)
    }

    @Test
    fun handlesSnapToStopsAndRoundTrip() {
        for (pct in AiSizeScale.CLEANUP_STOPS) assertEquals(pct, AiSizeScale.cleanupAt(AiSizeScale.fraction(pct)))
        for (pct in AiSizeScale.UPSCALE_STOPS) assertEquals(pct, AiSizeScale.upscaleAt(AiSizeScale.fraction(pct)))
        assertTrue(AiSizeScale.CLEANUP_STOPS.drop(1).all { it % AiSizeScale.STEP == 0 })
    }

    @Test
    fun upscaleHandleStopsAtTheFloor() {
        assertEquals(AiThresholds.UPSCALE_FLOOR, AiSizeScale.UPSCALE_STOPS.first())
        assertEquals(50, AiSizeScale.upscaleAt(AiSizeScale.fraction(45)))
        assertEquals(50, AiSizeScale.upscaleAt(AiSizeScale.fraction(25)))
        assertEquals(50, AiSizeScale.upscaleAt(0f))
        assertEquals(50, AiSizeScale.upscaleAt(-1f))
        assertEquals(50, AiSizeScale.step(AiSizeScale.UPSCALE_STOPS, 50, -1))
        assertEquals(55, AiSizeScale.step(AiSizeScale.UPSCALE_STOPS, 50, 1))
    }

    @Test
    fun cleanupHandleGoesDownToAlways() {
        assertEquals(AiThresholds.ALWAYS, AiSizeScale.cleanupAt(0f))
        assertEquals(AiThresholds.ALWAYS, AiSizeScale.cleanupAt(AiSizeScale.fraction(25) * 0.4f))
        assertEquals(25, AiSizeScale.cleanupAt(AiSizeScale.fraction(25) * 0.6f))
        assertEquals(AiThresholds.ALWAYS, AiSizeScale.step(AiSizeScale.CLEANUP_STOPS, 25, -1))
        assertEquals(AiThresholds.ALWAYS, AiSizeScale.step(AiSizeScale.CLEANUP_STOPS, AiThresholds.ALWAYS, -1))
        assertEquals(25, AiSizeScale.step(AiSizeScale.CLEANUP_STOPS, AiThresholds.ALWAYS, 1))
    }

    @Test
    fun bothHandlesEndAtTheTop() {
        assertEquals(AiThresholds.MAX_PCT, AiSizeScale.upscaleAt(2f))
        assertEquals(AiThresholds.MAX_PCT, AiSizeScale.cleanupAt(1f))
        assertEquals(200, AiSizeScale.step(AiSizeScale.UPSCALE_STOPS, 200, 1))
    }

    @Test
    fun valuesOffTheStopsStepToTheirNeighbors() {
        assertEquals(40, AiSizeScale.step(AiSizeScale.CLEANUP_STOPS, 37, 0))
        assertEquals(35, AiSizeScale.step(AiSizeScale.CLEANUP_STOPS, 37, -1))
        assertTrue(AiSizeScale.fraction(103) in AiSizeScale.fraction(100)..AiSizeScale.fraction(105))
    }

    @Test
    fun everyStopMakesValidThresholds() {
        // The handles are independent: any pair, crossed or not, is a setting.
        for (up in AiSizeScale.UPSCALE_STOPS) for (clean in AiSizeScale.CLEANUP_STOPS) {
            assertEquals(AiThresholds(up, clean), AiThresholds.of(up, clean))
        }
    }
}
