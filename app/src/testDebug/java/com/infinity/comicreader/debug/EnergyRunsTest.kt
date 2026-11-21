package com.infinity.comicreader.debug

import android.os.BatteryManager
import com.infinity.comicreader.debug.EnergyRuns.Draw
import com.infinity.comicreader.debug.EnergyRuns.Run
import com.infinity.comicreader.debug.EnergyRuns.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyRunsTest {
    /** Samples 100 ms apart of [ma] at [volts]. */
    private fun draw(vararg ma: Double, volts: Double = 4.0, charger: Boolean = false) =
        Draw(ma.mapIndexed { i, a -> Sample(i * 100L, a, volts) }, charger)

    @Test
    fun energyIsTheSignedPowerAboveIdle() {
        // Idle drains 400 mA, busy 900 mA: 2 W more at 4 V for 10 s over 20 pages = 1000 mJ each.
        val run = Run(draw(-390.0, -410.0), draw(-850.0, -950.0), 10.0, 20, 500)
        assertNull(run.refused)
        assertEquals(2.0, run.aboveIdleW!!, 1e-9)
        assertEquals(1000.0, run.mJPerPage!!, 1e-9)
        assertEquals(2.0, run.pagesPerS, 1e-9)
        assertEquals(25.0, run.cpuMsPerPage, 1e-9)
    }

    @Test
    fun powerTakesEachSamplesOwnVoltage() {
        // -1000 mA at 4 V and -1000 mA at 3 V: 3.5 W, not -1000 mA times a voltage read once.
        val busy = Draw(listOf(Sample(0, -1000.0, 4.0), Sample(100, -1000.0, 3.0)))
        assertEquals(-3500.0, busy.meanMw, 1e-9)
        // A sample without a voltage counts for the current, not the power.
        val partly = Draw(listOf(Sample(0, -1000.0, 4.0), Sample(100, -3000.0, 0.0)))
        assertEquals(-4000.0, partly.meanMw, 1e-9)
        assertEquals(-2000.0, partly.meanMa, 1e-9)
        assertEquals(4.0, partly.minVolts, 1e-9)
        assertEquals(1, partly.distinctVolts)
        assertTrue(Draw(listOf(Sample(0, -1000.0, 0.0))).meanMw.isNaN())
    }

    @Test
    fun aChargerOrAFlippedSignRefusesTheEstimate() {
        val idle = draw(-400.0, -400.0)
        val busy = draw(-900.0, -900.0)
        assertEquals("a charger was connected", Run(idle, draw(-900.0, charger = true), 10.0, 20, 0).refused)
        // A charger that cannot keep up: draining throughout, yet it supplies part of the work.
        assertNull(Run(draw(-400.0, charger = true), busy, 10.0, 20, 0).mJPerPage)
        // Charging while idle, or a single sample not draining.
        assertEquals("the battery did not drain throughout", Run(draw(200.0, 150.0), busy, 10.0, 20, 0).refused)
        assertNull(Run(draw(-400.0, 30.0), busy, 10.0, 20, 0).mJPerPage)
        assertNull(Run(idle, draw(-900.0, 0.0), 10.0, 20, 0).mJPerPage)
        assertNull(Run(Draw(emptyList()), busy, 10.0, 20, 0).mJPerPage)
        assertEquals("no battery voltage", Run(draw(-400.0, volts = 0.0), busy, 10.0, 20, 0).refused)
        assertEquals("no pages", Run(idle, busy, 10.0, 0, 0).refused)
        assertNull(Run(idle, busy, 10.0, 0, 0).aboveIdleW)
    }

    @Test
    fun aChargerShowsByStatusOrPlug() {
        assertTrue(EnergyRuns.onCharger(BatteryManager.BATTERY_STATUS_CHARGING, 0))
        assertTrue(EnergyRuns.onCharger(BatteryManager.BATTERY_STATUS_FULL, 0))
        // Plugged yet not charging (held at a level): still on a charger.
        assertTrue(EnergyRuns.onCharger(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_USB))
        assertTrue(EnergyRuns.onCharger(BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertFalse(EnergyRuns.onCharger(BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertFalse(EnergyRuns.onCharger(BatteryManager.BATTERY_STATUS_NOT_CHARGING, 0))
    }

    @Test
    fun distinctCurrentsCountPerTenSeconds() {
        // A gauge refreshing every few seconds: 25 s of samples, 100 ms apart.
        val samples = (0 until 250).map { i -> Sample(5_000L + i * 100L, -400.0 - (i / 40), 4.0) }
        val d = Draw(samples)
        // Windows from the first sample: 0-10 s holds values 0..2, 10-20 s 2..4, 20-25 s 5..6.
        assertEquals(listOf(3, 3, 2), d.distinctPer())
        assertEquals(7, d.distinct)
        assertEquals(listOf(7), d.distinctPer(60_000))
        assertEquals(emptyList<Int>(), Draw(emptyList()).distinctPer())
    }

    @Test
    fun voltageNodesReadInMicroOrMillivolts() {
        assertEquals(3.85, EnergyRuns.voltsFromNode("3850000\n")!!, 1e-9)
        assertEquals(4.2, EnergyRuns.voltsFromNode("4200")!!, 1e-9)
        for (bad in listOf(null, "", "abc", "0", "-3850000", "385")) assertNull(bad, EnergyRuns.voltsFromNode(bad))
    }

    @Test
    fun spreadIsHalfTheRangeOverTheMean() {
        val s = EnergyRuns.spread(listOf(78.6, 81.3, 84.5))!!
        assertEquals(81.4667, s.mean, 1e-3)
        assertEquals(78.6, s.min, 1e-9)
        assertEquals(84.5, s.max, 1e-9)
        assertEquals(2.95 / 81.4667 * 100, s.pct, 1e-3)
        assertEquals(0.0, EnergyRuns.spread(listOf(5.0))!!.pct, 1e-9)
        assertNull(EnergyRuns.spread(emptyList()))
    }
}
