package com.infinity.comicreader.reader

import org.json.JSONArray
import java.io.File
import org.json.JSONObject
import kotlin.math.abs

/**
 * How this device decodes and resamples pages: the resampling [engine] with [pixelThreads]
 * CPU workers, pinned to the large cores or not, and native JPEG XL decodes on
 * [jxlThreads], pinned or not. Pages read ahead get the same budget as pages on screen: one
 * often comes on screen mid-decode, and fewer JPEG XL threads for them measured slower fast
 * page turns for little less CPU time at reading pace. Platform JPEG decodes are pinned to
 * the large cores with the other platform formats when [jpegPinned] ([PlatformPages]).
 */
data class Tuning(
    val engine: PixelEngine,
    val pixelThreads: Int,
    val pixelPinned: Boolean,
    val jxlThreads: Int,
    val jxlPinned: Boolean,
    val jpegPinned: Boolean = true
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
        PlatformPages.jpegPinned = jpegPinned
    }

    fun toJson(): JSONObject = JSONObject().put("engine", engine.key).put("pixelThreads", pixelThreads).put("pixelPinned", pixelPinned)
        .put("jxlThreads", jxlThreads).put("jxlPinned", jxlPinned).put("jpegPinned", jpegPinned)

    companion object {
        /**
         * Before any calibration: native CPU code, one worker and one JPEG XL thread per large
         * core ([CpuCores]), pinned there; nothing pinned where there are no large cores to pin
         * to ([CpuCores.big] 0), so these match the setups calibration times there.
         */
        fun defaults(cores: CpuCores, processors: Int): Tuning {
            val n = NativeResampler.defaultThreads(cores, processors)
            val pinned = cores.big != 0L
            return Tuning(PixelEngine.NATIVE, n, pinned, n, pinned, pinned)
        }

        /** The tuning [json] holds; null when it holds none. */
        fun of(json: JSONObject?): Tuning? = json?.let { j ->
            runCatching {
                Tuning(
                    PixelEngine.of(j.getString("engine")) ?: return null,
                    j.getInt("pixelThreads").coerceIn(1, MAX_THREADS), j.getBoolean("pixelPinned"),
                    j.getInt("jxlThreads").coerceIn(1, MAX_THREADS), j.getBoolean("jxlPinned"),
                    j.optBoolean("jpegPinned", true)
                )
            }.getOrNull()
        }

        private const val MAX_THREADS = 16
    }
}

/**
 * A setup calibration times: resampling ([PIXELS]) or JPEG XL decoding ([JXL]) on [threads]
 * workers, or the GPU; or a platform JPEG decode ([JPEG]), its thread pinned or not.
 */
data class Candidate(val kind: Kind, val threads: Int, val pinned: Boolean, val gpu: Boolean = false) {
    enum class Kind(val key: String) { PIXELS("pixels"), JXL("jxl"), JPEG("jpeg") }

    val key: String get() = when {
        gpu -> "${kind.key}/gpu"
        kind == Kind.JPEG -> "${kind.key}/${if (pinned) "big" else "any"}"
        else -> "${kind.key}/$threads/${if (pinned) "big" else "any"}"
    }
}

/** The median wall and CPU time of [candidate] over its runs, and how far its runs stray ([Calibration.spread]). */
data class Timing(
    val candidate: Candidate,
    val wallMs: Double,
    val cpuMs: Double,
    val wallSpreadMs: Double = 0.0,
    val cpuSpreadMs: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject().put("setup", candidate.key).put("wallMs", wallMs).put("cpuMs", cpuMs)
        .put("wallSpreadMs", wallSpreadMs).put("cpuSpreadMs", cpuSpreadMs)
}

/**
 * Per-device calibration's choices, kept free of the device so they can be tested: which
 * setups to time ([candidates]) and which wins ([pick], [tuning]). Differences within the
 * runs' own spread count as none, so the setup in effect stays unless another one beats it
 * by more than noise.
 */
object Calibration {
    /**
     * Setups within this share of the fastest wall time count as fast; the one of those
     * using the least CPU time wins, since battery matters as much as speed. JPEG XL on
     * synthetic pages on a tablet with 4 large and 4 little cores: 8 threads on any core
     * decode about 10 % faster than 4 on the large ones, for 40-60 % more CPU time; the
     * large ones win. CPU time on a little core costs less energy than on a large one, so
     * this leans toward the large cores where both are close.
     */
    const val TOLERANCE = 0.20

