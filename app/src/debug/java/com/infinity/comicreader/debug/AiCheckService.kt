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
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiPages
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.ModelFiles
import com.infinity.comicreader.ai.PageLimits
import com.infinity.comicreader.ai.Session
import com.infinity.comicreader.ai.Sessions
import com.infinity.comicreader.ai.TileEngine
import com.infinity.comicreader.ai.TilePlan
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.SyntheticPage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Debug and bench builds only: runs an ncnn model ([LibNcnn]) end to end on synthetic pages.
 * Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.AiCheckService
 *  --es model <path to .param, its .bin beside it> [--es do check|time|limits|all] [--es engines gpu,cpu]
 *  [--es tiles 128,192,256,384] [--es size 1000x1500] [--es small 300x420] [--ei reps 3] [--ei cooldown 10] [--ez fp16 true] [--ei threads 4]`
 *
 * - open: per engine, the time to open and measure the model, its geometry and tile size.
 * - check: tiled output against one tile covering the whole of a `small` page (seams), and
 *   the GPU's against the CPU's (fp16 against fp32 arithmetic).
 * - time: per engine and tile size, `reps` pages of `size` after one warm-up: time per tile
 *   and per page, peak resident and graphics memory, thermal status and headroom.
 * - limits: a page whose time runs out falls back to exactly the Lanczos3 page; a page
 *   canceled after three tiles stops within one; a sample of each output saved as PNG.
 *
 * Results go to the log under "AiCheck", one JSON object per line, ending with "done", and
 * to `files/bench/ai.jsonl` in the app's external files (samples beside it).
 */
class AiCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = intent?.getStringExtra("do") ?: "all"
        val param = intent?.getStringExtra("model")?.let(::File)
        val engines = (intent?.getStringExtra("engines") ?: "gpu,cpu").split(',').map { it.trim() == "gpu" }
        val tiles = (intent?.getStringExtra("tiles") ?: "128,192,256,384").split(',').mapNotNull { it.trim().toIntOrNull() }
        val size = (intent?.getStringExtra("size") ?: "1000x1500").split('x').mapNotNull { it.toIntOrNull() }
        val small = (intent?.getStringExtra("small") ?: "300x420").split('x').mapNotNull { it.toIntOrNull() }
        val reps = intent?.getIntExtra("reps", 3)?.coerceIn(1, 50) ?: 3
        val cooldown = intent?.getIntExtra("cooldown", 10)?.coerceIn(0, 600) ?: 10
        val fp16 = intent?.getBooleanExtra("fp16", true) ?: true
        val threads = intent?.getIntExtra("threads", NativeResampler.threads)?.coerceIn(1, 16) ?: NativeResampler.threads
        thread(name = "ai-check") { Calibrator.hold {
            try {
                out.parentFile?.mkdirs()
                require(param != null && param.isFile) { "no model at $param" }
                require(size.size == 2 && small.size == 2) { "unreadable size" }
                val files = ModelFiles(param, File(param.path.removeSuffix(".param") + ".bin"))
                log(
                    JSONObject().put("model", param.name).put("loaded", LibNcnn.available)
                        .put("gpu", LibNcnn.gpu()?.let { JSONObject().put("name", it.name).put("budgetMb", it.budgetMb).put("fp16", it.fp16) })
                        .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else JSONObject.NULL)
                        .put("threads", threads).put("graphicsMb", graphicsKb() / 1024).put("rssMb", rssKb() / 1024)
                        .put("conditions", conditions()).put("state", state())
                )
                for (gpu in engines) {
                    val t0 = SystemClock.elapsedRealtime()
                    val session = Sessions.open(LibNcnn, files, threads, gpu = gpu, fp16 = fp16)
                    val openMs = SystemClock.elapsedRealtime() - t0
                    if (session == null) {
                        log(JSONObject().put("open", if (gpu) "gpu" else "cpu").put("failed", true))
                        continue
                    }
                    session.use { s ->
                        val g = s.geometry
                        log(
                            JSONObject().put("open", if (gpu) "gpu" else "cpu").put("onGpu", s.model.onGpu).put("ms", openMs)
                                .put("scale", g.scale).put("trim", g.trim).put("context", g.context).put("margin", g.margin).put("tile", s.size)
                        )
                        if (what == "check" || what == "all") seams(s, small[0], small[1])
                        if (what == "time" || what == "all") time(s, tiles, size[0], size[1], reps, cooldown)
                        if (what == "limits" || what == "all") limits(s, size[0], size[1])
                    }
                    log(JSONObject().put("closed", if (gpu) "gpu" else "cpu").put("graphicsMb", graphicsKb() / 1024).put("rssMb", rssKb() / 1024))
                }
                if ((what == "check" || what == "all") && engines.containsAll(listOf(true, false))) gpuAgainstCpu(files, fp16, threads, small[0], small[1])
            } catch (t: Throwable) {
                log(JSONObject().put("failed", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                Log.i(TAG, "done")
                stopSelfResult(startId)
            }
        } }
        return START_NOT_STICKY
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/ai.jsonl")

    // ------------------------------------------------------------------ check

    /** Tiled output at each size against one tile covering the whole page: the seams' largest and mean difference. */
    private fun seams(s: Session, w: Int, h: Int) {
        val page = SyntheticPage.draw(w, h)
        val whole = Session(s.model, s.geometry, max(page.width, page.height) + 2 * s.geometry.margin)
        val reference = TileEngine.run(whole, page).bitmap ?: return log(JSONObject().put("seams", "reference failed"))
        val ref = pixels(reference)
        reference.recycle()
        for (size in listOf(64, 96, 128, 192, 256).filter { TilePlan.fits(it, s.geometry) }) {
            val got = TileEngine.run(Session(s.model, s.geometry, size), page).bitmap ?: continue
            val d = diff(ref, pixels(got))
            got.recycle()
            log(JSONObject().put("seams", if (s.model.onGpu) "gpu" else "cpu").put("tile", size).put("maxDiff", d.max).put("meanDiff", Stats.round1(d.mean * 100) / 100).put("over2", d.over2))
        }
        page.recycle()
    }

    /** The GPU's output against the CPU's on one page at one tile size. */
    private fun gpuAgainstCpu(files: ModelFiles, fp16: Boolean, threads: Int, w: Int, h: Int) {
        val page = SyntheticPage.draw(w, h)
        val outputs = listOf(true, false).map { gpu ->
            Sessions.open(LibNcnn, files, threads, gpu = gpu, fp16 = fp16)?.use { s ->
                TileEngine.run(Session(s.model, s.geometry, 128), page).bitmap?.let { b -> pixels(b).also { b.recycle() } }
            }
        }
        page.recycle()
        val (g, c) = outputs
        if (g == null || c == null) return log(JSONObject().put("gpuVsCpu", "failed"))
        val d = diff(g, c)
        log(JSONObject().put("gpuVsCpu", true).put("maxDiff", d.max).put("meanDiff", Stats.round1(d.mean * 100) / 100).put("over2", d.over2))
    }

    // ------------------------------------------------------------------ time

    private fun time(s: Session, sizes: List<Int>, w: Int, h: Int, reps: Int, cooldown: Int) {
        val page = SyntheticPage.draw(w, h)
        for (size in sizes.filter { TilePlan.fits(it, s.geometry) }) {
            if (cooldown > 0) Thread.sleep(cooldown * 1000L)
            val session = Session(s.model, s.geometry, size)
            val before = state()
            val rssBefore = rssKb()
            TileEngine.run(session, page, UNLIMITED).bitmap?.recycle()
            val pages = ArrayList<Long>()
            val tileMs = ArrayList<Long>()
            var peakRss = 0L
            var peakGraphics = 0L
            var tiles = 0
            for (r in 0 until reps) {
                val running = AtomicBoolean(true)
                val sampler = thread(name = "ai-memory") {
                    var n = 0
                    while (running.get()) {
                        peakRss = max(peakRss, rssKb())
                        if (n++ % 10 == 0) peakGraphics = max(peakGraphics, graphicsKb())
                        Thread.sleep(20)
                    }
                }
                val t0 = SystemClock.elapsedRealtime()
                val run = TileEngine.run(session, page, UNLIMITED)
                pages += SystemClock.elapsedRealtime() - t0
                running.set(false)
                sampler.join()
                run.bitmap?.recycle()
                tileMs += run.stats.tileMs.toList()
                tiles = run.stats.planned
            }
            log(
                JSONObject().put("time", if (s.model.onGpu) "gpu" else "cpu").put("tile", size).put("page", "${w}x$h").put("tiles", tiles)
                    .put("pageMs", Stats.summary(pages)).put("tileMs", Stats.summary(tileMs))
                    .put("rssMb", JSONObject().put("before", rssBefore / 1024).put("peak", peakRss / 1024).put("after", rssKb() / 1024))
                    .put("graphicsPeakMb", peakGraphics / 1024).put("graphicsAfterMb", graphicsKb() / 1024)
                    .put("before", before).put("after", state())
            )
        }
        page.recycle()
    }

    // ------------------------------------------------------------------ limits

    private fun limits(s: Session, w: Int, h: Int) {
        val page = SyntheticPage.draw(w, h)
        val plan = Plan(w * 4 / 5, h * 4 / 5, 0.0, 0.0, w.toDouble(), h.toDouble())
        // A whole run's time, to set a limit it cannot meet.
        val t0 = SystemClock.elapsedRealtime()
        val full = AiPages.render(s, page, plan, Scaling.DEFAULT)
        val fullMs = SystemClock.elapsedRealtime() - t0
        save(full.bitmap, "ai-${if (s.model.onGpu) "gpu" else "cpu"}.png")
        val limit = fullMs * 2 / 5
        val t1 = SystemClock.elapsedRealtime()
        val short = AiPages.render(s, page, plan, Scaling.DEFAULT, limits = PageLimits(timeoutMs = limit))
        val shortMs = SystemClock.elapsedRealtime() - t1
        val lanczos = BitmapUtils.resample(page, plan, false, Scaling.DEFAULT)
        val same = diff(pixels(lanczos), pixels(short.bitmap)).max
        save(lanczos, "lanczos.png")
        log(
            JSONObject().put("timeout", if (s.model.onGpu) "gpu" else "cpu").put("fullMs", fullMs).put("limitMs", limit)
                .put("fallback", short.run.fallback?.name ?: JSONObject.NULL).put("tilesRun", short.run.stats.tiles)
                .put("of", short.run.stats.planned).put("returnedMs", shortMs).put("maxDiffFromLanczos", same)
        )
        full.bitmap.recycle()
        short.bitmap.recycle()
        lanczos.recycle()

        var canceledAt = 0L
        var tilesAfter = -1
        var done = 0
        val t2 = SystemClock.elapsedRealtime()
        try {
            TileEngine.run(s, page, canceled = {
                if (done >= 3 && canceledAt == 0L) canceledAt = SystemClock.elapsedRealtime()
                done >= 3
            }, onTile = { d, _, _ -> done = d; if (canceledAt != 0L) tilesAfter++ })
        } catch (e: CancellationException) {
            log(JSONObject().put("cancel", if (s.model.onGpu) "gpu" else "cpu").put("tilesRun", done).put("stopMs", SystemClock.elapsedRealtime() - canceledAt).put("totalMs", SystemClock.elapsedRealtime() - t2))
        }
        page.recycle()
    }

    // ------------------------------------------------------------------ helpers

    private class Diff(val max: Int, val mean: Double, val over2: Int)

    private fun diff(a: IntArray, b: IntArray): Diff {
        if (a.size != b.size) return Diff(255, 255.0, a.size)
        var worst = 0
        var sum = 0L
        var over = 0
        for (i in a.indices) {
            var d = 0
            for (s in 0..16 step 8) d = max(d, abs(((a[i] shr s) and 0xFF) - ((b[i] shr s) and 0xFF)))
            worst = max(worst, d)
            sum += d
            if (d > 2) over++
        }
        return Diff(worst, sum.toDouble() / a.size, over)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun save(b: Bitmap, name: String) {
        runCatching { File(out.parentFile, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** Resident memory of this process, in KB. */
    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()
    }.getOrDefault(0L)

    /** Graphics memory of this process as the system counts it (GPU buffers included), in KB. */
    private fun graphicsKb(): Long = runCatching {
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.getMemoryStat("summary.graphics").toLong()
    }.getOrDefault(0L)

    private fun conditions(): JSONObject {
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return JSONObject().put("cpuset", cpuset).put("debuggable", debuggable)
    }

    private fun state(): JSONObject {
        val power = getSystemService(PowerManager::class.java)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject()
            .put("thermal", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
            .put("headroom", (if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(0) else Float.NaN).let { if (it.isNaN()) JSONObject.NULL else Stats.round1(it * 100.0) / 100 })
            .put("batteryTempC", (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            .put("plugged", (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "AI check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking AI processing")
            .setOngoing(true)
            .build()
    }

    private fun log(o: JSONObject) {
        Log.i(TAG, o.toString())
        runCatching { out.appendText(o.toString() + "\n") }
    }

    private companion object {
        const val TAG = "AiCheck"
        const val CHANNEL = "ai_check"
        const val NOTIFICATION_ID = 7307

        /** Limits no timed page reaches. */
        val UNLIMITED = PageLimits(timeoutMs = 3_600_000L)
    }
}
