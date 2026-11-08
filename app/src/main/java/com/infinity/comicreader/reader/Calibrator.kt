package com.infinity.comicreader.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlDecodingSpeed
import com.awxkee.jxlcoder.JxlEffort
import com.infinity.comicreader.compact.LibJxl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Per-device calibration on the device ([Calibration] chooses): once per system build and
 * calibration version ([Calibration.VERSION]), while the app is in front but not reading
 * ([Reading]), charging, cool, out of battery saver and otherwise idle, it times
 * resampling, JPEG XL decoding and platform JPEG decoding setups on synthetic pages and
 * keeps the winners ([Tuning]) in a file that is not backed up. Each sample follows a short
 * rest ([Calibration.REST_MS]), as page decodes at reading pace do. It starts once the app is
 * idle (waiting [Calibration.SETTLE_MS] at most before it waits as for any sample), then draws
 * and encodes its pages, off the timed path. A timing during which the app decoded anything ([working]) or another
 * of its threads used the CPU ([Calibration.othersBusy]) is made again; the app's drawing
 * ([UiCpu]) is left out of CPU times. Heat is waited out before timing starts and, where the
 * thermal status shows it, ends a run that heats up (battery temperature, the fallback, is
 * too slow to: [Calibration.heatStatus]). Until the first calibration ends, the [Tuning.defaults] apply; after a
 * system update the previous winners apply until new ones are established. A forced run
 * ([recalibrate]) goes on under battery saver, whose clock caps may skew it: it is kept
 * marked so, and the next automatic run out of saver replaces it. Benchmarks and checks
 * [hold] it off. Never shown to the user.
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

    /** True while one of the app's screens is in front. */
    val inFront: Boolean get() = resumed.get() > 0

    private val reading = AtomicInteger()

    private lateinit var app: Application

    @Volatile
    private var fingerprint: String? = null

    /** What the encoded pages are kept for ([Calibration.pageFingerprint]). */
    @Volatile
    private var pageFingerprint: String? = null

    /** The run in progress (0: none); -1 once a forced run drops it. */
    @Volatile
    private var current = 0

    private var runs = 0

    /** A forced run waits for the one it dropped to end. */
    private var again = false

    /**
     * Applies the kept tuning, or else the [Tuning.defaults], and calibrates when due as
     * activities come to the front. What is kept is read off the main thread; until then nothing is due.
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
            tuning = (Tuning.of(kept?.optJSONObject("tuning")) ?: Tuning.defaults(CpuCores.device, Runtime.getRuntime().availableProcessors()))
                .also { it.apply() }
            pageFingerprint = Calibration.pageFingerprint(appVersion(app), Build.FINGERPRINT)
            fingerprint = Calibration.fingerprint(Calibration.VERSION, Build.FINGERPRINT)
            if (resumed.get() > reading.get() && due()) begin(force = false)
        }
    }

    /** True when this system build and calibration version have no calibration yet, or only one made under battery saver; false until what is kept is read. */
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
    fun recalibrate() = recalibrate(BURST, warm = true)

    /**
     * [recalibrate] with each sample the median of [burst] runs, after an untimed one when
     * [warm], only in the first round when [warmOnce], and ending once the winners stay the
     * same when [early] ([CalibrationRun]), [gapMs] of rest before each sample
     * ([Calibration.REST_MS]): debug builds compare run shapes with it. Kept with the result.
     */
    fun recalibrate(burst: Int, warm: Boolean, warmOnce: Boolean = false, early: Boolean = false, gapMs: Long = Calibration.REST_MS) {
        shape = Shape(burst.coerceIn(1, 9), warm, warmOnce, early, gapMs.coerceIn(0, 5_000))
        begin(force = true)
    }

    /** How a run samples ([CalibrationRun], [recalibrate]); automatic runs take the default. */
    private data class Shape(
        val burst: Int = BURST,
        val warm: Boolean = true,
        val warmOnce: Boolean = false,
        val early: Boolean = false,
        val gapMs: Long = Calibration.REST_MS
    )

    /** How forced runs sample. */
    @Volatile
    private var shape = Shape()

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
        val f = fingerprint ?: return "not ready"
        val pages = pageFingerprint ?: return "not ready"
        val started = SystemClock.elapsedRealtime()
        val s = if (force) shape else Shape()
        val probe = DeviceProbe(app, force, dropped, pages, s.gapMs)
        try {
            // Nothing is drawn before the app is found idle.
            if (!probe.awaitFair()) return probe.why ?: "not fair"
            val settleMs = SystemClock.elapsedRealtime() - started
            probe.prepareAhead()
            if (!probe.awaitPages()) return probe.why ?: "stopped"
            val cores = CpuCores.device
            // Platform JPEG is timed only where there are large cores to pin it to.
            val candidates = Calibration.candidates(
                cores, Runtime.getRuntime().availableProcessors(), gpu = true, jxl = probe.hasJxl, jpeg = cores.big != 0L && probe.hasJpeg
            )
            val before = tuning ?: Tuning.defaults(CpuCores.device, Runtime.getRuntime().availableProcessors())
            val wasChosen = Calibration.chosen(kept, before)
            val run = CalibrationRun(
                probe, candidates, burst = s.burst, warm = s.warm, warmOnce = s.warmOnce,
                winners = if (s.early) { t -> Calibration.tuning(t, before, chosen = wasChosen) } else null
            )
            val t0 = SystemClock.elapsedRealtime()
            probe.timing()
            val timings = run.run(dropped) ?: return run.why ?: "stopped"
            val tookMs = SystemClock.elapsedRealtime() - t0
            if (timings.none { it.candidate.kind == Candidate.Kind.PIXELS }) return "no resampling setup ran"
            val chosen = Calibration.tuning(timings, before, chosen = wasChosen)
            chosen.apply()
            tuning = chosen
            val record = JSONObject().put("fingerprint", f).put("at", System.currentTimeMillis())
                .put("tookMs", tookMs).put("totalMs", SystemClock.elapsedRealtime() - started).put("settleMs", settleMs).put("prepMs", probe.prepMs)
                .put("tuning", chosen.toJson())
                .put("timings", Calibration.timingsJson(timings)).put("forced", force).put("powerSave", run.sawPowerSave)
                .put("thermal", probe.thermal).put("heat", probe.heatSource).put("batteryTemp", probe.batteryTemp / 10.0)
                .put("page", probe.page).put("target", probe.target).put("jxlPage", probe.decodePage).put("jpegPage", probe.decodePage)
                .put("burst", s.burst).put("warm", s.warm).put("warmOnce", s.warmOnce).put("early", s.early).put("gapMs", s.gapMs)
                .put("rounds", run.roundsRun).put("redone", run.redone).put("disturbedBy", JSONObject(probe.disturbedBy as Map<*, *>))
                .put("cappedSamples", probe.cappedSamples).put("capsKHz", probe.caps?.let { JSONArray(it.toList()) })
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

    /** The installed app's version code and install time: a new one after every update, for the pages it draws and encodes ([Calibration.pageFingerprint]). */
    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "$code@${info.lastUpdateTime}"
    }.getOrDefault("?")

    private fun charging(context: Context): Boolean = battery(context)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

    /** The battery's last broadcast state; null when there is none. */
    private fun battery(context: Context): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** The whole screen's size in pixels, system bars included: what the reader's pages fill. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size.x to size.y
    }

    private fun powerSave(context: Context): Boolean = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    /**
     * Times setups on this device: resampling a 2000 x 3000 synthetic page to the screen's
     * portrait size, JPEG XL and platform JPEG decodes of a 4000 x 6000 one, 4K class as image
     * decode timings use. Encoded pages are kept in the cache for [pageFingerprint] (this
     * install and system build, whose drawing and encoders make them).
     */
    private class DeviceProbe(
        private val context: Context,
        private val force: Boolean,
        private val dropped: () -> Boolean,
        private val pageFingerprint: String,
        private val gapMs: Long = Calibration.REST_MS
    ) : CalibrationProbe {
        @Volatile private var src: Bitmap? = null
        @Volatile private var dst: Bitmap? = null
        @Volatile private var jxl: ByteArray? = null
        @Volatile private var jxlOut: Bitmap? = null
        @Volatile private var jpeg: ByteArray? = null
        private val ui = UiCpu.self().also { it.scan() }
        private val tasks = TaskCpu.self()
        private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        private val cores = CpuCores.device
        private val coreCount = maxOf(cores.capacities.size, Runtime.getRuntime().availableProcessors())

        /** Calibration's own threads: this one, the one preparing pages, and those born during GPU runs ([Calibration.bornDuringGpuRun]). */
        private val own = mutableSetOf(Process.myTid())

        private var preparing: Thread? = null

        /** App work started before the sample in progress. */
        private var workedBefore = 0L

        /** Every thread's CPU time when the sample in progress began, the process's (ms), and when that was. */
        private var tasksBefore: Map<Int, Long> = emptyMap()
        private var processBefore = 0L
        private var sampleStartNs = 0L

        private var reason: String? = null

        override val why: String? get() = reason

        override val powerSave: Boolean get() = saver

        /** The highest thermal status seen during the run, or the one battery temperature stood for ([Calibration.heatStatus]). */
        var thermal = 0
            private set

        /** The highest battery temperature the battery broadcasts reported during the run (possibly older), in tenths of a degree C. */
        var batteryTemp = 0
            private set

        /** Time spent preparing pages ([prepareAhead]). */
        @Volatile
        var prepMs = 0L
            private set

        /** How often each of the app's other threads disturbed a sample most, by name. */
        val disturbedBy = HashMap<String, Int>()

        private val clockCaps = ClockCaps.device()

        /** The highest clock caps seen during the run, in kHz by frequency domain ([Calibration.raised]); null where they cannot be read. */
        var caps: IntArray? = null
            private set

        /** True once a clock cap fell during the sample in progress ([Calibration.capped]). */
        private var cappedNow = false

        /** Samples during which a clock cap fell. */
        var cappedSamples = 0
            private set

        /** Time spent waiting for fair moments so far. */
        private var waitedMs = 0L

        /** True once a run of a setup began: heat then ends the run ([Calibration.giveUp]). */
        private var timing = false

        /** True once the run waited for heat: it waits for light throttling to end too. */
        private var cooling = false

        /** When the app is checked for idle before a sample; samples check the app's other threads during each ([stillFair]). */
        private val idleChecks = IdleChecks()

        // The device's state as last read ([refresh]).
        private var checkedAt: Long? = null
        private var charging = false
        private var saver = false
        private var status = 0

        /**
         * Whether the device's thermal status is real: true where it reports thermal headroom
         * (a thermal HAL answers), false where there is no thermal status (before Android 10),
         * null where it cannot be told.
         */
        private val thermalKnown: Boolean? by lazy {
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm?.getThermalHeadroom(0)?.isNaN() == false -> true
                else -> null
            }
        }

        /** What the heat signal is: "thermal", "battery", or "both" ([Calibration.heatStatus]). */
        val heatSource: String get() = when (thermalKnown) { true -> "thermal"; false -> "battery"; null -> "both" }

        /** CPU time in ms of all but the UI threads ([UiCpu]). */
        private fun cpuMs(): Double = Process.getElapsedCpuTime() - ui.ms()

        /** Starts drawing and encoding the pages on a thread of their own, at background priority. */
        fun prepareAhead() {
            val started = SystemClock.elapsedRealtime()
            preparing = thread(name = "calibration-pages", isDaemon = true) {
                synchronized(own) { own += Process.myTid() }
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    prepare()
                } catch (t: Throwable) {
                    Log.w(TAG, "Calibration pages failed", t)
                } finally {
                    prepMs = SystemClock.elapsedRealtime() - started
                    // A run that stopped without waiting for the pages left them to this thread.
                    if (released) recycle()
                }
            }
        }

        /** Waits for the pages ([prepareAhead]); false, saying [why], when the run must stop meanwhile ([stopNow]). */
        fun awaitPages(): Boolean {
            val t = preparing
            while (t != null && t.isAlive) {
                stopNow()?.let { return no(it) }
                t.join(STEP_MS)
            }
            return stopNow()?.let { no(it) } ?: true
        }

        /** Why the run must stop now, or null: dropped, or [Calibration.stopReason] but for charging, read where it is waited for. */
        private fun stopNow(): String? = if (dropped()) "dropped" else
            Calibration.stopReason(held = holds.get() > 0, reading = reading.get() > 0, inFront = resumed.get() > 0, forced = true) { true }

        /** True when the JPEG XL page was made and native decodes are there. */
        val hasJxl: Boolean get() = jxl != null && jxlOut != null

        /** True when the JPEG page was made. */
        val hasJpeg: Boolean get() = jpeg != null

        /** Timing starts: the core ledger opens ([LibPixels.ledger]). */
        fun timing() {
            if (LibPixels.loaded) LibPixels.ledger(true)
        }

        /**
         * Draws the resampling page and makes its target, the page shrunk to fit the whole
         * screen held upright ([Calibration.target]), as the reader shows it; makes the 4K
         * JPEG XL page (quality 91) and JPEG page (quality 90, as most scans) from one drawing,
         * each once per [pageFingerprint]; deletes pages kept from earlier setups. Stops
         * between steps once the run must stop ([stopNow]).
         */
        private fun prepare() {
            val (sw, sh) = screenSize(context)
            val (w, h) = Calibration.target(sw, sh, PAGE_W, PAGE_H)
            src = SyntheticPage.draw(PAGE_W, PAGE_H)
            dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var drawn: Bitmap? = null
            fun big() = drawn ?: SyntheticPage.draw(DECODE_W, DECODE_H).also { drawn = it }
            try {
                if (stopNow() != null) return
                if (JxlPages.kernel != null) {
                    jxl = Calibration.cachedPage(cache, JXL_NAME, "jxl", pageFingerprint) {
                        JxlCoder.encode(big(), JxlChannelsConfiguration.RGB, JxlCompressionOption.LOSSY, JxlEffort.HARE, 91, JxlDecodingSpeed.SLOWEST)
                    }
                    if (jxl != null) jxlOut = Bitmap.createBitmap(DECODE_W, DECODE_H, Bitmap.Config.ARGB_8888)
                }
                if (stopNow() != null) return
                jpeg = Calibration.cachedPage(cache, JPEG_NAME, "jpg", pageFingerprint) {
                    ByteArrayOutputStream().also { big().compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                }
            } finally {
                drawn?.recycle()
            }
            cache.listFiles()?.filter { f -> !f.name.startsWith(JXL_NAME) && !f.name.startsWith(JPEG_NAME) }?.forEach { it.delete() }
        }

        /** The resampling page, its target, and the decode pages, as `WxH`. */
        val page: String get() = "${PAGE_W}x$PAGE_H"
        val target: String get() = dst?.let { "${it.width}x${it.height}" } ?: "?"
        val decodePage: String get() = "${DECODE_W}x$DECODE_H"

        private val cache: File get() = File(context.cacheDir, "calibration")

        /** [bytes] decoded by the platform as the reader decodes its pages, then dropped; false when it fails. */
        private fun decodeJpeg(bytes: ByteArray): Boolean {
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } ?: return false
            b.recycle()
            return true
        }

        override fun time(candidate: Candidate): RunTime? {
            timing = true
            // What the ledger counted between runs is not this run's.
            if (LibPixels.loaded) LibPixels.ledgerNs(coreCount)
            val threadsBefore = if (candidate.gpu) tasks.tids() else null
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
                    val mask = if (candidate.pinned) cores.big else 0L
                    val r = LibJxl.page(bytes, out, candidate.threads, mask)
                    r == LibJxl.PAGE_OPAQUE || r == LibJxl.PAGE_ALPHA
                }
                Candidate.Kind.JPEG -> {
                    val bytes = jpeg ?: return null
                    PlatformPages.placed(candidate.pinned) { runCatching { decodeJpeg(bytes) }.getOrDefault(false) }
                }
            }
            val wall = (System.nanoTime() - t0) / 1e6
            val cpu = (cpuMs() - cpu0).coerceAtLeast(0.0)
            threadsBefore?.let { before -> Calibration.bornDuringGpuRun(before, tasks.tids()).let { born -> synchronized(own) { own += born } } }
            if (!ok) return null
            val coreNs = if (LibPixels.loaded) LibPixels.ledgerNs(coreCount) else LongArray(0)
            val now = clockCaps.read()
            if (Calibration.capped(caps, now)) cappedNow = true
            caps = Calibration.raised(caps, now)
            return RunTime(wall, cpu, Calibration.weighted(cpu, coreNs, cores.capacities))
        }

        /** Waits for a fair moment while [Calibration.giveUp] allows; says [why] when there is none. */
        override fun awaitFair(): Boolean {
            while (true) {
                if (dropped()) return no("dropped")
                refresh()
                Calibration.stopReason(held = holds.get() > 0, reading = reading.get() > 0, inFront = resumed.get() > 0, forced = force, charging = { charging })
                    ?.let { return no(it) }
                val wait = hindrance() ?: if (settle()) null else "app not idle"
                if (wait == null) {
                    if (gapMs > 0) SystemClock.sleep(gapMs)
                    begin()
                    return true
                }
                val hot = wait.startsWith(Calibration.TOO_WARM)
                Calibration.giveUp(wait, hot, timing, waitedMs)?.let { return no(it) }
                if (hot) cooling = true
                val since = SystemClock.elapsedRealtime()
                SystemClock.sleep(WAIT_MS)
                waitedMs += SystemClock.elapsedRealtime() - since
            }
        }

        /** A sample begins: what it is checked against ([stillFair]) is taken now. */
        private fun begin() {
            cappedNow = false
            workedBefore = worked.get()
            if (LibPixels.loaded) LibPixels.ledgerThreads()
            tasksBefore = tasks.snapshot()
            processBefore = Process.getElapsedCpuTime()
            sampleStartNs = System.nanoTime()
        }

        private fun no(why: String): Boolean {
            reason = why
            return false
        }

        /** Nothing held, left, read or decoded since the sample began, nothing to wait out, and the app's other threads quiet. */
        override fun stillFair(): Boolean {
            if (cappedNow) cappedSamples++
            refresh()
            val fair = holds.get() == 0 && inFront() && worked.get() == workedBefore && hindrance() == null && othersQuiet()
            if (!fair) idleChecks.disturbed()
            return fair
        }

        /**
         * True when the app's threads, but calibration's own, the UI's and the runtime's
         * housekeeping ([Calibration.skips]), used no more CPU time beyond what the core ledger
         * counted as calibration's work ([LibPixels.ledgerThreads]), threads that ended meanwhile
         * included ([Calibration.goneMs]; not where times come in clock ticks, too coarse to
         * tell), than [Calibration.othersBusy] allows since the sample began; else the busiest
         * is noted ([disturbedBy]).
         */
        private fun othersQuiet(): Boolean {
            val processMs = (Process.getElapsedCpuTime() - processBefore).toDouble()
            val after = tasks.snapshot()
            val wallMs = (System.nanoTime() - sampleStartNs) / 1e6
            val counted = if (LibPixels.loaded) LibPixels.ledgerThreads() else emptyMap()
            val skip = Calibration.skips(synchronized(own) { HashSet(own) }, ui.tids, tasks::name)
            val others = Calibration.othersMs(tasksBefore, after, counted, skip)
            val gone = if (tasks.coarse) 0.0 else Calibration.goneMs(processMs, tasksBefore, after, counted)
            if (!Calibration.othersBusy(others + gone, wallMs, tasks.coarse)) return true
            fun gained(tid: Int) = after.getValue(tid) - (tasksBefore[tid] ?: 0L) - (counted[tid] ?: 0L)
            val busiest = after.keys.filter { !skip(it) }.maxByOrNull(::gained)
            val name = if (busiest == null || gone * 1e6 > gained(busiest)) GONE else tasks.name(busiest).ifEmpty { "?" }
            disturbedBy[name] = (disturbedBy[name] ?: 0) + 1
            return false
        }

        /** In front and not reading. */
        private fun inFront() = resumed.get() > 0 && reading.get() == 0

        /** Reads charging, battery saver and heat again when due ([Calibration.refreshDue]). */
        private fun refresh() {
            val now = SystemClock.elapsedRealtime()
            if (!Calibration.refreshDue(checkedAt, now)) return
            checkedAt = now
            val b = battery(context)
            charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val tenths = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            tenths?.let { batteryTemp = maxOf(batteryTemp, it) }
            saver = pm?.isPowerSaveMode == true
            val reported = if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else null
            status = Calibration.heatStatus(reported, thermalKnown, tenths)
            thermal = maxOf(thermal, status)
        }

        /** What timing now would be skewed by ([Calibration.waitReason]); null when nothing. */
        private fun hindrance(): String? =
            Calibration.waitReason(busy = busy.get() > 0, thermalStatus = status, powerSave = saver, forced = force, cooling = cooling)

        /** True when no idle check is due ([IdleChecks]) or the app is found idle ([idle]) within the time the check may take. */
        private fun settle(): Boolean {
            val tryMs = idleChecks.due() ?: return true
            val until = SystemClock.elapsedRealtime() + tryMs
            var idle: Boolean
            do {
                idle = idle()
            } while (!idle && !dropped() && SystemClock.elapsedRealtime() < until)
            idleChecks.checked(idle)
            return idle
        }

        /** True when the app's threads but calibration's own and the UI's used under a tenth of a core over a short while: nothing else of the app is busy. */
        private fun idle(): Boolean {
            val before = tasks.snapshot()
            SystemClock.sleep(IDLE_WINDOW_MS)
            val ours = synchronized(own) { HashSet(own) }.apply { addAll(ui.tids) }
            return Calibration.othersMs(before, tasks.snapshot()) { it in ours } < IDLE_WINDOW_MS / 10.0
        }

        /** True once the run ended ([release]): pages still being made are then the preparing thread's to recycle. */
        @Volatile
        private var released = false

        /**
         * Ends the run: closes the core ledger and recycles the pages, waiting a little for
         * one still being made, which stops at its next step ([prepare]); one that takes longer
         * recycles them itself.
         */
        fun release() {
            released = true
            val t = preparing
            t?.join(RELEASE_WAIT_MS)
            if (LibPixels.loaded) LibPixels.ledger(false)
            if (t == null || !t.isAlive) recycle()
        }

        private fun recycle() {
            src?.recycle()
            dst?.recycle()
            jxlOut?.recycle()
        }

        companion object {
            private const val JXL_NAME = "page-${DECODE_W}x$DECODE_H-q91"
            private const val JPEG_NAME = "page-${DECODE_W}x$DECODE_H-q90"
            private const val STEP_MS = 50L

            /** The longest [release] waits for pages still being made. */
            private const val RELEASE_WAIT_MS = 500L

            /** How [disturbedBy] names threads that ended during a sample. */
            private const val GONE = "(ended threads)"
        }
    }

    private const val BURST = 3
    private const val PAGE_W = 2000
    private const val PAGE_H = 3000

    /** The JPEG XL and platform JPEG page: 4K class, as image decode timings use. */
    private const val DECODE_W = 4000
    private const val DECODE_H = 6000

    private const val WAIT_MS = 1_000L
    private const val IDLE_WINDOW_MS = 300L
}
