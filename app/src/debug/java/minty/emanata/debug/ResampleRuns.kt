package minty.emanata.debug

import minty.emanata.reader.Calibration
import minty.emanata.reader.CpuCores
import minty.emanata.reader.PixelEngine

/** Pure parts of [ResampleCheckService]: what to run, in which order, and to what size. */
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
        return dimensions(text)
    }

    /** The synthetic page `sustain` shrinks: [text] as `WxH`, else [DEFAULT_SOURCE]; null when unreadable. */
    fun source(text: String?): Pair<Int, Int>? = if (text.isNullOrBlank()) DEFAULT_SOURCE else dimensions(text)

    /** The page `sustain` shrinks when none is asked. */
    val DEFAULT_SOURCE = 2000 to 3000

    /** [text] as `WxH`, each side up to [MAX_SIDE]; null when unreadable. */
    private fun dimensions(text: String): Pair<Int, Int>? {
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
             * `kotlin/8`, `gpu` or `gpu/big` (the GPU's threads are always 1, so `gpu/1/big` and
             * `gpu/1` read as `gpu/big` and `gpu`); threads default to [threads], cores to any.
             * Null when unreadable.
             */
            fun of(text: String, threads: Int): Setup? {
                val parts = text.trim().split('/')
                val engine = PixelEngine.of(parts[0]) ?: return null
                if (engine == PixelEngine.GPU) {
                    val rest = parts.drop(1).let { if (it.firstOrNull()?.toIntOrNull() != null) it.drop(1) else it }
                    if (rest.size > 1) return null
                    return Setup(engine, 1, rest.firstOrNull()?.let { Cores.of(it) ?: return null } ?: Cores.ANY)
                }
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

    /** A trim of what the GPU path keeps: when it freed ("now", "at close" or "after failure") and how many across programs went. */
    data class GpuTrim(val freed: String, val programs: Int)

    private val TRIM_LINE = Regex("""\btrim (now|at close|after failure) freed=\S+ programs=(\d+)""")

    /** The trims in [lines] of the GPU path's log ("ResampleGpu", as `resample_gpu.c` writes them), in order. */
    fun gpuTrims(lines: List<String>): List<GpuTrim> =
        lines.mapNotNull { TRIM_LINE.find(it) }.map { GpuTrim(it.groupValues[1], it.groupValues[2].toInt()) }

    /** True when [trims] hold exactly one trim done as a job closed, its programs with it, and none done at once. */
    fun trimmedAtClose(trims: List<GpuTrim>): Boolean =
        trims.count { it.freed == "at close" && it.programs > 0 } == 1 && trims.none { it.freed == "now" }
}
