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
import kotlin.concurrent.thread

/**
 * Per-device calibration on the device ([Calibration] chooses): once per app version and
 * system build, while the app is in front, charging, cool and otherwise idle, it times
 * resampling and JPEG XL decoding setups on a synthetic page and keeps the winners
 * ([Tuning]) in a file that is not backed up. Until the first calibration ends, the
 * [Tuning.defaults] apply; after an update the previous winners apply until new ones are
 * established. Benchmarks and checks [hold] it off. Never shown to the user.
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

    /** Called on the calibrating thread when a run ends, with whether it finished. */
    @Volatile
    var onEnded: ((Boolean) -> Unit)? = null

    @PublishedApi
    internal val holds = AtomicInteger()

    private val resumed = AtomicInteger()

    private lateinit var app: Application

    private var fingerprint = ""

    /** Applies the kept tuning, if any, and calibrates when due as activities come to the front. */
    fun start(app: Application) {
        this.app = app
        fingerprint = Calibration.fingerprint(appVersion(app), Build.FINGERPRINT)
        kept = runCatching { JSONObject(file(app).readText()) }.getOrNull()
        tuning = Tuning.of(kept?.optJSONObject("tuning"))?.also { it.apply() }
            ?: Tuning.defaults(CpuCores.device, Runtime.getRuntime().availableProcessors())
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed.incrementAndGet()
                if (due()) begin(force = false)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed.decrementAndGet()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** True when this app version and system build have no calibration yet. */
    fun due(): Boolean = kept?.optString("fingerprint") != fingerprint

    /** Runs [block] with calibration held off, as benchmarks and checks that set their own setups do. */
    inline fun <T> hold(block: () -> T): T {
        holds.incrementAndGet()
        try {
            return block()
        } finally {
            holds.decrementAndGet()
        }
    }

    /** Calibrates now, charging or not (debug builds' Recalibrate); still only in front and idle. */
    fun recalibrate() = begin(force = true)

    @Synchronized
    private fun begin(force: Boolean) {
        if (running || !::app.isInitialized) return
        if (!force && !charging(app)) return
        running = true
        thread(name = "calibration", isDaemon = true) {
            var done = false
            try {
                // Let what brought the app to the front settle first.
                SystemClock.sleep(if (force) FORCED_START_MS else START_MS)
                done = calibrate(force)
            } catch (t: Throwable) {
                Log.w(TAG, "Calibration failed", t)
            } finally {
                running = false
                Log.i(TAG, "ended $done")
                onEnded?.invoke(done)
            }
        }
    }

    /** One calibration; true when it finished and its winners apply. */
    private fun calibrate(force: Boolean): Boolean {
        val probe = DeviceProbe(app, force)
        try {
            // Nothing is drawn or encoded before the app is first found idle.
            if (!probe.awaitFair()) return false
            probe.prepare()
            val candidates = Calibration.candidates(
                CpuCores.device, Runtime.getRuntime().availableProcessors(), gpu = true, jxl = probe.prepareJxl()
            )
            val t0 = SystemClock.elapsedRealtime()
            val timings = CalibrationRun(probe, candidates).run() ?: return false
            if (timings.none { it.candidate.kind == Candidate.Kind.PIXELS }) return false
            val before = tuning ?: Tuning.defaults(CpuCores.device, Runtime.getRuntime().availableProcessors())
            val chosen = Calibration.tuning(timings, before)
            chosen.apply()
            tuning = chosen
            val record = JSONObject().put("fingerprint", fingerprint).put("at", System.currentTimeMillis())
                .put("tookMs", SystemClock.elapsedRealtime() - t0).put("tuning", chosen.toJson())
                .put("timings", Calibration.timingsJson(timings)).put("forced", force)
            kept = record
            val f = file(app)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(record.toString())
            if (!tmp.renameTo(f)) tmp.delete()
            Log.i(TAG, record.toString())
            return true
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

    /** Times setups on this device, on a 2000 x 3000 synthetic page shrunk to the screen's portrait size. */
    private class DeviceProbe(private val context: Context, private val force: Boolean) : CalibrationProbe {
        private var src: Bitmap? = null
        private var dst: Bitmap? = null
        private var jxl: ByteArray? = null
        private var jxlOut: Bitmap? = null
        private val started = SystemClock.elapsedRealtime()

        /** Draws the page and makes its target, the page shrunk to fit the screen held upright. */
        fun prepare() {
            val m = context.resources.displayMetrics
            val shortSide = minOf(m.widthPixels, m.heightPixels).coerceAtLeast(1)
            val longSide = maxOf(m.widthPixels, m.heightPixels).coerceAtLeast(1)
            val scale = minOf(1.0, shortSide.toDouble() / PAGE_W, longSide.toDouble() / PAGE_H)
            src = SyntheticPage.draw(PAGE_W, PAGE_H)
            dst = Bitmap.createBitmap((PAGE_W * scale).toInt().coerceAtLeast(1), (PAGE_H * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

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
            val cpu0 = Process.getElapsedCpuTime()
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
            return (System.nanoTime() - t0) / 1e6 to (Process.getElapsedCpuTime() - cpu0).toDouble()
        }

        override fun awaitFair(): Boolean {
            while (true) {
                if (holds.get() > 0 || resumed.get() <= 0) return false
                if (!force && !charging(context)) return false
                if (SystemClock.elapsedRealtime() - started > GIVE_UP_MS) return false
                if (cool() && idle()) return true
                SystemClock.sleep(WAIT_MS)
            }
        }

        override fun stillFair(): Boolean = holds.get() == 0 && resumed.get() > 0 && cool()

        /** True when the process used under a tenth of a core over a short while: nothing else of the app is busy. */
        private fun idle(): Boolean {
            val cpu0 = Process.getElapsedCpuTime()
            SystemClock.sleep(IDLE_WINDOW_MS)
            return Process.getElapsedCpuTime() - cpu0 < IDLE_WINDOW_MS / 10
        }

        private fun cool(): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            if (pm.isPowerSaveMode) return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pm.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
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
    private const val WAIT_MS = 1_000L
    private const val IDLE_WINDOW_MS = 300L
    private const val GIVE_UP_MS = 120_000L
}