    /**
     * The setups to time on [cores] with [processors] CPUs: CPU workers 1, 2, one per large
     * core and one per core for resampling (with the GPU when [gpu]), 2, one per large core
     * and one per core for JPEG XL decodes ([jxl]). Workers that fit on the large cores are pinned there; more are
     * left to the system. With [jpeg], a platform JPEG decode pinned to the large cores and
     * one left to the system, where there are large cores to pin to.
     */
    fun candidates(cores: CpuCores, processors: Int, gpu: Boolean, jxl: Boolean, jpeg: Boolean = false): List<Candidate> {
        val all = processors.coerceAtLeast(1)
        val big = NativeResampler.defaultThreads(cores, all)
        fun of(kind: Candidate.Kind, counts: List<Int>) = counts.filter { it in 1..all }.distinct().sorted()
            .map { Candidate(kind, it, pinned = cores.big != 0L && it <= cores.bigCount) }
        val out = ArrayList(of(Candidate.Kind.PIXELS, listOf(1, 2, big, all)))
        if (gpu) out += Candidate(Candidate.Kind.PIXELS, 1, pinned = false, gpu = true)
        if (jxl) out += of(Candidate.Kind.JXL, listOf(2, big, all))
        if (jpeg && cores.big != 0L) out += listOf(Candidate(Candidate.Kind.JPEG, 1, pinned = true), Candidate(Candidate.Kind.JPEG, 1, pinned = false))
        return out
    }

    /**
     * An unpinned JPEG decode must be faster than a pinned one by this share (and its own
     * spread) for platform JPEG to be left unpinned. Where the system decoder spreads a page
     * over several cores, pinning confines it to the large ones. On a tablet with 2 large and
     * 6 little cores, synthetic 2000x3000 and 4000x6000 JPEGs decoded on their own took
     * 1.5-1.6x as long pinned; calibration, between its other setups, measured 1.0-1.6x
     * (median 1.1x). Its CPU time is not weighed, unlike [pick]'s: unpinned, the decode used
     * about 1.7x the CPU time, but mostly on the little cores, while pinned the large cores ran
     * mostly at their lowest clock; which costs less energy is unknown.
     */
    const val JPEG_MARGIN = 0.05

    /**
     * An unpinned JPEG decode using at least this many cores' worth of CPU time over its wall
     * time is multithreaded. Only then can pinning slow it: a one-thread decode timed warm
     * runs as fast anywhere, while at reading pace it would often start on a little core.
     */
    const val JPEG_THREADED = 1.5

    /**
     * Whether platform JPEG decodes stay pinned, from the [JPEG][Candidate.Kind.JPEG] [timings]:
     * pinned where the system decodes on one thread; else [before] unless the other choice
     * is faster by more than [JPEG_MARGIN] and its own spread (a slow choice's outliers never
     * count, as in [pick]).
     */
    fun jpegPinned(timings: List<Timing>, before: Boolean): Boolean {
        val jpeg = timings.filter { it.candidate.kind == Candidate.Kind.JPEG }
        val pinned = jpeg.firstOrNull { it.candidate.pinned } ?: return before
        val any = jpeg.firstOrNull { !it.candidate.pinned } ?: return before
        if (any.cpuMs < any.wallMs * JPEG_THREADED) return true
        fun beats(a: Timing, b: Timing) = a.wallMs < b.wallMs * (1 - JPEG_MARGIN) && b.wallMs - a.wallMs > a.wallSpreadMs
        return if (before) !beats(any, pinned) else beats(pinned, any)
    }

