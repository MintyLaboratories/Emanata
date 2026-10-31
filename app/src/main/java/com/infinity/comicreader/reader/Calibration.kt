package com.infinity.comicreader.reader

import org.json.JSONArray
import org.json.JSONObject

/**
 * How this device decodes and resamples pages: the resampling [engine] with [pixelThreads]
 * CPU workers, pinned to the large cores or not, and native JPEG XL decodes on
 * [jxlThreads], pinned or not. Read-ahead work gets the same budget as pages on screen.
 */
data class Tuning(
    val engine: PixelEngine,
    val pixelThreads: Int,
    val pixelPinned: Boolean,
    val jxlThreads: Int,
    val jxlPinned: Boolean
) {
    /** Makes this the tuning pages are decoded and resampled with from now on. */
    fun apply() {
        NativeResampler.engine = engine
        NativeResampler.threads = pixelThreads
        NativeResampler.laterThreads = pixelThreads
        NativeResampler.pinned = pixelPinned
        JxlPages.threads = jxlThreads
        JxlPages.laterThreads = jxlThreads
        JxlPages.pinned = jxlPinned
    }

    fun toJson(): JSONObject = JSONObject().put("engine", engine.key).put("pixelThreads", pixelThreads).put("pixelPinned", pixelPinned)
        .put("jxlThreads", jxlThreads).put("jxlPinned", jxlPinned)

    companion object {
        /** Before any calibration: native CPU code, one worker and one JPEG XL thread per large core ([CpuCores]), pinned there. */
        fun defaults(cores: CpuCores, processors: Int): Tuning {
            val n = NativeResampler.defaultThreads(cores, processors)
            return Tuning(PixelEngine.NATIVE, n, true, n, true)
        }

        /** The tuning [json] holds; null when it holds none. */
        fun of(json: JSONObject?): Tuning? = json?.let { j ->
            runCatching {
                Tuning(
                    PixelEngine.of(j.getString("engine")) ?: return null,
                    j.getInt("pixelThreads").coerceIn(1, MAX_THREADS), j.getBoolean("pixelPinned"),
                    j.getInt("jxlThreads").coerceIn(1, MAX_THREADS), j.getBoolean("jxlPinned")
                )
            }.getOrNull()
        }

        private const val MAX_THREADS = 16
    }
}

/** A setup calibration times: resampling ([PIXELS]) or JPEG XL decoding ([JXL]) on [threads] workers, or the GPU. */
data class Candidate(val kind: Kind, val threads: Int, val pinned: Boolean, val gpu: Boolean = false) {
    enum class Kind(val key: String) { PIXELS("pixels"), JXL("jxl") }

    val key: String get() = if (gpu) "${kind.key}/gpu" else "${kind.key}/$threads/${if (pinned) "big" else "any"}"
}

/** The median wall and CPU time of [candidate] over its runs. */
data class Timing(val candidate: Candidate, val wallMs: Double, val cpuMs: Double) {
    fun toJson(): JSONObject = JSONObject().put("setup", candidate.key).put("wallMs", wallMs).put("cpuMs", cpuMs)
}

/**
 * Per-device calibration's choices, kept free of the device so they can be tested: which
 * setups to time ([candidates]) and which wins ([pick], [tuning]).
 */
object Calibration {
    /**
     * Setups within this share of the fastest wall time count as fast; the one of those
     * using the least CPU time wins, since battery matters as much as speed. JPEG XL on
     * synthetic pages on a tablet with 4 large and 4 little cores: 8 threads on any core
     * decode about 10 % faster than 4 on the large ones, for 40-60 % more CPU time; the
     * large ones win.
     */
    const val TOLERANCE = 0.10

