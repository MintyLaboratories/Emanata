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
 * the large cores with the other platform formats when [jpegPinned] ([PlatformPages]). The
 * GPU's calling thread is pinned to the large cores when [gpuPinned]; the CPU workers keep
 * their own setup, which the GPU falls back to.
 */
data class Tuning(
    val engine: PixelEngine,
    val pixelThreads: Int,
    val pixelPinned: Boolean,
    val jxlThreads: Int,
    val jxlPinned: Boolean,
    val jpegPinned: Boolean = true,
    val gpuPinned: Boolean = true
) {
    /** Makes this the tuning pages are decoded and resampled with from now on. */
    fun apply() {
        NativeResampler.engine = engine
        NativeResampler.threads = pixelThreads
        NativeResampler.laterThreads = pixelThreads
        NativeResampler.pinned = pixelPinned
        NativeResampler.gpuPinned = gpuPinned
        JxlPages.threads = jxlThreads
        JxlPages.laterThreads = jxlThreads
        JxlPages.pinned = jxlPinned
        PlatformPages.jpegPinned = jpegPinned
    }

    fun toJson(): JSONObject = JSONObject().put("engine", engine.key).put("pixelThreads", pixelThreads).put("pixelPinned", pixelPinned)
        .put("jxlThreads", jxlThreads).put("jxlPinned", jxlPinned).put("jpegPinned", jpegPinned).put("gpuPinned", gpuPinned)

    companion object {
        /**
         * Before any calibration: native CPU code, one worker and one JPEG XL thread per large
         * core ([CpuCores]), pinned there with the GPU's caller; nothing pinned where there are
         * no large cores to pin to ([CpuCores.big] 0), so these match the setups calibration
         * times there.
         */
        fun defaults(cores: CpuCores, processors: Int): Tuning {
            val n = NativeResampler.defaultThreads(cores, processors)
            val pinned = cores.big != 0L
            return Tuning(PixelEngine.NATIVE, n, pinned, n, pinned, pinned, pinned)
        }

        /** The tuning [json] holds; null when it holds none. */
        fun of(json: JSONObject?): Tuning? = json?.let { j ->
            runCatching {
                Tuning(
                    PixelEngine.of(j.getString("engine")) ?: return null,
                    j.getInt("pixelThreads").coerceIn(1, MAX_THREADS), j.getBoolean("pixelPinned"),
                    j.getInt("jxlThreads").coerceIn(1, MAX_THREADS), j.getBoolean("jxlPinned"),
                    j.optBoolean("jpegPinned", true), j.getBoolean("gpuPinned")
                )
            }.getOrNull()
        }

        private const val MAX_THREADS = 16
    }
}

/**
 * A setup calibration times: resampling ([PIXELS]) or JPEG XL decoding ([JXL]) on [threads]
 * workers, or the GPU with its calling thread; or a platform JPEG decode ([JPEG]), its thread
 * pinned or not.
 */
data class Candidate(val kind: Kind, val threads: Int, val pinned: Boolean, val gpu: Boolean = false) {
    enum class Kind(val key: String) { PIXELS("pixels"), JXL("jxl"), JPEG("jpeg") }

    val key: String get() {
        val on = if (pinned) "big" else "any"
        return when {
            gpu -> "${kind.key}/gpu/$on"
            kind == Kind.JPEG -> "${kind.key}/$on"
            else -> "${kind.key}/$threads/$on"
        }
    }
}

/**
 * The median wall and CPU time of [candidate] over its runs, its CPU time weighed by the
 * cores it ran on ([weightedMs], [Calibration.weighted]), and how far its runs stray ([Calibration.spread]).
 */
data class Timing(
    val candidate: Candidate,
    val wallMs: Double,
    val cpuMs: Double,
    val wallSpreadMs: Double = 0.0,
    val cpuSpreadMs: Double = 0.0,
    val weightedMs: Double = cpuMs,
    val weightedSpreadMs: Double = cpuSpreadMs
) {
    fun toJson(): JSONObject = JSONObject().put("setup", candidate.key).put("wallMs", wallMs).put("cpuMs", cpuMs)
        .put("wallSpreadMs", wallSpreadMs).put("cpuSpreadMs", cpuSpreadMs).put("weightedMs", weightedMs).put("weightedSpreadMs", weightedSpreadMs)
}