    /**
     * The winner of [timings]: of those within [tolerance] of the fastest (or within the
     * fastest's own spread of it), the one using the least CPU time; [incumbent], the setup
     * in effect, while within [stay] more than that, unless the winner uses less CPU time by
     * more than the noise ([cpuNoise]). A slow setup's own spread never counts, so its
     * outliers cannot make it fast.
     */
    fun pick(timings: List<Timing>, incumbent: Candidate? = null, tolerance: Double = TOLERANCE, stay: Double = 0.0): Timing? {
        val fastest = timings.minByOrNull { it.wallMs } ?: return null
        fun fast(t: Timing, share: Double) = t.wallMs <= fastest.wallMs * (1 + share) || t.wallMs - fastest.wallMs <= fastest.wallSpreadMs
        val winner = timings.filter { fast(it, tolerance) }.minWith(compareBy({ it.cpuMs }, { it.wallMs }))
        val kept = timings.firstOrNull { it.candidate == incumbent && fast(it, tolerance + stay) } ?: return winner
        return if (kept.cpuMs - winner.cpuMs <= cpuNoise(kept, winner)) kept else winner
    }

    /**
     * How much further than [TOLERANCE] behind the fastest a resampling setup an earlier
     * calibration chose ([chosen]) may fall and still count as fast: up to 1.4x the fastest's
     * time, so a setup near the edge doesn't change with every calibration. On a tablet with 4
     * large and 4 little cores, resampling on the 4 large cores took 1.04-1.34x as long as on 8
     * unpinned threads over 79 calibrations in one day, for about two thirds of their CPU time;
     * without this margin each calibration could pick either. JPEG XL decodes get none until
     * their flips are measured. Defaults that no calibration chose get none either, so a first
     * calibration still moves off them where they take over 1.2x the fastest's time.
     */
    const val STAY_MARGIN = 0.20

    /**
     * The CPU time difference between [a] and [b] that counts as none: their spread, but at
     * least [CPU_NOISE_SHARE] of the lesser CPU time and [CPU_NOISE_MS]. CPU times come in whole
     * ms, so medians of steady runs often spread 0.
     */
    internal fun cpuNoise(a: Timing, b: Timing): Double =
        maxOf(a.cpuSpreadMs, b.cpuSpreadMs, CPU_NOISE_SHARE * minOf(a.cpuMs, b.cpuMs), CPU_NOISE_MS)

    private const val CPU_NOISE_SHARE = 0.05
    private const val CPU_NOISE_MS = 1.0

    /**
     * True when [gpu] beats every CPU setup in [cpu] by more than [TOLERANCE] and the
     * spread: its CPU time says nothing of its own power, so it must visibly win on time.
     */
    fun gpuWins(gpu: Timing, cpu: List<Timing>): Boolean {
        val fastest = cpu.minByOrNull { it.wallMs } ?: return true
        return gpu.wallMs * (1 + TOLERANCE) < fastest.wallMs && fastest.wallMs - gpu.wallMs > maxOf(gpu.wallSpreadMs, fastest.wallSpreadMs)
    }

    /**
     * [before] with each kind [timings] cover set to its winner ([pick], [gpuWins]); [before]
     * keeps its resampling setup near the edge ([STAY_MARGIN]) when a calibration chose it ([chosen]).
     */
    fun tuning(timings: List<Timing>, before: Tuning, chosen: Boolean = false): Tuning {
        var t = before
        val stay = if (chosen) STAY_MARGIN else 0.0
        val pixels = timings.filter { it.candidate.kind == Candidate.Kind.PIXELS }
        val gpu = pixels.firstOrNull { it.candidate.gpu }
        val cpu = pixels.filter { !it.candidate.gpu }
        if (gpu != null && gpuWins(gpu, cpu)) {
            t = t.copy(engine = PixelEngine.GPU)
        } else {
            pick(cpu, Candidate(Candidate.Kind.PIXELS, before.pixelThreads, before.pixelPinned), stay = stay)?.candidate?.let { c ->
                t = t.copy(engine = PixelEngine.NATIVE, pixelThreads = c.threads, pixelPinned = c.pinned)
            }
        }
        val jxl = timings.filter { it.candidate.kind == Candidate.Kind.JXL }
        pick(jxl, Candidate(Candidate.Kind.JXL, before.jxlThreads, before.jxlPinned))?.candidate?.let { c ->
            t = t.copy(jxlThreads = c.threads, jxlPinned = c.pinned)
        }
        return t.copy(jpegPinned = jpegPinned(timings, before.jpegPinned))
    }

