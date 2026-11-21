package com.infinity.comicreader.debug

import android.os.BatteryManager
import kotlin.math.abs

/**
 * Pure parts of [EnergyMeter]: battery samples, the charger guard, energy per page above an
 * idle baseline, and its spread across rounds.
 */
internal object EnergyRuns {
    /** One reading [atMs] into its window: current in mA (negative while draining) and voltage in V (0 when unknown). */
    data class Sample(val atMs: Long, val currentMa: Double, val volts: Double)

    /**
     * Readings over one window; [charger] when a charger showed at any check during it;
     * [error] when the sampler failed, its readings stopping there; [screenOff] when the
     * screen was off at any check.
     */
    class Draw(val samples: List<Sample>, val charger: Boolean = false, val error: String? = null, val screenOff: Boolean = false) {
        val meanMa: Double get() = if (samples.isEmpty()) Double.NaN else samples.map { it.currentMa }.average()

        private val withVolts: List<Sample> get() = samples.filter { it.volts > 0 }

        /** Mean power in mW, current times voltage sample by sample (negative while draining); NaN without a voltage. */
        val meanMw: Double get() = withVolts.let { s -> if (s.isEmpty()) Double.NaN else s.map { it.currentMa * it.volts }.average() }

        val minVolts: Double get() = withVolts.minOfOrNull { it.volts } ?: Double.NaN
        val maxVolts: Double get() = withVolts.maxOfOrNull { it.volts } ?: Double.NaN

        /** True when every sample drains the battery: no charger covers any of the work. */
        val draining: Boolean get() = samples.isNotEmpty() && samples.all { it.currentMa < 0 }

        /** Distinct current values in each [windowMs] from the first sample on: a slow fuel gauge repeats its last value. */
        fun distinctPer(windowMs: Long = DISTINCT_WINDOW_MS): List<Int> {
            if (samples.isEmpty()) return emptyList()
            val from = samples.first().atMs
            return samples.groupBy { (it.atMs - from) / windowMs }.toSortedMap().values.map { w -> w.map { it.currentMa }.distinct().size }
        }

        /** Distinct current values over the whole window. */
        val distinct: Int get() = samples.map { it.currentMa }.distinct().size

        /** Distinct voltages over the whole window. */
        val distinctVolts: Int get() = withVolts.map { it.volts }.distinct().size
    }

    /** The window [Draw.distinctPer] counts in by default. */
    const val DISTINCT_WINDOW_MS = 10_000L

    /**
     * True when the battery may sit on a charger: its own [status] (which `dumpsys battery
     * unplug` does not hide) anything but discharging, such as not charging at a charge
     * limit with the cable in, or a [plugged] type other than none. A status the battery
     * does not report (unknown, or none at all) leaves only the plug.
     */
    fun onCharger(status: Int, plugged: Int): Boolean =
        plugged != 0 || (status > BatteryManager.BATTERY_STATUS_UNKNOWN && status != BatteryManager.BATTERY_STATUS_DISCHARGING)

    /**
     * [text] of a sysfs voltage node in volts: microvolts as most report it, or millivolts;
     * null when unreadable or no battery's.
     */
    fun voltsFromNode(text: String?): Double? {
        val v = text?.trim()?.toLongOrNull() ?: return null
        return when (v) {
            in 1_000_000L..10_000_000L -> v / 1e6
            in 1_000L..10_000L -> v / 1e3
            else -> null
        }
    }

    /**
     * One measured run: an [idle] baseline, then [pages] made in [seconds] while [busy] was
     * sampled, taking [cpuMs] of the process's CPU time.
     */
    class Run(val idle: Draw, val busy: Draw, val seconds: Double, val pages: Int, val cpuMs: Long) {
        /** Why no energy per page counts; null when it does. */
        val refused: String? get() = when {
            idle.error != null || busy.error != null -> "the sampler failed: ${idle.error ?: busy.error}"
            idle.charger || busy.charger -> "a charger was connected"
            idle.screenOff || busy.screenOff -> "the screen turned off"
            !idle.draining || !busy.draining -> "the battery did not drain throughout"
            idle.meanMw.isNaN() || busy.meanMw.isNaN() -> "no battery voltage"
            pages <= 0 || seconds <= 0 -> "no pages"
            else -> null
        }

        /** Power above idle in W: how much more the battery drained while busy. */
        val aboveIdleW: Double? get() = if (refused != null) null else (idle.meanMw - busy.meanMw) / 1000

        /** Energy per page above idle in mJ. */
        val mJPerPage: Double? get() = aboveIdleW?.let { it * 1000 * seconds / pages }

        val pagesPerS: Double get() = if (seconds > 0) pages / seconds else 0.0
        val cpuMsPerPage: Double get() = cpuMs.toDouble() / maxOf(pages, 1)
    }

    /**
     * One setup's rounds, each a run and whether it was valid (cool, the app in front, a
     * bench build): the energy per page of the [valid] ones and of those measured but not
     * valid ([invalid]), in round order. Only valid rounds count toward the [spread].
     */
    class Rounds {
        private val runs = ArrayList<Pair<Run, Boolean>>()

        fun add(run: Run, valid: Boolean) {
            runs += run to valid
        }

        val count: Int get() = runs.size
        val valid: List<Double> get() = runs.filter { it.second }.mapNotNull { it.first.mJPerPage }
        val invalid: List<Double> get() = runs.filterNot { it.second }.mapNotNull { it.first.mJPerPage }
        val measured: Int get() = valid.size + invalid.size
        val spread: Spread? get() = spread(valid)
    }

    /** The [mean], [min] and [max] of values, and half their range as a share of the mean ([pct], ±%). */
    data class Spread(val mean: Double, val min: Double, val max: Double, val pct: Double)

    /** The spread of [values]; null when there are none. */
    fun spread(values: List<Double>): Spread? {
        if (values.isEmpty()) return null
        val mean = values.average()
        val min = values.min()
        val max = values.max()
        return Spread(mean, min, max, if (mean == 0.0) 0.0 else (max - min) / 2 / abs(mean) * 100)
    }
}