/** One run of a setup: its wall time, CPU time and CPU time weighed by the cores it ran on ([Calibration.weighted]), in ms. */
data class RunTime(val wallMs: Double, val cpuMs: Double, val weightedMs: Double = cpuMs)

/**
 * Per-device calibration's choices, kept free of the device so they can be tested: which
 * setups to time ([candidates]) and which wins ([pick], [tuning]). Differences within the
 * runs' own spread count as none, so the setup in effect stays unless another one beats it
 * by more than noise.
 */
object Calibration {
    /**
     * Setups within this share of the fastest wall time count as fast; the one of those
     * using the least CPU time weighed by the cores it ran on ([weighted]) wins, since battery
     * matters as much as speed. JPEG XL on synthetic pages on a tablet with 4 large and 4
     * little cores: 8 threads on any core decode about 10 % faster than 4 on the large ones,
     * for 40-60 % more CPU time.
     */
    const val TOLERANCE = 0.20

    /**
     * Bumped whenever the setups timed or the method change: every device then calibrates
     * again ([fingerprint]). A test pins a hash of what the method is made of ([method],
     * [Calibrator.method]) to each version, the GPU path's revision
     * ([NativeResampler.GPU_REVISION]) included.
     */
    const val VERSION = 5

    /** Rounds a run times every setup in ([CalibrationRun]). */
    const val ROUNDS = 5

    /** How many times over a run may make its samples again before it stops ([CalibrationRun]). */
    const val RETRIES = 10

