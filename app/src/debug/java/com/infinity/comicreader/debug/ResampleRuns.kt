package com.infinity.comicreader.debug

import com.infinity.comicreader.reader.Calibration
import com.infinity.comicreader.reader.CpuCores
import com.infinity.comicreader.reader.PixelEngine

/** Pure parts of [ResampleCheckService]: what to run, in which order, to what size, and the energy it took. */
internal object ResampleRuns {
    /**
     * The size `time` shrinks pages to: [text] as `WxH`; `screen` for the 2000x3000 page fit
     * to a [screenW] x [screenH] screen held upright, as calibration does ([Calibration.target]);
     * when absent, [DEFAULT_SIZE], the same on every device so runs compare across devices.
     * Null when unreadable.
     */
    fun size(text: String?, screenW: Int, screenH: Int): Pair<Int, Int>? {
        if (text.isNullOrBlank()) return DEFAULT_SIZE
        if (text.trim().equals("screen", ignoreCase = true)) return Calibration.target(screenW, screenH, 2000, 3000)
        val parts = text.trim().lowercase().split('x')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return (w to h).takeIf { w in 1..MAX_SIDE && h in 1..MAX_SIDE }
    }

    private const val MAX_SIDE = 16_000

    /** The 2000x3000 page fit to a 1848x2960 screen. */
    val DEFAULT_SIZE = 1848 to 2772

    /** Where CPU workers run: pinned to the large cores, to the little ones, to the fastest alone, or left to the system. */
    enum class Cores(val key: String) {
        ANY("any"),
        BIG("big"),
        LITTLE("little"),
        PRIME("prime");

        companion object {
            fun of(key: String): Cores? = entries.firstOrNull { it.key == key }
        }
    }

    /** The cores [cores] pins to on a CPU split as [c] says; 0 pins nothing. */
    fun mask(cores: Cores, c: CpuCores): Long = when (cores) {
        Cores.BIG -> c.big
        Cores.LITTLE -> c.little
        Cores.PRIME -> c.prime
        Cores.ANY -> 0L
    }

    /**
     * One engine setup: [threads] CPU workers (Kotlin's, or native code's) on [cores]; the GPU
     * has one calling thread, on [cores] as a native worker would be.
     */
    data class Setup(val engine: PixelEngine, val threads: Int, val cores: Cores) {
        val key: String get() = when {
            engine != PixelEngine.GPU -> "${engine.key}/$threads/${cores.key}"
            cores == Cores.ANY -> engine.key
            else -> "${engine.key}/${cores.key}"
        }

        companion object {
            /**
             * `engine[/threads[/cores]]`, as `native/4/big`, `native/1/little`, `native/2/prime`,
             * `kotlin/8`, `gpu` or `gpu/big` (the GPU's threads are always 1); threads default to
             * [threads], cores to any. Null when unreadable.
             */
            fun of(text: String, threads: Int): Setup? {
                val parts = text.trim().split('/')
                val engine = PixelEngine.of(parts[0]) ?: return null
                if (engine == PixelEngine.GPU) return (if (parts.size > 1) Cores.of(parts.last()) ?: return null else Cores.ANY).let { Setup(engine, 1, it) }
                val n = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size > 1) return null else threads
                val cores = parts.getOrNull(2)?.let { Cores.of(it) ?: return null } ?: Cores.ANY
                if (n !in 1..16 || (engine == PixelEngine.KOTLIN && cores != Cores.ANY)) return null
                return Setup(engine, n, cores)
            }
        }
    }

    /**
     * The order of [setups] in each of [rounds]: forward, then backward, and so on (A B, B A,
     * A B…), so heat from the earlier setups weighs on each about equally.
     */
    fun <T> interleaved(setups: List<T>, rounds: Int): List<List<T>> =
        List(rounds) { if (it % 2 == 0) setups else setups.asReversed() }

    /**
     * The thermal statuses seen through a timed run, from before it starts to after it ends
     * ([see]); an unknown status (-1, before Android 10) counts as none. A run's times count
     * only while it stayed [cool]: from light throttling on, a phone's cores already slow down.
     */
    class Heat {
        /** The highest status seen; -1 before any. */
        var peak: Int = -1
            private set

        fun see(status: Int) {
            if (status > peak) peak = status
        }

        /** True while no status seen reached [WARM]. */
        val cool: Boolean get() = peak < WARM

        companion object {
            /** `PowerManager.THERMAL_STATUS_LIGHT`. */
            const val WARM = 1
        }
    }

    /** Battery current samples in mA, negative while the battery drains. */
    class Draw(val samples: List<Double>) {
        val mean: Double get() = if (samples.isEmpty()) Double.NaN else samples.average()

        /** True when every sample drains the battery: no charger covers any of the work. */
        val draining: Boolean get() = samples.isNotEmpty() && samples.all { it < 0 }
    }

    /**
     * The energy per page in mJ of [pages] made in [seconds] at [volts], from the draw while
     * [busy] above the draw while [idle]; null while [charging] (the battery reports a
     * charger, which can supply part of the work even while the battery drains), when
     * either draw is not wholly draining (the current's sign flips), or nothing was made.
     */
    fun millijoulesPerPage(idle: Draw, busy: Draw, volts: Double, seconds: Double, pages: Int, charging: Boolean = false): Double? {
        if (charging || !idle.draining || !busy.draining || pages <= 0 || volts <= 0 || seconds <= 0) return null
        // Both negative: the extra drain is how much more negative the busy mean is.
        val extraMa = idle.mean - busy.mean
        return extraMa * volts * seconds / pages
    }
}