    /**
     * True when [before], the tuning in effect, is the pick of [kept], the calibration kept,
     * made out of battery saver: only such a pick earns [STAY_MARGIN]. One made under battery
     * saver, whose clock caps skew timings, is replaced by the next run out of saver ([due]).
     * A pick kept from an earlier app version or system build keeps the margin: automatic runs
     * come only after an update, and a setup near the edge should not change with each one.
     */
    fun chosen(kept: JSONObject?, before: Tuning): Boolean =
        kept != null && !kept.optBoolean("powerSave") && Tuning.of(kept.optJSONObject("tuning")) == before

    /**
     * The size a [width] x [height] page is shrunk to on a [screenW] x [screenH] screen held
     * upright, as calibration times it: whole, never enlarged. It differs between devices, so
     * each calibration keeps it with its timings.
     */
    fun target(screenW: Int, screenH: Int, width: Int, height: Int): Pair<Int, Int> {
        val shortSide = minOf(screenW, screenH).coerceAtLeast(1)
        val longSide = maxOf(screenW, screenH).coerceAtLeast(1)
        val scale = minOf(1.0, shortSide.toDouble() / width, longSide.toDouble() / height)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** What a calibration is kept for: this install of the app on this system build. */
    fun fingerprint(app: String, system: String): String = "$app|$system"

    /**
     * The bytes of the page [name].[ext] in [dir] made for [fingerprint]: as kept, or else
     * made by [make] and kept, older ones of [name] deleted; null when it cannot be made. An
     * update of the app or system may draw or encode the page differently, so each fingerprint
     * makes its own.
     */
    internal fun cachedPage(dir: File, name: String, ext: String, fingerprint: String, make: () -> ByteArray?): ByteArray? = runCatching {
        val tag = "%08x".format(fingerprint.hashCode())
        val f = File(dir, "$name-$tag.$ext")
        if (!f.exists()) {
            dir.mkdirs()
            dir.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.delete() }
            val bytes = make() ?: return null
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return bytes
            }
        }
        f.readBytes()
    }.getOrNull()

    /**
     * True when [kept], the calibration kept, is not for [fingerprint] (this install and
     * system build), or was made under battery saver; false while [fingerprint] is unknown.
     */
    fun due(kept: JSONObject?, fingerprint: String?): Boolean =
        fingerprint != null && (kept?.optString("fingerprint") != fingerprint || kept?.optBoolean("powerSave") == true)

    /**
     * Why a calibration run cannot go on now, or null: a check or benchmark holds it off,
     * the reader or another app is in front, or, unless [forced], the device is off the
     * charger ([charging], asked only then).
     */
    fun stopReason(held: Boolean, reading: Boolean, inFront: Boolean, forced: Boolean, charging: () -> Boolean): String? = when {
        held -> "held off by a check"
        reading -> "reading"
        !inFront -> "app not in front"
        !forced && !charging() -> "not charging"
        else -> null
    }

    /**
     * What a calibration run waits out before timing, or null: app work, heat (thermal
     * status [HOT] or above; [LIGHT] or above while [cooling], after a wait for heat, as some
     * chips throttle there already), or, unless [forced], battery saver, whose clock caps skew
     * timings.
     */
    fun waitReason(busy: Boolean, thermalStatus: Int, powerSave: Boolean, forced: Boolean, cooling: Boolean = false): String? = when {
        busy -> "app busy"
        thermalStatus >= if (cooling) LIGHT else HOT -> "$TOO_WARM (thermal status $thermalStatus)"
        powerSave && !forced -> "battery saver on"
        else -> null
    }

    /**
     * Why a run that must wait ([wait], for heat when [hot]) gives up, or null to wait on:
     * at once for heat once it has begun timing ([timing]), as its own work heats the device
     * and what it timed next would be throttled; else once its waits add up to over [GIVE_UP_MS].
     */
    fun giveUp(wait: String, hot: Boolean, timing: Boolean, waitedMs: Long): String? = when {
        hot && timing -> "$wait while timing"
        waitedMs > GIVE_UP_MS -> "waited over ${GIVE_UP_MS / 1000} s in all: $wait"
        else -> null
    }

    /** How [waitReason] begins when the wait is for heat. */
    const val TOO_WARM = "too warm"

    /** PowerManager.THERMAL_STATUS_LIGHT: some devices throttle a little. */
    const val LIGHT = 1

    /** PowerManager.THERMAL_STATUS_MODERATE: the device has begun to throttle. */
    const val HOT = 2

    /** The longest a calibration run waits for fair moments in all before it tries another time. */
    const val GIVE_UP_MS = 120_000L

    /** The median of [xs]; NaN when empty. */
    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * How far [xs] stray: [SPREAD_MADS] times their median absolute deviation, about two
     * standard deviations of steady runs, which one or two outliers among five do not move;
     * 0 when empty.
     */
    internal fun spread(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val m = median(xs)
        return SPREAD_MADS * median(xs.map { abs(it - m) })
    }

    private const val SPREAD_MADS = 3.0

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

    /** True when nothing disturbed the sample made since [awaitFair] last said fair. */
    fun stillFair(): Boolean

    /** Why [awaitFair] last said no; null when unknown. */
    val why: String? get() = null

    /** True while battery saver is on (only a forced run times under it). */
    val powerSave: Boolean get() = false
}

