package com.infinity.comicreader.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlDecodingSpeed
import com.awxkee.jxlcoder.JxlEffort
import com.infinity.comicreader.compact.LibJxl
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Per-device calibration on the device ([Calibration] chooses): once per app version and
 * system build, while the app is in front but not reading ([Reading]), charging, cool, out
 * of battery saver and otherwise idle, it times resampling and JPEG XL decoding setups on a
 * synthetic page and keeps the winners ([Tuning]) in a file that is not backed up. A timing
 * during which the app decoded anything ([working]) is made again; the app's drawing
 * ([UiCpu]) is left out of CPU times. Until the first calibration ends, the
 * [Tuning.defaults] apply; after an update the previous winners apply until new ones are
 * established. A forced run ([recalibrate]) goes on under battery saver, whose clock caps
 * may skew it: it is kept marked so, and the next automatic run out of saver replaces it.
 * Benchmarks and checks [hold] it off. Never shown to the user.
 */
object Calibrator {
    private const val TAG = "Calibration"

    /** The tuning in effect. */
    @Volatile
    var tuning: Tuning? = null
        private set

    /** The last calibration's timings, fingerprint and time, as kept; null before any. */
    @Volatile
    var kept: JSONObject? = null
        private set

    /** True while a calibration runs. */
    @Volatile
    var running: Boolean = false
        private set

    /** How the last run ended: "ended true", or "ended false: " and why; null before any. */
    @Volatile
    var outcome: String? = null
        private set

    /** Called on the calibrating thread when a run ends, with whether it finished. */
    @Volatile
    var onEnded: ((Boolean) -> Unit)? = null

    /** An activity calibration never runs under: the reader, whose page turns it would slow and skew. */
    interface Reading

    @PublishedApi
    internal val holds = AtomicInteger()

    /** App work in progress ([working]). */
    @PublishedApi
    internal val busy = AtomicInteger()

    /** App work started so far ([working]). */
    @PublishedApi
    internal val worked = AtomicLong()

    private val resumed = AtomicInteger()

    private val reading = AtomicInteger()

    private lateinit var app: Application

    @Volatile
    private var fingerprint: String? = null

    /** The run in progress (0: none); -1 once a forced run drops it. */
    @Volatile
    private var current = 0

    private var runs = 0

    /** A forced run waits for the one it dropped to end. */
    private var again = false

    /**
     * Applies the kept tuning, if any, and calibrates when due as activities come to the
     * front. What is kept is read off the main thread; until then nothing is due.
     */
    fun start(app: Application) {
        this.app = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed.incrementAndGet()
                if (activity is Reading) reading.incrementAndGet() else if (due()) begin(force = false)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed.decrementAndGet()
                if (activity is Reading) reading.decrementAndGet()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        thread(name = "calibration-load", isDaemon = true) {
            kept = runCatching { JSONObject(file(app).readText()) }.getOrNull()
            tuning = Tuning.of(kept?.optJSONObject("tuning"))?.also { it.apply() }
                ?: Tuning.defaults(CpuCores.device, Runtime.getRuntime().availableProcessors())
            fingerprint = Calibration.fingerprint(appVersion(app), Build.FINGERPRINT)
            if (resumed.get() > reading.get() && due()) begin(force = false)
        }
    }

    /** True when this app version and system build have no calibration yet, or only one made under battery saver; false until what is kept is read. */
    fun due(): Boolean = Calibration.due(kept, fingerprint)

    /** Runs [block] as app work no calibration timing may overlap: one it overlaps is made again. */
    inline fun <T> working(block: () -> T): T {
        busy.incrementAndGet()
        worked.incrementAndGet()
        try {
            return block()
        } finally {
            busy.decrementAndGet()
        }
    }

    /** Runs [block] with calibration held off, as benchmarks and checks that set their own setups do. */
    inline fun <T> hold(block: () -> T): T {
        holds.incrementAndGet()
        try {
            return block()
        } finally {
            holds.decrementAndGet()
        }
    }

    /**
     * Calibrates now, charging or not and under battery saver too (debug builds'
     * Recalibrate); still only in front, not reading, cool and idle. A run already waiting or
     * timing is dropped for this one.
     */
    fun recalibrate() = begin(force = true)