    /**
     * What this object's part of the method is made of, as text: the setups timed on a few
     * kinds of chips, the rules that pick among them, the bounds that judge samples fair, and
     * the GPU path's revision.
     */
    fun method(): String {
        val chips = listOf(
            CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024), capacities = intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024)),
            CpuCores(intArrayOf(435, 435, 435, 435, 435, 435, 1024, 1024), capacities = intArrayOf(435, 435, 435, 435, 435, 435, 1024, 1024)),
            CpuCores(intArrayOf(765, 765, 765, 765, 765, 765, 1024, 1024)),
            CpuCores(IntArray(0))
        )
        val setups = chips.joinToString(";") { c -> candidates(c, 8, gpu = true, jxl = true, jpeg = true).joinToString(",") { it.key } }
        return listOf(
            setups, TOLERANCE, STAY_MARGIN, JPEG_MARGIN, JPEG_THREADED, CPU_NOISE_SHARE, CPU_NOISE_MS, coreWeight(435, 1024),
            OTHERS_SHARE, OTHERS_FLOOR_MS, HOUSEKEEPING.sorted(), REST_SHARE, REST_MIN_MS, REST_MAX_MS, LIGHT, HOT, BATTERY_LIGHT_C, BATTERY_HOT_C,
            SPREAD_MADS, ROUNDS, RETRIES, NativeResampler.GPU_REVISION
        ).joinToString("|")
    }

    /**
     * [cpuMs] weighed by the cores it ran on, as an energy proxy: each core's share of the
     * time the core ledger counted ([coreNs], ns by core number) times its [coreWeight] (from
     * its `cpu_capacity`, [capacities]). Raw CPU time is the wrong proxy on chips with large
     * and little cores: there a decode spread over the little cores too can use less energy
     * than one on the large cores alone, for far more CPU time. The ledger counts the threads
     * doing the timed work (native resampling bands, a GPU band's calling thread, JPEG XL
     * decodes and their workers), each charging its CPU time between ticks (a band, a JPEG XL
     * group) to the core it is on at the tick, so unpinned threads are followed wherever the
     * system moves them; the rest of [cpuMs] (the runtime, glue code, the GPU driver's
     * threads) is taken to run where they did. [cpuMs] as is where capacities are unknown or
     * the ledger counted nothing (platform decodes). The GPU's only picks where its caller
     * runs, never the GPU over the CPU ([gpuWins]).
     */
    fun weighted(cpuMs: Double, coreNs: LongArray, capacities: IntArray): Double {
        val top = capacities.maxOrNull()?.takeIf { it > 0 } ?: return cpuMs
        var total = 0.0
        var weighed = 0.0
        for (n in coreNs.indices) {
            val ns = coreNs[n].coerceAtLeast(0L).toDouble()
            total += ns
            weighed += ns * coreWeight(capacities.getOrNull(n)?.takeIf { it > 0 } ?: top, top)
        }
        return if (total <= 0.0) cpuMs else cpuMs * weighed / total
    }

    /**
     * What a ms of CPU time on a core of [capacity] costs against one on the fastest core
     * ([top]), as energy: the square of their capacities' ratio. A core's power grows faster
     * than its speed (larger cores spend more transistors on each instruction, higher clocks
     * need higher voltage, and dynamic power goes with clock times voltage squared). Fitted to
     * the one measurement on battery alone: on a chip with 2 large cores (capacity 1024) and 6
     * little ones (435), JPEG XL decodes on 8 threads used 19 % less energy per page than on
     * the 2 large cores, for 2.1x the CPU time. With that CPU time spread evenly over the 8
     * cores, squared capacity gives 2.1 x (0.25 + 0.75 x 0.18) = 0.81 of the large cores'
     * cost; capacity alone (a little ms 0.42 of a large one) gives 1.19 and picks the large
     * cores, the wrong choice there. Squared, 8 threads cost less once over 64 % of their CPU
     * time is on the little cores; where both cluster sizes run flat out it is 71 % there.
     */
    fun coreWeight(capacity: Int, top: Int): Double {
        val r = capacity.toDouble() / top
        return r * r
    }

    /**
     * The setups to time on [cores] with [processors] CPUs: CPU workers 1, 2, one per large
     * core and one per core for resampling (with the GPU when [gpu]), 2, one per large core
     * and one per core for JPEG XL decodes ([jxl]). Workers that fit on the large cores are pinned there; more are
     * left to the system. The GPU's calling thread is timed left to the system and, where there are large cores, pinned there as one worker. With [jpeg], a platform JPEG decode of a 4K-class page pinned to the large cores and
     * one left to the system, where there are large cores to pin to.
     */
    fun candidates(cores: CpuCores, processors: Int, gpu: Boolean, jxl: Boolean, jpeg: Boolean = false): List<Candidate> {
        val all = processors.coerceAtLeast(1)
        val big = NativeResampler.defaultThreads(cores, all)
        fun of(kind: Candidate.Kind, counts: List<Int>) = counts.filter { it in 1..all }.distinct().sorted()
            .map { Candidate(kind, it, pinned = cores.big != 0L && it <= cores.bigCount) }
        val out = ArrayList(of(Candidate.Kind.PIXELS, listOf(1, 2, big, all)))
        if (gpu) out += listOf(false, true).filter { !it || cores.big != 0L }.map { Candidate(Candidate.Kind.PIXELS, 1, pinned = it, gpu = true) }
        if (jxl) out += of(Candidate.Kind.JXL, listOf(2, big, all))
        if (jpeg && cores.big != 0L) out += listOf(Candidate(Candidate.Kind.JPEG, 1, pinned = true), Candidate(Candidate.Kind.JPEG, 1, pinned = false))
        return out
    }

    /**
     * An unpinned JPEG decode must be faster than a pinned one by this share (and its own
     * spread) for platform JPEG to be left unpinned. Calibration times a synthetic 4000x6000
     * page (quality 90), 4K class as image decode timings use: a smaller page can hide a
     * difference pages of reading size show. Where the system decoder spreads a page
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
     * fastest's own spread of it), the one using the least CPU time weighed by core capacity
     * ([Timing.weightedMs]); [incumbent], the setup in effect, while within [stay] more than
     * that, unless the winner uses less of it by more than the noise ([cpuNoise]). A slow
     * setup's own spread never counts, so its outliers cannot make it fast.
     */
    fun pick(timings: List<Timing>, incumbent: Candidate? = null, tolerance: Double = TOLERANCE, stay: Double = 0.0): Timing? {
        val fastest = timings.minByOrNull { it.wallMs } ?: return null
        fun fast(t: Timing, share: Double) = t.wallMs <= fastest.wallMs * (1 + share) || t.wallMs - fastest.wallMs <= fastest.wallSpreadMs
        val winner = timings.filter { fast(it, tolerance) }.minWith(compareBy({ it.weightedMs }, { it.wallMs }))
        val kept = timings.firstOrNull { it.candidate == incumbent && fast(it, tolerance + stay) } ?: return winner
        return if (kept.weightedMs - winner.weightedMs <= cpuNoise(kept, winner)) kept else winner
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
     * The weighed CPU time difference between [a] and [b] that counts as none: their spread,
     * but at least [CPU_NOISE_SHARE] of the lesser and [CPU_NOISE_MS]. CPU times come in whole
     * ms, so medians of steady runs often spread 0.
     */
    internal fun cpuNoise(a: Timing, b: Timing): Double =
        maxOf(a.weightedSpreadMs, b.weightedSpreadMs, CPU_NOISE_SHARE * minOf(a.weightedMs, b.weightedMs), CPU_NOISE_MS)

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
     * [before] with each kind [timings] cover set to its winner ([pick], [gpuWins]): the CPU
     * workers' setup even when the GPU wins, since the GPU falls back to it, and the GPU's
     * caller placed as its fastest, least costly timing was; [before] keeps its resampling
     * setups near the edge ([STAY_MARGIN]) when a calibration chose them ([chosen]).
     */
    fun tuning(timings: List<Timing>, before: Tuning, chosen: Boolean = false): Tuning {
        var t = before
        val stay = if (chosen) STAY_MARGIN else 0.0
        val pixels = timings.filter { it.candidate.kind == Candidate.Kind.PIXELS }
        val cpu = pixels.filter { !it.candidate.gpu }
        pick(cpu, Candidate(Candidate.Kind.PIXELS, before.pixelThreads, before.pixelPinned), stay = stay)?.candidate?.let { c ->
            t = t.copy(engine = PixelEngine.NATIVE, pixelThreads = c.threads, pixelPinned = c.pinned)
        }
        val gpu = pick(pixels.filter { it.candidate.gpu }, Candidate(Candidate.Kind.PIXELS, 1, before.gpuPinned, gpu = true), stay = stay)
        if (gpu != null && gpuWins(gpu, cpu)) t = t.copy(engine = PixelEngine.GPU, gpuPinned = gpu.candidate.pinned)
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
     * A pick kept from an earlier system build or [VERSION] keeps the margin: automatic runs
     * come only after those change, and a setup near the edge should not change with each one.
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

    /**
     * What a calibration is kept for: calibration [version] ([VERSION]) on this [system]
     * build. App updates that change neither the setups nor the method keep it.
     */
    fun fingerprint(version: Int, system: String): String = "v$version|$system"

    /** True when [kept], a calibration kept, was made by this calibration [VERSION] (on any system build): only then are its timings comparable with this version's. */
    fun sameVersion(kept: JSONObject?): Boolean = kept?.optString("fingerprint")?.startsWith(fingerprint(VERSION, "")) == true

    /** What calibration's encoded pages are kept for: this install of the [app] on this [system] build, whose drawing and encoders make them. */
    fun pageFingerprint(app: String, system: String): String = "$app|$system"

    /**
     * The bytes of the page [name].[ext] in [dir] made for [fingerprint] ([pageFingerprint]):
     * as kept, or else made by [make] and kept, older ones of [name] deleted; null when it
     * cannot be made. An update of the app or system may draw or encode the page differently,
     * so each fingerprint makes its own.
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
     * True when [kept], the calibration kept, is not for [fingerprint] (this calibration
     * version and system build), or was made under battery saver; false while [fingerprint] is unknown.
     */
    fun due(kept: JSONObject?, fingerprint: String?): Boolean =
        fingerprint != null && (kept?.optString("fingerprint") != fingerprint || kept?.optBoolean("powerSave") == true)

    /**
     * Why a calibration run cannot go on now, or null: a check or benchmark holds it off,
     * the reader ([READING]) or another app ([AWAY]) is in front.
     */
    fun stopReason(held: Boolean, reading: Boolean, inFront: Boolean): String? = when {
        held -> "held off by a check"
        reading -> READING
        !inFront -> AWAY
        else -> null
    }

    /** Why a run stopped when a book opened ([stopReason]). */
    const val READING = "reading"

    /** Why a run stopped when the app was left ([stopReason]). */
    const val AWAY = "app not in front"

    /**
     * What a calibration run waits out before timing, or null: app work, heat (thermal
     * status [HOT] or above, [heatStatus]; [LIGHT] or above while [cooling], after a wait for
     * heat, as some chips throttle there already), or, unless the user chose to measure
     * [underSaver], battery saver, whose clock caps skew timings.
     */
    fun waitReason(busy: Boolean, thermalStatus: Int, powerSave: Boolean, underSaver: Boolean, cooling: Boolean = false): String? = when {
        busy -> "app busy"
        thermalStatus >= if (cooling) LIGHT else HOT -> "$TOO_WARM (thermal status $thermalStatus)"
        powerSave && !underSaver -> "battery saver on"
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

    /**
     * The heat signal as a thermal status: [thermalStatus] where the device has a thermal HAL
     * ([thermalKnown] true); battery temperature's ([batteryTenthsC], tenths of a degree C,
     * [batteryStatus]) where it has none or reports no status (null); the higher of both where
     * that cannot be told ([thermalKnown] null: Android 10, which reports status 0 without a HAL).
     * Battery temperature is a check at the start only: it comes from the last battery
     * broadcast, which can be minutes old (hours apart on a full battery on the charger), so
     * where it is the only signal a run that heats up is not ended by it.
     */
    fun heatStatus(thermalStatus: Int?, thermalKnown: Boolean?, batteryTenthsC: Int?): Int {
        val battery = batteryTenthsC?.let { batteryStatus(it) } ?: 0
        return when {
            thermalStatus == null || thermalKnown == false -> battery
            thermalKnown == true -> thermalStatus
            else -> maxOf(thermalStatus, battery)
        }
    }

    /**
     * The thermal status battery temperature [tenthsC] (tenths of a degree C) stands for
     * where there is none: [HOT] from [BATTERY_HOT_C], [LIGHT] from [BATTERY_LIGHT_C], else 0.
     * Charging in a room keeps a battery well under these (the reference tablet: 27-32 °C on a
     * fast charger); a battery past 40 °C means the device is warm through, where charging
     * rules (JEITA) commonly start cutting charge current.
     */
    fun batteryStatus(tenthsC: Int): Int = when {
        tenthsC >= BATTERY_HOT_C * 10 -> HOT
        tenthsC >= BATTERY_LIGHT_C * 10 -> LIGHT
        else -> 0
    }

    /** Battery temperature in °C standing for thermal status [LIGHT] ([batteryStatus]). */
    const val BATTERY_LIGHT_C = 40

    /** Battery temperature in °C standing for thermal status [HOT] ([batteryStatus]). */
    const val BATTERY_HOT_C = 42

    /**
     * CPU time in ms the app's other threads used during a sample: what each thread in
     * [after] gained since [before] (all of it for one born since), less what the core ledger
     * [counted] as calibration's work on it (ns by thread id: a shared pool thread that ran
     * a resampling band counts only its time beyond the band), but for those [skip] names
     * ([skips]). Threads gone since are not here ([goneMs]).
     */
    fun othersMs(before: Map<Int, Long>, after: Map<Int, Long>, counted: Map<Int, Long> = emptyMap(), skip: (Int) -> Boolean): Double {
        var ns = 0L
        for ((tid, now) in after) {
            if (skip(tid)) continue
            ns += (now - (before[tid] ?: 0L) - (counted[tid] ?: 0L)).coerceAtLeast(0L)
        }
        return ns / 1e6
    }

    /**
     * CPU time in ms of threads that ended during a sample, which no per-thread reading
     * shows: what the whole process used ([processMs], read within the two readings of every
     * thread) beyond what the threads still there gained from [before] to [after] and what
     * the core ledger [counted] for threads gone since (calibration's own JPEG XL workers,
     * which end with each decode). Its readings lie within theirs, so it can only fall short.
     */
    fun goneMs(processMs: Double, before: Map<Int, Long>, after: Map<Int, Long>, counted: Map<Int, Long>): Double {
        var ns = 0L
        for ((tid, now) in after) ns += (now - (before[tid] ?: 0L)).coerceAtLeast(0L)
        for ((tid, c) in counted) if (tid !in after) ns += c
        return (processMs - ns / 1e6).coerceAtLeast(0.0)
    }

    /**
     * The threads born during a GPU run of calibration's ([before] to [after], each the live
     * thread ids): the GPU driver's, which run inside the app and do calibration's work, so
     * they count as calibration's own from then on ([skips]). Another of the app's threads
     * born just then would be missed, its CPU time still measured.
     */
    fun bornDuringGpuRun(before: Set<Int>, after: Set<Int>): Set<Int> = after - before

    /**
     * The threads a sample's check of the app's other threads leaves out whole: calibration's
     * [own] (the one timing, the one preparing pages, GPU driver threads born during its GPU
     * runs: [bornDuringGpuRun]), the [ui] threads (the app drawing
     * itself, left out of CPU times too) and the runtime's housekeeping ([housekeeping], by
     * [name]). Threads that ran calibration's native work are not among them: what they did
     * besides is counted ([othersMs]).
     */
    fun skips(own: Set<Int>, ui: Collection<Int>, name: (Int) -> String): (Int) -> Boolean =
        { tid -> tid in own || tid in ui || housekeeping(name(tid)) }

    /**
     * True when the app's other threads used too much CPU time ([othersMs]) in a sample of
     * [wallMs] for it to count: more than a tenth of a core ([OTHERS_SHARE], as the idle
     * check allows), and more than [OTHERS_FLOOR_MS], or a clock tick more where times come in
     * ticks ([coarse]).
     */
    fun othersBusy(othersMs: Double, wallMs: Double, coarse: Boolean): Boolean =
        othersMs > maxOf(wallMs * OTHERS_SHARE, OTHERS_FLOOR_MS + if (coarse) UiCpu.TICK_NS / 1e6 else 0.0)

    /** The share of one core the app's other threads may use during a sample ([othersBusy]). */
    const val OTHERS_SHARE = 0.10

    /** CPU time the app's other threads may always use during a sample ([othersBusy]): wakeups, a binder call. */
    const val OTHERS_FLOOR_MS = 2.0

    /**
     * True for the runtime's housekeeping threads, whose work calibration's own allocations
     * and code bring on as much as anything else does (garbage collection, finalizers, JIT):
     * left out of [othersMs] during samples, while still part of the CPU time measured.
     */
    fun housekeeping(name: String): Boolean = name in HOUSEKEEPING

    /** Thread names (`comm`, at most 15 characters) of the runtime's housekeeping. */
    private val HOUSEKEEPING = setOf("HeapTaskDaemon", "FinalizerDaemon", "ReferenceQueueD", "Jit thread pool")

    /** PowerManager.THERMAL_STATUS_LIGHT: some devices throttle a little. */
    const val LIGHT = 1

    /** PowerManager.THERMAL_STATUS_MODERATE: the device has begun to throttle. */
    const val HOT = 2

    /** The longest a calibration run waits for fair moments in all before it tries another time. */
    const val GIVE_UP_MS = 120_000L

    /**
     * The rest before a sample, in ms, after one that took [previousMs]: [REST_SHARE] of it,
     * from [REST_MIN_MS] to [REST_MAX_MS] (all samples rested 300 ms before they were checked
     * while they run), so the cores rest as for pages decoded at reading pace. Back to back, a
     * run's own load lowers the large cores' clock caps within a fraction of a second at
     * thermal status 0 (a power limit), which skews setups unevenly, most of all the sample
     * after a long one. On a tablet with 4 large and 4 little cores (Snapdragon 8 Gen 1 class,
     * Android 16; 3 runs each), against a fixed 300 ms: with no rest, resampling on the large
     * cores went from 0.95-1.05x to 1.14x the time of 8 threads on any core, close to
     * [TOLERANCE], and JPEG XL there took 1.14x as long; a fixed 100 ms kept that ratio while
     * the JPEG XL page was 2000x3000 but not at 4000x6000 (1.15x, up to 1.22x); resting until
     * every clock was at its floor and every cap back (about 40 ms) did not either (1.14x): the
     * limit outlasts what the clocks show. This rest kept every setup within 5 % and the
     * ratios that decide picks within noise (1.03x), in 37 instead of 44 s.
     */
    fun restMs(previousMs: Double): Long = (previousMs * REST_SHARE).toLong().coerceIn(REST_MIN_MS, REST_MAX_MS)

    /** The share of the previous sample's length rested before the next ([restMs]). */
    const val REST_SHARE = 0.3

    /** The shortest and longest rest before a sample ([restMs]). */
    const val REST_MIN_MS = 100L
    const val REST_MAX_MS = 300L

    /**
     * True when a CPU frequency domain may now run slower than it could earlier in the run:
     * its clock cap in [now] ([ClockCaps]) is below the highest seen ([top]). False when
     * either is unknown or they differ in count. Calibration counts samples a cap fell
     * during; it cannot redo or end them, as its own load lowers caps ([restMs]).
     */
    fun capped(top: IntArray?, now: IntArray?): Boolean =
        top != null && now != null && top.size == now.size && now.indices.any { now[it] < top[it] }

    /** [top] raised to [now] wherever [now] is higher; [now] while there is no [top], [top] while [now] is unknown. */
    fun raised(top: IntArray?, now: IntArray?): IntArray? = when {
        now == null -> top
        top == null || top.size != now.size -> now.copyOf()
        else -> IntArray(top.size) { maxOf(top[it], now[it]) }
    }

    /** The longest a run tries at its start for the app to go idle ([IdleChecks]) before it waits as for any sample. */
    const val SETTLE_MS = 3_000L

    /** True when battery saver and heat, last read at [checkedAt] (null: never), are due to be read again at [now]: each read is a call to the system. */
    fun refreshDue(checkedAt: Long?, now: Long): Boolean = checkedAt == null || now - checkedAt >= CHECK_MS

    /** How often a run reads battery saver and heat again ([refreshDue]). */
    const val CHECK_MS = 1_000L

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

/**
 * When a calibration run checks that the app is idle before a sample: at its start, trying
 * for up to [Calibration.SETTLE_MS] (what brought the app to the front settling); then no
 * more, as each sample checks the app's other threads itself; again, with one try, before
 * the sample after one something disturbed, so redone samples never run back to back.
 */
internal class IdleChecks {
    private var seen = false
    private var tried = false

    /** How long to keep trying for idle before the next sample, in ms: null when no check is due, 0 for one try. */
    fun due(): Long? = when {
        seen -> null
        tried -> 0L
        else -> Calibration.SETTLE_MS
    }

    /** A check ended, finding the app [idle] or not. */
    fun checked(idle: Boolean) {
        tried = true
        if (idle) seen = true
    }

    /** A sample was disturbed: the next one checks for idle first. */
    fun disturbed() {
        seen = false
    }
}

/** What a calibration run needs of the device. */
internal interface CalibrationProbe {
    /** One run of [candidate]: its times, or null when it cannot run here. */
    fun time(candidate: Candidate): RunTime?

    /**
     * Waits until results would be fair (the app in front, nothing else busy, not hot);
     * false when they will not be soon, so the run stops and tries another time.
     */
    fun awaitFair(): Boolean

    /** True when nothing disturbed the sample made since [awaitFair] last said fair. */
    fun stillFair(): Boolean

    /** Why [awaitFair] last said no; null when unknown. */
    val why: String? get() = null

    /** True while battery saver is on (only a run the user let measure under it times then). */
    val powerSave: Boolean get() = false
}

/**
 * Times [candidates] on [probe] in [rounds], the order reversed every other round (A B,
 * B A…) so heat weighs on each alike, [warmup] rounds left out. Each sample waits for the
 * device to be fair, runs the candidate once untimed when [warm] (cores the setup before
 * left at other clocks, or a wait let clock down, make the first run after pay for it),
 * then takes the median of [burst] runs back to back. A sample something disturbed is made
 * again, up to [retries] times in all per run a sample makes: a longer sample is the
 * likelier to be disturbed.
 *
 * Two shortcuts, off by default and for debug comparisons only: [warmOnce] warms only in
 * the first round; [winners], when given, ends the run once what it makes of the timings so
 * far (the tuning they pick) stays the same over a round, from round [minRounds] on.
 * [progress] hears where the run is before each sample it tries and once at its end.
 */
internal class CalibrationRun(
    private val probe: CalibrationProbe,
    private val candidates: List<Candidate>,
    private val rounds: Int = Calibration.ROUNDS,
    private val warmup: Int = 0,
    private val burst: Int = 3,
    private val warm: Boolean = true,
    private val retries: Int = Calibration.RETRIES,
    private val warmOnce: Boolean = false,
    private val winners: ((List<Timing>) -> Any?)? = null,
    private val minRounds: Int = 3,
    private val progress: ((CalibrationProgress) -> Unit)? = null
) {
    /** Why the last [run] stopped first; null when it finished. */
    var why: String? = null
        private set

    /** True when the last [run] kept a sample made with battery saver on ([CalibrationProbe.powerSave]). */
    var sawPowerSave: Boolean = false
        private set

    /** Samples the last [run] made again. */
    var redone: Int = 0
        private set

    /** Time the last [run] spent on samples it made again, in ms. */
    var redoneMs: Long = 0
        private set

    /** Rounds the last [run] went through, warm-up rounds included. */
    var roundsRun: Int = 0
        private set

    private val mostRedone = retries * (burst.coerceAtLeast(1) + if (warm) 1 else 0)

    /** The median timing of each candidate that ran, with its spread over the samples; null when the run stopped first ([why]). */
    fun run(stopped: () -> Boolean = { false }): List<Timing>? {
        why = null
        sawPowerSave = false
        redone = 0
        redoneMs = 0
        roundsRun = 0
        val samples = candidates.associateWith { ArrayList<RunTime>() }
        val missing = HashSet<Candidate>()
        var said: Any? = null
        for (round in 0 until warmup + rounds) {
            val order = if (round % 2 == 0) candidates else candidates.asReversed()
            for (c in order) {
                if (c in missing) continue
                progress?.invoke(where(samples, missing, c, round))
                while (true) {
                    if (stopped()) return stop("stopped")
                    if (!probe.awaitFair()) return stop(probe.why ?: "not fair")
                    val saver = probe.powerSave
                    val sampledFrom = System.nanoTime()
                    val t = sample(c, warm && (!warmOnce || round == 0))
                    if (t == null) {
                        missing += c
                        break
                    }
                    if (probe.stillFair()) {
                        if (round >= warmup) {
                            samples.getValue(c) += t
                            if (saver || probe.powerSave) sawPowerSave = true
                        }
                        break
                    }
                    redoneMs += (System.nanoTime() - sampledFrom) / 1_000_000
                    if (++redone > mostRedone) return stop("disturbed $redone times")
                }
            }
            roundsRun = round + 1
            val timed = round + 1 - warmup
            if (winners != null && timed >= 1) {
                val now = winners.invoke(timings(samples, missing))
                if (timed >= minRounds && now == said) break
                said = now
            }
        }
        progress?.invoke(where(samples, missing, null, roundsRun).let { it.copy(done = it.total) })
        return timings(samples, missing)
    }

    /** Where the run is, about to sample [next] in [round] (from 0, warm-up rounds counted): samples kept of those it means to keep. */
    private fun where(samples: Map<Candidate, List<RunTime>>, missing: Set<Candidate>, next: Candidate?, round: Int) = CalibrationProgress(
        timing = true,
        done = samples.values.sumOf { it.size },
        total = (candidates.size - missing.size) * rounds,
        candidate = next,
        round = (round - warmup + 1).coerceIn(1, rounds)
    )

    /** Each candidate's median times over [samples] and their spread; those [missing] or never sampled left out. */
    private fun timings(samples: Map<Candidate, List<RunTime>>, missing: Set<Candidate>): List<Timing> =
        candidates.filter { it !in missing && samples.getValue(it).isNotEmpty() }.map {
            val s = samples.getValue(it)
            val w = s.map { r -> r.wallMs }
            val c = s.map { r -> r.cpuMs }
            val g = s.map { r -> r.weightedMs }
            Timing(it, Calibration.median(w), Calibration.median(c), Calibration.spread(w), Calibration.spread(c), Calibration.median(g), Calibration.spread(g))
        }

    private fun stop(reason: String): List<Timing>? {
        why = reason
        return null
    }

    /** One sample of [c]: the medians of a burst, after an untimed run when [warmed]; null when it cannot run. */
    private fun sample(c: Candidate, warmed: Boolean): RunTime? {
        if (warmed && probe.time(c) == null) return null
        val runs = ArrayList<RunTime>(burst)
        repeat(burst.coerceAtLeast(1)) { runs += probe.time(c) ?: return null }
        return RunTime(Calibration.median(runs.map { it.wallMs }), Calibration.median(runs.map { it.cpuMs }), Calibration.median(runs.map { it.weightedMs }))
    }
}