/**
 * Times [candidates] on [probe] in [rounds], the order reversed every other round (A B,
 * B A…) so heat weighs on each alike, [warmup] rounds left out. Each sample waits for the
 * device to be fair, runs the candidate once untimed when [warm] (the wait lets the cores
 * clock down, which the first run after it pays for), then takes the median of [burst]
 * runs back to back. A sample something disturbed is made again, up to [retries] times in
 * all per run a sample makes: a longer sample is the likelier to be disturbed.
 */
internal class CalibrationRun(
    private val probe: CalibrationProbe,
    private val candidates: List<Candidate>,
    private val rounds: Int = 5,
    private val warmup: Int = 0,
    private val burst: Int = 3,
    private val warm: Boolean = true,
    private val retries: Int = 10
) {
    /** Why the last [run] stopped first; null when it finished. */
    var why: String? = null
        private set

    /** True when the last [run] kept a sample made with battery saver on ([CalibrationProbe.powerSave]). */
    var sawPowerSave: Boolean = false
        private set

    private val mostRedone = retries * (burst.coerceAtLeast(1) + if (warm) 1 else 0)

    /** The median timing of each candidate that ran, with its spread over the samples; null when the run stopped first ([why]). */
    fun run(stopped: () -> Boolean = { false }): List<Timing>? {
        why = null
        sawPowerSave = false
        val walls = candidates.associateWith { ArrayList<Double>() }
        val cpus = candidates.associateWith { ArrayList<Double>() }
        val missing = HashSet<Candidate>()
        var redone = 0
        for (round in 0 until warmup + rounds) {
            val order = if (round % 2 == 0) candidates else candidates.asReversed()
            for (c in order) {
                if (c in missing) continue
                while (true) {
                    if (stopped()) return stop("stopped")
                    if (!probe.awaitFair()) return stop(probe.why ?: "not fair")
                    val saver = probe.powerSave
                    val t = sample(c)
                    if (t == null) {
                        missing += c
                        break
                    }
                    if (probe.stillFair()) {
                        if (round >= warmup) {
                            walls.getValue(c) += t.first
                            cpus.getValue(c) += t.second
                            if (saver || probe.powerSave) sawPowerSave = true
                        }
                        break
                    }
                    if (++redone > mostRedone) return stop("disturbed $redone times")
                }
            }
        }
        return candidates.filter { it !in missing && walls.getValue(it).isNotEmpty() }.map {
            val w = walls.getValue(it)
            val c = cpus.getValue(it)
            Timing(it, Calibration.median(w), Calibration.median(c), Calibration.spread(w), Calibration.spread(c))
        }
    }

    private fun stop(reason: String): List<Timing>? {
        why = reason
        return null
    }

    /** One sample of [c]: the medians of a warmed burst; null when it cannot run. */
    private fun sample(c: Candidate): Pair<Double, Double>? {
        if (warm && probe.time(c) == null) return null
        val w = ArrayList<Double>(burst)
        val u = ArrayList<Double>(burst)
        repeat(burst.coerceAtLeast(1)) {
            val t = probe.time(c) ?: return null
            w += t.first
            u += t.second
        }
        return Calibration.median(w) to Calibration.median(u)
    }
}