    /**
     * The setups to time on [cores] with [processors] CPUs: CPU workers 1, 2, one per large
     * core and one per core for resampling (with the GPU when [gpu]), 2, one per large core
     * and one per core for JPEG XL decodes ([jxl]). Workers that fit on the large cores are
     * pinned there; more are left to the system.
     */
    fun candidates(cores: CpuCores, processors: Int, gpu: Boolean, jxl: Boolean): List<Candidate> {
        val all = processors.coerceAtLeast(1)
        val big = NativeResampler.defaultThreads(cores, all)
        fun of(kind: Candidate.Kind, counts: List<Int>) = counts.filter { it in 1..all }.distinct().sorted()
            .map { Candidate(kind, it, pinned = cores.big != 0L && it <= cores.bigCount) }
        val out = ArrayList(of(Candidate.Kind.PIXELS, listOf(1, 2, big, all)))
        if (gpu) out += Candidate(Candidate.Kind.PIXELS, 1, pinned = false, gpu = true)
        if (jxl) out += of(Candidate.Kind.JXL, listOf(2, big, all))
        return out
    }

    /** The winner of [timings]: of those within [TOLERANCE] of the fastest, the one using the least CPU time. */
    fun pick(timings: List<Timing>): Timing? {
        val fastest = timings.minOfOrNull { it.wallMs } ?: return null
        return timings.filter { it.wallMs <= fastest * (1 + TOLERANCE) }.minWith(compareBy({ it.cpuMs }, { it.wallMs }))
    }

    /** [before] with each kind [timings] cover set to its winner ([pick]). */
    fun tuning(timings: List<Timing>, before: Tuning): Tuning {
        var t = before
        pick(timings.filter { it.candidate.kind == Candidate.Kind.PIXELS })?.candidate?.let { c ->
            t = if (c.gpu) t.copy(engine = PixelEngine.GPU) else t.copy(engine = PixelEngine.NATIVE, pixelThreads = c.threads, pixelPinned = c.pinned)
        }
        pick(timings.filter { it.candidate.kind == Candidate.Kind.JXL })?.candidate?.let { c ->
            t = t.copy(jxlThreads = c.threads, jxlPinned = c.pinned)
        }
        return t
    }

    /** What a calibration is kept for: this install of the app on this system build. */
    fun fingerprint(app: String, system: String): String = "$app|$system"

    /** The median of [xs]; NaN when empty. */
    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun timingsJson(timings: List<Timing>): JSONArray = JSONArray().apply { timings.forEach { put(it.toJson()) } }
}

/** What a calibration run needs of the device. */
internal interface CalibrationProbe {
    /** One run of [candidate]: its wall and CPU time in ms, or null when it cannot run here. */
    fun time(candidate: Candidate): Pair<Double, Double>?

    /**
     * Waits until results would be fair (the app in front, nothing else busy, not hot);
     * false when they will not be soon, so the run stops and tries another time.
     */
    fun awaitFair(): Boolean

    /** True when nothing disturbed the run just made. */
    fun stillFair(): Boolean
}

/**
 * Times [candidates] on [probe]: [warmup] rounds left out, then [rounds] counted, the order
 * reversed every other round (A B, B A…) so heat weighs on each alike. A run something
 * disturbed is made again, up to [retries] times in all.
 */
internal class CalibrationRun(
    private val probe: CalibrationProbe,
    private val candidates: List<Candidate>,
    private val rounds: Int = 5,
    private val warmup: Int = 1,
    private val retries: Int = 10
) {
    /** The median timing of each candidate that ran; null when the run stopped first. */
    fun run(stopped: () -> Boolean = { false }): List<Timing>? {
        val walls = candidates.associateWith { ArrayList<Double>() }
        val cpus = candidates.associateWith { ArrayList<Double>() }
        val missing = HashSet<Candidate>()
        var redone = 0
        for (round in 0 until warmup + rounds) {
            val order = if (round % 2 == 0) candidates else candidates.asReversed()
            for (c in order) {
                if (c in missing) continue
                while (true) {
                    if (stopped() || !probe.awaitFair()) return null
                    val t = probe.time(c)
                    if (t == null) {
                        missing += c
                        break
                    }
                    if (probe.stillFair()) {
                        if (round >= warmup) {
                            walls.getValue(c) += t.first
                            cpus.getValue(c) += t.second
                        }
                        break
                    }
                    if (++redone > retries) return null
                }
            }
        }
        return candidates.filter { it !in missing && walls.getValue(it).isNotEmpty() }
            .map { Timing(it, Calibration.median(walls.getValue(it)), Calibration.median(cpus.getValue(it))) }
    }
}