    @Synchronized
    private fun begin(force: Boolean) {
        if (!::app.isInitialized) return
        if (running) {
            if (force) {
                current = -1
                again = true
            }
            return
        }
        if (!force && (!charging(app) || powerSave(app))) return
        running = true
        val id = ++runs
        current = id
        val dropped = { current != id }
        thread(name = "calibration", isDaemon = true) {
            var why: String? = "stopped"
            try {
                // Let what brought the app to the front settle first.
                val until = SystemClock.elapsedRealtime() + if (force) FORCED_START_MS else START_MS
                while (!dropped() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(STEP_MS)
                if (!dropped()) why = calibrate(force, dropped)
            } catch (t: Throwable) {
                why = "failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "Calibration failed", t)
            } finally {
                ended(dropped(), why)
            }
        }
    }

    /** A run ended, finished unless [why] says why not: a dropped one without reporting it, then the forced run that dropped it starts. */
    @Synchronized
    private fun ended(dropped: Boolean, why: String?) {
        running = false
        current = 0
        if (dropped) Log.i(TAG, "dropped for a new run") else {
            val line = if (why == null) "ended true" else "ended false: $why"
            outcome = line
            Log.i(TAG, line)
            onEnded?.invoke(why == null)
        }
        if (again) {
            again = false
            begin(force = true)
        }
    }

    /** One calibration: null when it finished and its winners apply, else why not. */
    private fun calibrate(force: Boolean, dropped: () -> Boolean): String? {
        val probe = DeviceProbe(app, force, dropped)
        try {
            // Nothing is drawn or encoded before the app is first found idle.
            if (!probe.awaitFair()) return probe.why ?: "not fair"
            probe.prepare()
            val candidates = Calibration.candidates(
                CpuCores.device, Runtime.getRuntime().availableProcessors(), gpu = true, jxl = probe.prepareJxl()
            )
            val t0 = SystemClock.elapsedRealtime()
            val run = CalibrationRun(probe, candidates)
            val timings = run.run(dropped) ?: return run.why ?: "stopped"
            if (timings.none { it.candidate.kind == Candidate.Kind.PIXELS }) return "no resampling setup ran"
            val f = fingerprint ?: return "not ready"
            val before = tuning ?: Tuning.defaults(CpuCores.device, Runtime.getRuntime().availableProcessors())
            val chosen = Calibration.tuning(timings, before)
            chosen.apply()
            tuning = chosen
            val record = JSONObject().put("fingerprint", f).put("at", System.currentTimeMillis())
                .put("tookMs", SystemClock.elapsedRealtime() - t0).put("tuning", chosen.toJson())
                .put("timings", Calibration.timingsJson(timings)).put("forced", force).put("powerSave", probe.sawPowerSave)
                .put("page", probe.page).put("target", probe.target)
            kept = record
            val out = file(app)
            val tmp = File(out.path + ".tmp")
            tmp.writeText(record.toString())
            if (!tmp.renameTo(out)) tmp.delete()
            Log.i(TAG, record.toString())
            return null
        } finally {
            probe.release()
        }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, "calibration.json")

    /** The installed app's version code and install time: a new one after every update. */
    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "$code@${info.lastUpdateTime}"
    }.getOrDefault("?")

