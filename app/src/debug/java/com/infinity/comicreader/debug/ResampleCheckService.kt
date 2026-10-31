package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PixelEngine
import com.infinity.comicreader.reader.Resampler
import com.infinity.comicreader.reader.ScaleFilter
import com.infinity.comicreader.reader.Scaling
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Debug builds only: checks the native resampler against [Resampler]'s premultiplied model of
 * it ([check]), and times every engine on synthetic pages ([time], [sustain]). Only the shell
 * may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.ResampleCheckService
 *  [--es do check|time|sustain|all] [--es engines kotlin,native,gpu] [--es threads 1,2,4,8] [--ei reps 10] [--ei seconds 20]
 *  [--es filters lanczos3,lanczos6,bicubic,hermite]`
 *
 * - check: native CPU and GPU against the model on small noise images (every filter,
 *   anti-ringing, linear light, translucency, windows), and the CPU against the shipping
 *   Kotlin path on an opaque page; the largest channel difference of each.
 * - time: per engine, thread count and filter, a 2000x3000 and a 4000x6000 page shrunk to
 *   1848x2772 (the tablet's portrait page), `reps` times after 2 warm-ups: wall and process
 *   CPU per page, thermal status and headroom before and after.
 * - sustain: per engine, pages back to back for `seconds`, sampling the battery current every
 *   100 ms after a 5 s idle baseline: pages per second, mean draw above idle, and so energy per
 *   page (valid only unplugged: `dumpsys battery unplug`, reset after), thermal at the end.
 *
 * Results go to the log under "ResampleCheck", one JSON object per line, ending with "done",
 * and to `files/bench/resample.jsonl` in the app's external files.
 */
class ResampleCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = intent?.getStringExtra("do") ?: "all"
        val engines = intent?.getStringExtra("engines")?.split(',')?.mapNotNull { PixelEngine.of(it.trim()) } ?: PixelEngine.entries
        val threads = intent?.getStringExtra("threads")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(1, 2, 4, 8)
        val filters = intent?.getStringExtra("filters")?.split(',')?.map { ScaleFilter.of(it.trim()) } ?: listOf(ScaleFilter.LANCZOS3)
        val reps = intent?.getIntExtra("reps", 10)?.coerceIn(1, 100) ?: 10
        val seconds = intent?.getIntExtra("seconds", 20)?.coerceIn(3, 600) ?: 20
        thread(name = "resample-check") {
            val keptEngine = NativeResampler.engine
            val keptThreads = NativeResampler.threads
            try {
                out.parentFile?.mkdirs()
                if (what == "check" || what == "all") check()
                if (what == "time" || what == "all") time(engines, threads, filters, reps)
                if (what == "sustain" || what == "all") sustain(engines, seconds, keptThreads)
            } catch (t: Throwable) {
                log(JSONObject().put("failed", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                NativeResampler.engine = keptEngine
                NativeResampler.threads = keptThreads
                Log.i(TAG, "done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/resample.jsonl")

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
        for (engine in listOf(PixelEngine.NATIVE, PixelEngine.GPU)) {
            var worst = 0
            var failed = 0
            var off = 0L
            var pixels = 0L
            for ((i, c) in cases.withIndex()) {
                for (linear in listOf(false, true)) {
                    for (opaque in listOf(true, false)) {
                        val src = noise(c.srcW, c.srcH, i * 4 + (if (linear) 2 else 0) + (if (opaque) 1 else 0), opaque)
                        val held = premultipliedPixels(src)
                        val model = Resampler.resample(held, c.srcW, c.srcH, c.window, c.dstW, c.dstH, linear, c.scaling, premultiplied = true)
                        val dst = Bitmap.createBitmap(c.dstW, c.dstH, Bitmap.Config.ARGB_8888)
                        if (!NativeResampler.resample(src, dst, c.window, linear, c.scaling, engine = engine, fallback = false)) {
                            failed++
                            continue
                        }
                        val got = premultipliedPixels(dst)
                        for (k in got.indices) {
                            val d = channelDiff(got[k], model[k])
                            worst = max(worst, d)
                            if (d > 0) off++
                        }
                        pixels += got.size
                        src.recycle()
                        dst.recycle()
                    }
                }
            }
            log(
                JSONObject().put("check", engine.key).put("cases", cases.size * 4).put("failed", failed).put("maxDiff", worst)
                    .put("pixelsOff", off).put("pixels", pixels).put("ok", failed == 0 && worst <= 1)
            )
        }
        // The shipping Kotlin path reads and writes unpremultiplied: on an opaque page both agree.
        val page = page(2000, 3000)
        val window = Resampler.Window.of(2000, 3000)
        for (scaling in listOf(Scaling.DEFAULT, Scaling(ScaleFilter.LANCZOS6, 0, 100))) {
            val a = Bitmap.createBitmap(1848, 2772, Bitmap.Config.ARGB_8888)
            NativeResampler.resample(page, a, window, false, scaling, engine = PixelEngine.NATIVE)
            val b = kotlinResample(page, 1848, 2772, window, scaling)
            val pa = IntArray(1848 * 2772).also { a.getPixels(it, 0, 1848, 0, 0, 1848, 2772) }
            val pb = IntArray(1848 * 2772).also { b.getPixels(it, 0, 1848, 0, 0, 1848, 2772) }
            var worst = 0
            for (k in pa.indices) worst = max(worst, channelDiff(pa[k], pb[k]))
            log(JSONObject().put("check", "native vs kotlin, 2000x3000 page").put("scaling", "$scaling").put("maxDiff", worst).put("ok", worst <= 1))
        }
    }

    private class Case(val srcW: Int, val srcH: Int, val window: Resampler.Window, val dstW: Int, val dstH: Int, val scaling: Scaling)

    // ------------------------------------------------------------------ time

    private fun time(engines: List<PixelEngine>, threads: List<Int>, filters: List<ScaleFilter>, reps: Int) {
        for ((w, h) in listOf(2000 to 3000, 4000 to 6000)) {
            val page = page(w, h)
            for (filter in filters) {
                val scaling = Scaling(filter, 0, Scaling.DEFAULT_DOWN_RING)
                for (engine in engines) {
                    for (n in if (engine == PixelEngine.GPU) listOf(1) else threads) {
                        val before = state()
                        val walls = ArrayList<Double>()
                        val cpus = ArrayList<Double>()
                        repeat(2 + reps) { rep ->
                            val cpu0 = Process.getElapsedCpuTime()
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            run(engine, n, page, 1848, 2772, scaling).recycle()
                            val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                            if (rep >= 2) {
                                walls += wall
                                cpus += (Process.getElapsedCpuTime() - cpu0).toDouble()
                            }
                        }
                        log(
                            JSONObject().put("time", engine.key).put("threads", n).put("page", "${w}x$h").put("filter", filter.key)
                                .put("wallMs", summary(walls)).put("cpuMs", summary(cpus)).put("before", before).put("after", state())
                        )
                    }
                }
            }
            page.recycle()
        }
    }

    // ------------------------------------------------------------------ sustain

    /** As the app runs each engine: Kotlin on every core, native code on [threads] workers. */
    private fun sustain(engines: List<PixelEngine>, seconds: Int, threads: Int) {
        val page = page(2000, 3000)
        for (engine in engines) {
            val idle = sampleCurrent(5_000) {}
            val n = if (engine == PixelEngine.KOTLIN) Runtime.getRuntime().availableProcessors() else threads
            var pages = 0
            val t0 = SystemClock.elapsedRealtime()
            val cpu0 = Process.getElapsedCpuTime()
            val busy = sampleCurrent(seconds * 1000L) { until ->
                while (SystemClock.elapsedRealtime() < until) {
                    run(engine, n, page, 1848, 2772, Scaling.DEFAULT).recycle()
                    pages++
                }
            }
            val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val volts = voltage()
            // Draw above idle while working, times voltage and time, over the pages made.
            val extraMa = abs(busy) - abs(idle)
            val mjPerPage = if (pages > 0 && volts > 0) extraMa * volts * wall / pages else Double.NaN
            log(
                JSONObject().put("sustain", engine.key).put("threads", n).put("seconds", wall).put("pages", pages)
                    .put("pagesPerS", pages / wall).put("cpuMsPerPage", (Process.getElapsedCpuTime() - cpu0).toDouble() / max(pages, 1))
                    .put("idleMa", num(idle)).put("busyMa", num(busy)).put("volts", volts).put("mJPerPage", num(mjPerPage))
                    .put("after", state())
            )
        }
        page.recycle()
    }

    /** Mean battery current in mA over [ms] while [work] runs (given its deadline); negative while draining. */
    private fun sampleCurrent(ms: Long, work: (until: Long) -> Unit): Double {
        val battery = getSystemService(BatteryManager::class.java)
        val samples = ArrayList<Int>()
        val until = SystemClock.elapsedRealtime() + ms
        val sampler = thread(name = "current") {
            while (SystemClock.elapsedRealtime() < until) {
                battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.let { synchronized(samples) { samples += it } }
                Thread.sleep(100)
            }
        }
        work(until)
        sampler.join()
        // Microamperes on most devices.
        return synchronized(samples) { if (samples.isEmpty()) Double.NaN else samples.average() / 1000.0 }
    }

    private fun voltage(): Double {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
    }

    private fun state(): JSONObject {
        val power = getSystemService(PowerManager::class.java)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject()
            .put("thermal", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else -1)
            // NaN when asked again within a second, and where unknown.
            .put("headroom", (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(0) else Float.NaN).let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
            .put("plugged", (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("batteryPct", getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
    }

    // ------------------------------------------------------------------ engines and pixels

    /** [src] shrunk to [w] x [h] by [engine] on [threads] CPU workers; the engine must not fall back. */
    private fun run(engine: PixelEngine, threads: Int, src: Bitmap, w: Int, h: Int, scaling: Scaling): Bitmap {
        val window = Resampler.Window.of(src.width, src.height)
        if (engine == PixelEngine.KOTLIN) return kotlinResample(src, w, h, window, scaling, threads)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        NativeResampler.threads = threads
        check(NativeResampler.resample(src, dst, window, false, scaling, engine = engine, fallback = false)) { "${engine.key} did not run" }
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

    /** A synthetic comic page: white paper, ink lines and hatching, flat and graded color, a noisy panel. */
    private fun page(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val r = Random(w)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val panelH = h / 3f
        for (i in 0 until 3) {
            val top = i * panelH + w * 0.02f
            val bottom = (i + 1) * panelH - w * 0.02f
            paint.shader = LinearGradient(0f, top, 0f, bottom, Color.rgb(250, 220, 160), Color.rgb(60, 110, 190), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            c.drawRect(w * 0.04f, top, w * 0.96f, bottom, paint)
            paint.shader = null
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w / 400f
            c.drawRect(w * 0.04f, top, w * 0.96f, bottom, paint)
            repeat(60) {
                paint.strokeWidth = w / (300f + r.nextInt(900))
                c.drawLine(w * (0.05f + 0.9f * r.nextFloat()), top + (bottom - top) * r.nextFloat(), w * (0.05f + 0.9f * r.nextFloat()), top + (bottom - top) * r.nextFloat(), paint)
            }
        }
        // Fine hatching, the hardest case for ringing.
        paint.strokeWidth = 1f
        var x = w * 0.1f
        while (x < w * 0.4f) {
            c.drawLine(x, h * 0.4f, x, h * 0.55f, paint)
            x += 3f
        }
        val noisy = IntArray(w / 4 * (h / 8)) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        b.setPixels(noisy, 0, w / 4, w / 2, h / 2, w / 4, h / 8)
        // Opaque, as a decoded JPEG page is.
        b.setHasAlpha(false)
        return b
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
