package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.debug.ResampleRuns.Cores
import com.infinity.comicreader.debug.ResampleRuns.Setup
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.CpuCores
import com.infinity.comicreader.reader.LibPixels
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PixelEngine
import com.infinity.comicreader.reader.PixelKernels
import com.infinity.comicreader.reader.Resampler
import com.infinity.comicreader.reader.ScaleFilter
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.SyntheticPage
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Debug and bench builds only: checks the native resampler against [Resampler]'s
 * premultiplied model of it ([check]), and times engine setups on synthetic pages ([time],
 * [sustain]). Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.ResampleCheckService
 *  [--es do check|time|sustain|all] [--es setups kotlin/8,native/4/big,native/1/little,gpu,gpu/big] [--ei reps 10]
 *  [--ei seconds 20] [--ei rounds 2] [--ei cooldown 30] [--es filters lanczos3,lanczos6,bicubic,hermite] [--es rings 0,50,100]
 *  [--es size 1848x2772|screen] [--ez trace true]`
 *
 * A setup is `engine[/threads[/cores]]`: Kotlin or native code on that many workers, native
 * workers pinned to the large or little cores ([CpuCores]), to the fastest alone (`prime`),
 * or left to the system (`any`); a little setup runs as work nobody waits on (`Urgency`).
 * The GPU's calling thread is placed the same way (`gpu/big`); plain `gpu` leaves it to the
 * system.
 *
 * - check: native CPU and GPU against the model on small noise images (every filter,
 *   anti-ringing, linear light, translucency, windows), and the CPU against the shipping
 *   Kotlin path on an opaque page; the largest channel difference of each, and a CRC-32 of
 *   each engine's outputs (`digest`): equal digests across builds mean bit-identical output.
 *   Also the GPU band with a stop callback, and trimming ([checkGpuBand]); then a GPU job
 *   canceled before its second chunk ([canceledGpuCheck]).
 * - time: a 2000x3000 and a 4000x6000 page shrunk to `size` (by default 1848x2772 on every
 *   device, so runs compare across devices; `screen`: the 2000x3000 page fit to this screen
 *   held upright, as calibration does)
 *   by each setup, filter and anti-ringing strength when shrinking (`rings`, default the
 *   setting's default), `reps` rounds after 2 warm-up rounds, the setups interleaved
 *   (A B, B A...): wall and process CPU per page, thermal status and headroom before and
 *   after, and the highest thermal status seen, sampled after each setup's turn (`thermalPeak`).
 * - sustain: per setup in each of `rounds` interleaved rounds, a `cooldown` s pause, a 5 s
 *   idle baseline of the battery current, then pages back to back for `seconds`, sampling
 *   every 100 ms (the thermal status every second): pages per second, and energy per page
 *   from the signed drain above idle.
 *   Valid only on battery alone: unplug the cable (adb over Wi-Fi). A charger keeps
 *   supplying power under `dumpsys battery unplug`, so a battery reporting a charger, or
 *   any sample not draining, refuses the estimate.
 *
 * Timings are valid only from a bench build (`assembleBench`: a debuggable build runs
 * Kotlin many times slower) with the app in front (`am start` its list first; otherwise the
 * process sits in a cpuset of little cores only). Every result carries the build's
 * debuggability, the process's cpuset, and "valid" when both hold (`conditions`); a time or
 * sustain result's own "valid" also needs the run to have stayed below thermal status light
 * (1) from before it started to after it ended: warm cores run slower.
 *
 * Results go to the log under "ResampleCheck", one JSON object per line, ending with "done",
 * and to `files/bench/resample.jsonl` in the app's external files. With `trace`, every GPU
 * job also logs what its steps took under "ResampleGpu" ([LibPixels.trace]); off by default,
 * so timings compare with untraced builds.
 */
class ResampleCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = intent?.getStringExtra("do") ?: "all"
        val processors = Runtime.getRuntime().availableProcessors()
        val now = NativeResampler.threads
        val later = NativeResampler.laterThreads
        val asked = intent?.getStringExtra("setups")?.split(',')
        val timed = asked?.mapNotNull { Setup.of(it, now) } ?: listOf(
            Setup(PixelEngine.KOTLIN, processors, Cores.ANY), Setup(PixelEngine.NATIVE, now, Cores.BIG),
            Setup(PixelEngine.NATIVE, now, Cores.ANY), Setup(PixelEngine.NATIVE, later, Cores.LITTLE), Setup(PixelEngine.GPU, 1, Cores.ANY),
            Setup(PixelEngine.GPU, 1, Cores.BIG)
        )
        val sustained = if (asked != null) timed else listOf(
            Setup(PixelEngine.KOTLIN, processors, Cores.ANY), Setup(PixelEngine.NATIVE, now, Cores.BIG),
            Setup(PixelEngine.NATIVE, later, Cores.LITTLE), Setup(PixelEngine.GPU, 1, Cores.ANY), Setup(PixelEngine.GPU, 1, Cores.BIG)
        )
        val filters = intent?.getStringExtra("filters")?.split(',')?.map { ScaleFilter.of(it.trim()) } ?: listOf(ScaleFilter.LANCZOS3)
        val rings = intent?.getStringExtra("rings")?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 100) }
            ?.takeIf { it.isNotEmpty() } ?: listOf(Scaling.DEFAULT_DOWN_RING)
        val reps = intent?.getIntExtra("reps", 10)?.coerceIn(1, 100) ?: 10
        val seconds = intent?.getIntExtra("seconds", 20)?.coerceIn(3, 600) ?: 20
        val rounds = intent?.getIntExtra("rounds", 2)?.coerceIn(1, 20) ?: 2
        val cooldown = intent?.getIntExtra("cooldown", 30)?.coerceIn(0, 600) ?: 30
        val asSized = intent?.getStringExtra("size")
        val screen = Calibrator.screenSize(this)
        val size = ResampleRuns.size(asSized, screen.first, screen.second)
        val trace = intent?.getBooleanExtra("trace", false) ?: false
        thread(name = "resample-check") { Calibrator.hold {
            val keptEngine = NativeResampler.engine
            val keptThreads = NativeResampler.threads
            val keptLater = NativeResampler.laterThreads
            val keptPinned = NativeResampler.pinned
            val keptLittle = NativeResampler.laterOnLittle
            val keptPrime = NativeResampler.onPrime
            LibPixels.trace(trace)
            try {
                out.parentFile?.mkdirs()
                if (asked != null && timed.size != asked.size) log(JSONObject().put("failed", "unreadable setups: $asked"))
                if (size == null) log(JSONObject().put("failed", "unreadable size: $asSized"))
                val cores = CpuCores.device
                log(
                    JSONObject().put("big", java.lang.Long.toBinaryString(cores.big)).put("little", java.lang.Long.toBinaryString(cores.little))
                        .put("threads", now).put("laterThreads", later).put("conditions", conditions())
                )
                if (what == "check" || what == "all") check()
                if ((what == "time" || what == "all") && size != null) time(timed, filters, rings, reps, size)
                if (what == "sustain" || what == "all") sustain(sustained, seconds, rounds, cooldown)
            } catch (t: Throwable) {
                log(JSONObject().put("failed", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                NativeResampler.engine = keptEngine
                NativeResampler.threads = keptThreads
                NativeResampler.laterThreads = keptLater
                NativeResampler.pinned = keptPinned
                NativeResampler.laterOnLittle = keptLittle
                NativeResampler.onPrime = keptPrime
                LibPixels.trace(false)
                Log.i(TAG, "done")
                stopSelfResult(startId)
            }
        } }
        return START_NOT_STICKY
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/resample.jsonl")

    /** Whether timings taken now count: not debuggable, and the process in the foreground app's cpuset. */
    private fun conditions(): JSONObject {
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return JSONObject().put("cpuset", cpuset).put("debuggable", debuggable).put("valid", !debuggable && cpuset == "/top-app")
    }

    // ------------------------------------------------------------------ check

    /** The largest channel difference between native code and the model, over many small cases. */
    private fun check() {
        val cases = ArrayList<Case>()
        for (filter in ScaleFilter.entries) {
            for ((up, down) in listOf(0 to 0, 0 to 50, 100 to 100, 30 to 75)) {
                val s = Scaling(filter, up, down)
                cases += Case(61, 47, Resampler.Window.of(61, 47), 17, 13, s)
                cases += Case(13, 11, Resampler.Window.of(13, 11), 40, 29, s)
                cases += Case(40, 12, Resampler.Window.of(40, 12), 15, 31, s)
                cases += Case(300, 160, Resampler.Window.of(300, 160), 23, 23, s)
                cases += Case(61, 47, Resampler.Window.of(61, 47), 47, 37, s)
                cases += Case(50, 70, Resampler.Window(0.0, 11.25, 50.0, 47.5), 20, 19, s)
                cases += Case(997, 1301, Resampler.Window.of(997, 1301), 461, 600, s)
            }
        }
        // A cover from a large page: many source rows per output row, in several GPU chunks.
        cases += Case(3000, 4000, Resampler.Window.of(3000, 4000), 128, 171, Scaling.DEFAULT)
        for (engine in listOf(PixelEngine.NATIVE, PixelEngine.GPU)) {
            var worst = 0
            var failed = 0
            var off = 0L
            var pixels = 0L
            val digest = CRC32()
            for ((i, c) in cases.withIndex()) {
                for (linear in listOf(false, true)) {
                    for (opaque in listOf(true, false)) {
                        val src = noise(c.srcW, c.srcH, i * 4 + (if (linear) 2 else 0) + (if (opaque) 1 else 0), opaque)
                        val dst = Bitmap.createBitmap(c.dstW, c.dstH, Bitmap.Config.ARGB_8888)
                        try {
                            val held = premultipliedPixels(src)
                            val model = Resampler.resample(held, c.srcW, c.srcH, c.window, c.dstW, c.dstH, linear, c.scaling, premultiplied = true)
                            if (!NativeResampler.resample(src, dst, c.window, linear, c.scaling, engine = engine, fallback = false)) {
                                failed++
                                continue
                            }
                            val got = premultipliedPixels(dst)
                            for (k in got.indices) {
                                val d = channelDiff(got[k], model[k])
                                worst = max(worst, d)
                                if (d > 0) off++
                                digest.update(got[k])
                                digest.update(got[k] ushr 8)
                                digest.update(got[k] ushr 16)
                                digest.update(got[k] ushr 24)
                            }
                            pixels += got.size
                        } finally {
                            src.recycle()
                            dst.recycle()
                        }
                    }
                }
            }
            log(
                JSONObject().put("check", engine.key).put("cases", cases.size * 4).put("failed", failed).put("maxDiff", worst)
                    .put("pixelsOff", off).put("pixels", pixels).put("digest", "%08x".format(digest.value)).put("ok", failed == 0 && worst <= 1)
            )
        }
        checkGpuBand()
        // The shipping Kotlin path reads and writes unpremultiplied: on an opaque page both agree.
        val page = SyntheticPage.draw(2000, 3000)
        val window = Resampler.Window.of(2000, 3000)
        try {
            for (scaling in listOf(Scaling.DEFAULT, Scaling(ScaleFilter.LANCZOS6, 0, 100))) {
                val a = Bitmap.createBitmap(1848, 2772, Bitmap.Config.ARGB_8888)
                var b: Bitmap? = null
                try {
                    val ran = NativeResampler.resample(page, a, window, false, scaling, engine = PixelEngine.NATIVE, fallback = false)
                    b = kotlinResample(page, 1848, 2772, window, scaling)
                    val pa = IntArray(1848 * 2772).also { a.getPixels(it, 0, 1848, 0, 0, 1848, 2772) }
                    val pb = IntArray(1848 * 2772).also { b.getPixels(it, 0, 1848, 0, 0, 1848, 2772) }
                    var worst = 0
                    for (k in pa.indices) worst = max(worst, channelDiff(pa[k], pb[k]))
                    log(
                        JSONObject().put("check", "native vs kotlin, 2000x3000 page").put("scaling", "$scaling").put("native", ran)
                            .put("maxDiff", worst).put("ok", ran && worst <= 1)
                    )
                } finally {
                    a.recycle()
                    b?.recycle()
                }
            }
        } finally {
            page.recycle()
        }
        canceledGpuCheck()
    }

    /**
     * A GPU job canceled before its second chunk: it throws [CancellationException], not
     * failing (which the CPU would take over), or rethrows what canceling threw; the next
     * GPU job still matches the model. `polls` counts the asks: one before the job begins,
     * then one per chunk started.
     */
    private fun canceledGpuCheck() {
        val c = Case(3000, 4000, Resampler.Window.of(3000, 4000), 600, 800, Scaling.DEFAULT)
        val src = noise(c.srcW, c.srcH, 1, true)
        try {
            for (throwing in listOf(false, true)) {
                var polls = 0
                val dst = Bitmap.createBitmap(c.dstW, c.dstH, Bitmap.Config.ARGB_8888)
                val outcome = try {
                    val canceled = {
                        val stop = ++polls >= 3
                        check(!(stop && throwing)) { "canceling failed" }
                        stop
                    }
                    if (NativeResampler.resample(src, dst, c.window, false, c.scaling, canceled, PixelEngine.GPU, fallback = false)) "finished" else "failed"
                } catch (e: CancellationException) {
                    "canceled"
                } catch (e: IllegalStateException) {
                    "rethrown"
                } finally {
                    dst.recycle()
                }
                val expected = if (throwing) "rethrown" else "canceled"
                log(
                    JSONObject().put("check", "gpu canceled before its 2nd chunk").put("throwing", throwing).put("outcome", outcome)
                        .put("polls", polls).put("ok", outcome == expected && polls == 3)
                )
            }
            val model = Resampler.resample(premultipliedPixels(src), c.srcW, c.srcH, c.window, c.dstW, c.dstH, false, c.scaling, premultiplied = true)
            val dst = Bitmap.createBitmap(c.dstW, c.dstH, Bitmap.Config.ARGB_8888)
            try {
                val ran = NativeResampler.resample(src, dst, c.window, false, c.scaling, engine = PixelEngine.GPU, fallback = false)
                val got = premultipliedPixels(dst)
                var worst = 0
                for (k in got.indices) worst = max(worst, channelDiff(got[k], model[k]))
                log(JSONObject().put("check", "gpu after the canceled ones").put("ran", ran).put("maxDiff", worst).put("ok", ran && worst <= 1))
            } finally {
                dst.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    private class Case(val srcW: Int, val srcH: Int, val window: Resampler.Window, val dstW: Int, val dstH: Int, val scaling: Scaling)

    /**
     * [LibPixels.gpuBand] on a cover from a large page (several chunks), against the GPU's
     * [LibPixels.band] in two bands and [NativeResampler]'s GPU resample: with no stop the
     * same pixels; a stop saying so on its second call
     * cancels after exactly two calls; what a stop throws reaches the caller and the next job
     * still runs; then [LibPixels.trim].
     */
    private fun checkGpuBand() {
        val src = noise(3000, 4000, 7, opaque = true)
        val window = Resampler.Window.of(3000, 4000)
        val ax = Resampler.axis(3000, window.x, window.width, 128, Scaling.DEFAULT)
        val ay = Resampler.axis(4000, window.y, window.height, 171, Scaling.DEFAULT)
        val result = JSONObject().put("check", "gpu band")
        val made = ArrayList<Bitmap>()

        // One GPU job filtering all rows through gpuBand; null when it could not open.
        fun gpuBand(stop: PixelKernels.Stop?): Pair<Int, Bitmap>? {
            val dst = Bitmap.createBitmap(128, 171, Bitmap.Config.ARGB_8888).also { made += it }
            val job = LibPixels.begin(src, dst, false, 1, true)
            if (job == 0L) return null
            try {
                if (!LibPixels.axis(job, false, ax) || !LibPixels.axis(job, true, ay)) return null
                return LibPixels.gpuBand(job, 0, 171, 0, stop) to dst
            } finally {
                LibPixels.end(job)
            }
        }

        // The same through LibPixels.band, rows 0 until 85 then the rest; false when it could not.
        fun bands(dst: Bitmap): Boolean {
            val job = LibPixels.begin(src, dst, false, 1, true)
            if (job == 0L) return false
            try {
                return LibPixels.axis(job, false, ax) && LibPixels.axis(job, true, ay) &&
                    LibPixels.band(job, 0, 0, 85, 0) && LibPixels.band(job, 0, 85, 171, 0)
            } finally {
                LibPixels.end(job)
            }
        }

        try {
            val banded = Bitmap.createBitmap(128, 171, Bitmap.Config.ARGB_8888).also { made += it }
            val ran = bands(banded)
            val resampled = Bitmap.createBitmap(128, 171, Bitmap.Config.ARGB_8888).also { made += it }
            val viaResampler = NativeResampler.resample(src, resampled, window, false, Scaling.DEFAULT, engine = PixelEngine.GPU, fallback = false)
            val plain = gpuBand(null)
            val same = ran && viaResampler && plain != null && digest(plain.second) == digest(banded) && digest(resampled) == digest(banded)
            result.put("band", ran).put("resampler", viaResampler).put("done", plain?.first).put("sameAsBand", same)
            var calls = 0
            val canceled = gpuBand { ++calls == 2 }
            result.put("canceled", canceled?.first).put("stopCalls", calls)
            val thrown = IllegalStateException("stop")
            val caught = runCatching { gpuBand { throw thrown } }.exceptionOrNull()
            result.put("rethrown", caught === thrown)
            val after = gpuBand(null)
            val sameAfter = ran && after != null && digest(after.second) == digest(banded)
            result.put("afterThrow", after?.first).put("sameAfterThrow", sameAfter)
            LibPixels.trim()
            result.put("trimmed", true).put(
                "ok", same && plain?.first == PixelKernels.BAND_DONE && canceled?.first == PixelKernels.BAND_CANCELED && calls == 2 &&
                    caught === thrown && after?.first == PixelKernels.BAND_DONE && sameAfter
            )
        } catch (t: Throwable) {
            result.put("failed", "${t.javaClass.simpleName}: ${t.message}").put("ok", false)
        } finally {
            src.recycle()
            made.forEach { it.recycle() }
        }
        log(result)
    }

    /** A CRC-32 of [b]'s pixels as it holds them in memory. */
    private fun digest(b: Bitmap): Long =
        CRC32().apply { premultipliedPixels(b).forEach { p -> for (s in 0..24 step 8) update(p ushr s) } }.value

    // ------------------------------------------------------------------ time

    /** Each setup shrinking the pages to [size], width by height. */
    private fun time(setups: List<Setup>, filters: List<ScaleFilter>, rings: List<Int>, reps: Int, size: Pair<Int, Int>) {
        for ((w, h) in listOf(2000 to 3000, 4000 to 6000)) {
            val page = SyntheticPage.draw(w, h)
            try {
                for (filter in filters) for (ring in rings) {
                    val scaling = Scaling(filter, 0, ring)
                    val before = state()
                    val heat = ResampleRuns.Heat().apply { see(before.getInt("thermal")) }
                    val walls = setups.associateWith { ArrayList<Double>() }
                    val cpus = setups.associateWith { ArrayList<Double>() }
                    for ((round, order) in ResampleRuns.interleaved(setups, 2 + reps).withIndex()) {
                        for (s in order) {
                            val cpu0 = Process.getElapsedCpuTime()
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            run(s, page, size.first, size.second, scaling).recycle()
                            val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                            if (round >= 2) {
                                walls.getValue(s) += wall
                                cpus.getValue(s) += (Process.getElapsedCpuTime() - cpu0).toDouble()
                            }
                            heat.see(thermal())
                        }
                    }
                    val after = state()
                    heat.see(after.getInt("thermal"))
                    for (s in setups) {
                        val conditions = conditions()
                        log(
                            JSONObject().put("time", s.key).put("page", "${w}x$h").put("to", "${size.first}x${size.second}").put("filter", filter.key).put("downRing", ring)
                                .put("wallMs", summary(walls.getValue(s))).put("cpuMs", summary(cpus.getValue(s)))
                                .put("before", before).put("after", after).put("thermalPeak", heat.peak).put("conditions", conditions)
                                .put("valid", conditions.getBoolean("valid") && heat.cool)
                        )
                    }
                }
            } finally {
                page.recycle()
            }
        }
    }

    // ------------------------------------------------------------------ sustain

    /** Each setup making pages back to back, in [rounds] interleaved rounds, each after a [cooldown] s pause and an idle baseline. */
    private fun sustain(setups: List<Setup>, seconds: Int, rounds: Int, cooldown: Int) {
        val page = SyntheticPage.draw(2000, 3000)
        try {
            for ((round, order) in ResampleRuns.interleaved(setups, rounds).withIndex()) {
                for (s in order) {
                    Thread.sleep(cooldown * 1000L)
                    val charged = charging()
                    val heat = ResampleRuns.Heat().apply { see(thermal()) }
                    val idle = sampleCurrent(5_000) {}
                    var pages = 0
                    val t0 = SystemClock.elapsedRealtime()
                    val cpu0 = Process.getElapsedCpuTime()
                    val busy = sampleCurrent(seconds * 1000L, heat) { until ->
                        while (SystemClock.elapsedRealtime() < until) {
                            run(s, page, 1848, 2772, Scaling.DEFAULT).recycle()
                            pages++
                        }
                    }
                    val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    val volts = voltage()
                    val mj = ResampleRuns.millijoulesPerPage(idle, busy, volts, wall, pages, charged || charging())
                    val after = state()
                    heat.see(after.getInt("thermal"))
                    val conditions = conditions()
                    log(
                        JSONObject().put("sustain", s.key).put("round", round).put("seconds", wall).put("pages", pages)
                            .put("pagesPerS", pages / wall).put("cpuMsPerPage", (Process.getElapsedCpuTime() - cpu0).toDouble() / max(pages, 1))
                            .put("idleMa", num(idle.mean)).put("busyMa", num(busy.mean)).put("volts", volts)
                            .put("mJPerPage", mj ?: JSONObject.NULL)
                            .put("refused", if (mj == null) "a charger was connected, or the battery did not drain throughout" else JSONObject.NULL)
                            .put("after", after).put("thermalPeak", heat.peak).put("conditions", conditions)
                            .put("valid", conditions.getBoolean("valid") && heat.cool)
                    )
                }
            }
        } finally {
            page.recycle()
        }
    }

    /**
     * Battery current in mA every 100 ms over [ms] while [work] runs (given its deadline);
     * negative while draining. [heat], when given, sees the thermal status every second.
     */
    private fun sampleCurrent(ms: Long, heat: ResampleRuns.Heat? = null, work: (until: Long) -> Unit): ResampleRuns.Draw {
        val battery = getSystemService(BatteryManager::class.java)
        val samples = ArrayList<Double>()
        val until = SystemClock.elapsedRealtime() + ms
        val sampler = thread(name = "current") {
            var n = 0
            while (SystemClock.elapsedRealtime() < until) {
                if (heat != null && n++ % 10 == 0) heat.see(thermal())
                // Microamperes, as on the reference tablet and most devices.
                battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }
                    ?.let { synchronized(samples) { samples += it / 1000.0 } }
                Thread.sleep(100)
            }
        }
        work(until)
        // The sampler's samples and statuses are seen from here on.
        sampler.join()
        return synchronized(samples) { ResampleRuns.Draw(samples.toList()) }
    }

    /** True while the battery itself reports a charger (charging or full), whatever `dumpsys battery unplug` pretends. */
    private fun charging(): Boolean =
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }

    private fun voltage(): Double {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
    }

    /** The thermal status now; -1 before Android 10. */
    private fun thermal(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(PowerManager::class.java).currentThermalStatus else -1

    private fun state(): JSONObject {
        val power = getSystemService(PowerManager::class.java)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject()
            .put("thermal", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else -1)
            // NaN when asked again within a second, and where unknown.
            .put("headroom", (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(0) else Float.NaN).let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
            .put("plugged", (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("batteryPct", getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            // From the battery itself, past `dumpsys battery unplug` (2 = charging).
            .put("batteryStatus", getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS))
    }

    // ------------------------------------------------------------------ engines and pixels

    /** [src] shrunk to [w] x [h] by [setup]; the engine must not fall back. */
    private fun run(setup: Setup, src: Bitmap, w: Int, h: Int, scaling: Scaling): Bitmap {
        val window = Resampler.Window.of(src.width, src.height)
        if (setup.engine == PixelEngine.KOTLIN) return kotlinResample(src, w, h, window, scaling, setup.threads)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        NativeResampler.threads = setup.threads
        NativeResampler.laterThreads = setup.threads
        NativeResampler.pinned = setup.cores != Cores.ANY
        NativeResampler.laterOnLittle = setup.cores == Cores.LITTLE
        NativeResampler.onPrime = setup.cores == Cores.PRIME
        val urgent = setup.cores != Cores.LITTLE
        try {
            check(NativeResampler.resample(src, dst, window, false, scaling, engine = setup.engine, fallback = false, urgent = { urgent }, pin = NativeResampler.pinned)) {
                "${setup.key} did not run"
            }
        } catch (t: Throwable) {
            dst.recycle()
            throw t
        }
        return dst
    }

    /** What BitmapUtils did before native code: rows through Java arrays, written under a lock. */
    private fun kotlinResample(src: Bitmap, w: Int, h: Int, window: Resampler.Window, scaling: Scaling, threads: Int = Runtime.getRuntime().availableProcessors()): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Resampler.resample(
            src.width, src.height, { y, row -> src.getPixels(row, 0, src.width, 0, y, src.width, 1) }, window, w, h, false,
            { y, rows, pixels -> synchronized(out) { out.setPixels(pixels, 0, w, 0, y, w, rows) } }, scaling, threads = threads
        )
        return out
    }

    private fun noise(w: Int, h: Int, seed: Int, opaque: Boolean): Bitmap {
        val r = Random(seed)
        val pixels = IntArray(w * h) {
            val a = if (opaque) 255 else r.nextInt(256)
            (a shl 24) or r.nextInt(1 shl 24)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    /** [b]'s pixels as it holds them in memory (premultiplied), as ARGB ints. */
    private fun premultipliedPixels(b: Bitmap): IntArray {
        val buf = ByteBuffer.allocate(b.byteCount).order(ByteOrder.LITTLE_ENDIAN)
        b.copyPixelsToBuffer(buf)
        buf.rewind()
        return IntArray(b.width * b.height) {
            // Memory order R G B A, read little-endian as ABGR.
            val p = buf.getInt()
            (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p shr 16) and 0xFF)
        }
    }

    /** [v], or null where JSON has no number for it. */
    private fun num(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    private fun channelDiff(a: Int, b: Int): Int {
        var worst = 0
        for (s in 0..24 step 8) worst = max(worst, abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)))
        return worst
    }

    private fun summary(values: List<Double>): JSONObject {
        val s = values.sorted()
        return JSONObject().put("median", Stats.round1(s[s.size / 2])).put("min", Stats.round1(s.first())).put("max", Stats.round1(s.last()))
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Resample check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking resampling")
            .setOngoing(true)
            .build()
    }

    private fun log(o: JSONObject) {
        Log.i(TAG, o.toString())
        runCatching { out.appendText(o.toString() + "\n") }
    }

    private companion object {
        const val TAG = "ResampleCheck"
        const val CHANNEL = "resample_check"
        const val NOTIFICATION_ID = 7305
    }
}