    private fun charging(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun powerSave(context: Context): Boolean = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    /** Times setups on this device, on a 2000 x 3000 synthetic page shrunk to the screen's portrait size. */
    private class DeviceProbe(private val context: Context, private val force: Boolean, private val dropped: () -> Boolean) : CalibrationProbe {
        private var src: Bitmap? = null
        private var dst: Bitmap? = null
        private var jxl: ByteArray? = null
        private var jxlOut: Bitmap? = null
        private val ui = UiCpu.self().also { it.scan() }

        /** App work started before the sample in progress. */
        private var workedBefore = 0L

        private var reason: String? = null

        override val why: String? get() = reason

        /** True once battery saver was seen on during the run (only a forced run goes on under it). */
        var sawPowerSave = false
            private set

        /** CPU time in ms of all but the UI threads ([UiCpu]). */
        private fun cpuMs(): Double = Process.getElapsedCpuTime() - ui.ms()

        /** Draws the page and makes its target, the page shrunk to fit the screen held upright ([Calibration.target]). */
        fun prepare() {
            val m = context.resources.displayMetrics
            val (w, h) = Calibration.target(m.widthPixels, m.heightPixels, PAGE_W, PAGE_H)
            src = SyntheticPage.draw(PAGE_W, PAGE_H)
            dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

        /** The page and its target, as `WxH`. */
        val page: String get() = "${PAGE_W}x$PAGE_H"
        val target: String get() = dst?.let { "${it.width}x${it.height}" } ?: "?"

        /** Makes the JPEG XL page (quality 91, once, kept in the cache); false where native decodes are missing. */
        fun prepareJxl(): Boolean {
            if (JxlPages.kernel == null) return false
            val f = File(context.cacheDir, "calibration/page-${PAGE_W}x$PAGE_H-q91.jxl")
            jxl = runCatching {
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    val bytes = JxlCoder.encode(src ?: return false, JxlChannelsConfiguration.RGB, JxlCompressionOption.LOSSY, JxlEffort.HARE, 91, JxlDecodingSpeed.SLOWEST)
                    File(f.path + ".tmp").apply { writeBytes(bytes) }.renameTo(f)
                }
                f.readBytes()
            }.getOrNull() ?: return false
            jxlOut = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
            return true
        }

        override fun time(candidate: Candidate): Pair<Double, Double>? {
            val cpu0 = cpuMs()
            val t0 = System.nanoTime()
            val ok = when (candidate.kind) {
                Candidate.Kind.PIXELS -> NativeResampler.resample(
                    src ?: return null, dst ?: return null, Resampler.Window.of(PAGE_W, PAGE_H), false, Scaling.DEFAULT,
                    engine = if (candidate.gpu) PixelEngine.GPU else PixelEngine.NATIVE, fallback = false, urgent = { true },
                    workers = candidate.threads, pin = candidate.pinned
                )
                Candidate.Kind.JXL -> {
                    val bytes = jxl ?: return null
                    val out = jxlOut ?: return null
                    val mask = if (candidate.pinned) CpuCores.device.big else 0L
                    val r = LibJxl.page(bytes, out, candidate.threads, mask)
                    r == LibJxl.PAGE_OPAQUE || r == LibJxl.PAGE_ALPHA
                }
            }
            if (!ok) return null
            return (System.nanoTime() - t0) / 1e6 to (cpuMs() - cpu0).coerceAtLeast(0.0)
        }

        /** Waits up to [GIVE_UP_MS] for a fair moment; says [why] when there is none. */
        override fun awaitFair(): Boolean {
            val since = SystemClock.elapsedRealtime()
            while (true) {
                if (dropped()) return no("dropped")
                Calibration.stopReason(held = holds.get() > 0, reading = reading.get() > 0, inFront = resumed.get() > 0, forced = force, charging = { charging(context) })
                    ?.let { return no(it) }
                val wait = hindrance() ?: if (idle()) null else "app not idle"
                if (wait == null) {
                    workedBefore = worked.get()
                    return true
                }
                if (SystemClock.elapsedRealtime() - since > GIVE_UP_MS) return no("waited ${GIVE_UP_MS / 1000} s: $wait")
                SystemClock.sleep(WAIT_MS)
            }
        }

        private fun no(why: String): Boolean {
            reason = why
            return false
        }

        /** Nothing held, left, read or decoded since the sample began, and nothing to wait out. */
        override fun stillFair(): Boolean =
            holds.get() == 0 && inFront() && worked.get() == workedBefore && hindrance() == null

        /** In front and not reading. */
        private fun inFront() = resumed.get() > 0 && reading.get() == 0

        /** What timing now would be skewed by ([Calibration.waitReason]); null when nothing. */
        private fun hindrance(): String? {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val saver = pm?.isPowerSaveMode == true
            if (saver) sawPowerSave = true
            val thermal = if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
            return Calibration.waitReason(busy = busy.get() > 0, thermalStatus = thermal, powerSave = saver, forced = force)
        }

        /** True when all but the UI threads used under a tenth of a core over a short while: nothing else of the app is busy. */
        private fun idle(): Boolean {
            val cpu0 = cpuMs()
            SystemClock.sleep(IDLE_WINDOW_MS)
            return cpuMs() - cpu0 < IDLE_WINDOW_MS / 10
        }

        fun release() {
            src?.recycle()
            dst?.recycle()
            jxlOut?.recycle()
        }
    }

    private const val PAGE_W = 2000
    private const val PAGE_H = 3000
    private const val START_MS = 10_000L
    private const val FORCED_START_MS = 1_000L
    private const val STEP_MS = 250L
    private const val WAIT_MS = 1_000L
    private const val IDLE_WINDOW_MS = 300L
    private const val GIVE_UP_MS = 120_000L
}
