package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.TextDetector
import com.infinity.comicreader.data.CoverArt
import com.infinity.comicreader.data.CoverFocus
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug and bench builds only: finds the Smart crop anchor's focus on the covers of real books
 * and times the text detector. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.CoverFocusCheckService
 *  --es dir /path [--ei books 40] [--es setups cpu1,cpu2,cpu4,gpu|none] [--ei reps 3] [--ez marked false]
 *  [--es lane own|shared] [--ez busy false]`
 *
 * Takes `books` books spread evenly over every book under `dir` (read only), in name order,
 * and decodes each cover as the library does. For each: the detector's time on the cover's
 * input by each setup (`cpuN`: N threads of the large cores; `gpu`: Vulkan, fp16), 1 warm-up
 * and `reps` timed runs, the median; the app's own setup's `reps` runs, as asked (waits
 * included), median and most; the time of the whole focus (shrink, detail, detection, boxes) by
 * the app's own setup; the focus; and where Top, Center and Smart start a crop to a
 * 3:4 and a 1:1 box, as shares of the cover's height. With `marked`, a small copy of each cover
 * marked up (text boxes yellow, title green, the 1:1 crop by Smart cyan and by Top red) goes to
 * `files/bench/focus/<index>.jpg`. Books are named by index only. Results go to the log under
 * "CoverFocusCheck", one JSON object per line, ending with "done", and to
 * `files/bench/focus.jsonl` in the app's external files.
 *
 * The app's setup runs on [LibNcnn.cpuLane] (`lane own`, as the app does) or on the thread
 * of AI page tiles (`lane shared`). With `busy`, that thread meanwhile runs the detector on the
 * GPU over and over, as AI tiles keep it busy; its runs and their median time are logged at the
 * end.
 */
class CoverFocusCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val dir = intent?.getStringExtra("dir")
        val count = intent?.getIntExtra("books", 40)?.coerceIn(1, 10_000) ?: 40
        val setups = (intent?.getStringExtra("setups") ?: "cpu1,cpu2,cpu4,gpu").split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }
        val reps = intent?.getIntExtra("reps", 3)?.coerceIn(1, 50) ?: 3
        val marked = intent?.getBooleanExtra("marked", false) ?: false
        val shared = intent?.getStringExtra("lane") == "shared"
        val busy = intent?.getBooleanExtra("busy", false) ?: false
        thread(name = "cover-focus-check") {
            try {
                out.parentFile?.mkdirs()
                if (dir == null) {
                    log(JSONObject().put("failed", "no dir"))
                } else {
                    run(File(dir), count, setups, reps, marked, shared, busy)
                }
            } catch (t: Throwable) {
                log(JSONObject().put("failed", t.toString()))
            } finally {
                log(JSONObject().put("done", true))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun run(dir: File, count: Int, setups: List<String>, reps: Int, marked: Boolean, shared: Boolean, busy: Boolean) {
        val all = dir.walkTopDown().filter { it.isFile && Formats.isOpenable(it.name) && Formats.typeOf(it.name) != com.infinity.comicreader.data.EntryType.IMAGE }
            .sortedWith(compareBy(NaturalOrder) { it.absolutePath }).toList()
        val step = maxOf(1.0, all.size.toDouble() / count)
        val picked = (0 until minOf(count, all.size)).map { all[(it * step).toInt()] }
        log(JSONObject().put("books", all.size).put("picked", picked.size).put("setups", JSONArray(setups)).put("lane", if (shared) "shared" else "own").put("busy", busy))
        val files = TextDetector.install(this) ?: run {
            log(JSONObject().put("failed", "no model"))
            return
        }
        val detectors = setups.associateWith { s ->
            TextDetector(LibNcnn, { files }, gpu = s == "gpu", threads = s.removePrefix("cpu").toIntOrNull() ?: TextDetector.THREADS)
        }
        val app = TextDetector(if (shared) LibNcnn else LibNcnn.cpuLane, { files })
        val markDir = File(getExternalFilesDir(null), "bench/focus").apply { if (marked) mkdirs() }
        val load = if (busy) Load(files).apply { start() } else null
        try {
            covers(picked, detectors, app, reps, marked, markDir)
        } finally {
            load?.stop()
        }
        (detectors.values + app).forEach { it.close() }
    }

    /** Runs the detector on the GPU on the AI tiles' thread until [stop], which logs its runs. */
    private inner class Load(files: com.infinity.comicreader.ai.ModelFiles) {
        private val detector = TextDetector(LibNcnn, { files }, gpu = true)
        private val input = Bitmap.createBitmap(448, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        private val ms = ArrayList<Double>()

        @Volatile
        private var going = true
        private val worker = thread(start = false, name = "cover-focus-load") {
            while (going) {
                val t = SystemClock.elapsedRealtimeNanos()
                detector.detect(input)
                ms += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            }
        }

        fun start() = worker.start()

        fun stop() {
            going = false
            worker.join()
            detector.close()
            input.recycle()
            val sorted = ms.sorted()
            log(JSONObject().put("load", sorted.size).put("loadMs", if (sorted.isEmpty()) JSONObject.NULL else Stats.round1(sorted[sorted.size / 2])))
        }
    }

    private fun covers(picked: List<File>, detectors: Map<String, TextDetector>, app: TextDetector, reps: Int, marked: Boolean, markDir: File) {
        for ((index, book) in picked.withIndex()) {
            val cover = runCatching {
                PageSources.openCover(book, "UTF-8", cacheDir, 0).use { it.loadPage(0, Target(capPixels = 16_000_000L)) }
            }.getOrNull()
            if (cover == null) {
                log(JSONObject().put("book", index).put("failed", "no cover"))
                continue
            }
            try {
                val region = Rect(0, 0, cover.width, cover.height)
                val o = JSONObject().put("book", index).put("w", cover.width).put("h", cover.height)
                val (iw, ih) = SmartCrop.inputSize(cover.width, cover.height).let { it[0] to it[1] }
                val small = CoverArt.render(cover, Target(iw, ih, Fit.STRETCH).plan(cover.width.toDouble(), cover.height.toDouble()), false)
                val times = JSONObject()
                for ((name, d) in detectors) {
                    d.detect(small)
                    val ms = (1..reps).map {
                        val t = SystemClock.elapsedRealtimeNanos()
                        d.detect(small)
                        (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                    }.sorted()
                    times.put(name, Stats.round1(ms[ms.size / 2]))
                }
                val appMs = (1..reps).map {
                    val t = SystemClock.elapsedRealtimeNanos()
                    app.detect(small)
                    (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                }.sorted()
                small.recycle()
                val t0 = SystemClock.elapsedRealtimeNanos()
                val focus = CoverArt.focusOf(cover, region) { app.detect(it) }
                val focusMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                val map = run {
                    val s = CoverArt.render(cover, Target(iw, ih, Fit.STRETCH).plan(cover.width.toDouble(), cover.height.toDouble()), false)
                    app.detect(s).also { s.recycle() }
                }
                val boxes = map?.let { SmartCrop.boxes(it) }.orEmpty()
                o.put("input", "${iw}x$ih").put("detectMs", times)
                    .put("appMs", JSONObject().put("median", Stats.round1(appMs[appMs.size / 2])).put("most", Stats.round1(appMs.last())))
                    .put("focusMs", Stats.round1(focusMs)).put("boxes", boxes.size)
                    .put("glyphMax", boxes.maxOfOrNull { it.glyph / minOf(iw, ih) }?.let { Stats.round1(it * 1000) / 1000 } ?: JSONObject.NULL)
                    .put("title", band(focus.title?.let { it.top to it.bottom }))
                    .put("text", band(focus.text?.let { it.top to it.bottom }))
                for ((label, ratio) in listOf("3:4" to 0.75, "1:1" to 1.0)) {
                    // A box of this shape filled by the cover: the share of its height a crop keeps.
                    val span = minOf(1.0, cover.width.toDouble() / ratio / cover.height)
                    val h = cover.height.toDouble()
                    val smart = SmartCrop.offset(focus, h, span * h, vertical = true) / h
                    o.put(label, JSONObject().put("span", r3(span)).put("top", 0).put("center", r3((1 - span) / 2)).put("smart", r3(smart)))
                }
                if (marked) mark(cover, focus, boxes, iw, ih, File(markDir, "%03d.jpg".format(index)))
                log(o)
            } finally {
                cover.recycle()
            }
        }
    }

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    private fun band(b: Pair<Double, Double>?): Any = b?.let { JSONArray(listOf(r3(it.first), r3(it.second))) } ?: JSONObject.NULL

    /** A copy of [cover] 480 px tall, marked up, written as a JPEG to [file]. */
    private fun mark(cover: Bitmap, focus: CoverFocus, boxes: List<SmartCrop.TextBox>, mapW: Int, mapH: Int, file: File) {
        val h = 480
        val w = (cover.width * h.toDouble() / cover.height).toInt().coerceAtLeast(1)
        val small = CoverArt.render(cover, Target(w, h, Fit.STRETCH).plan(cover.width.toDouble(), cover.height.toDouble()), false)
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        small.recycle()
        val c = Canvas(out)
        val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        p.color = Color.YELLOW
        for (b in boxes) c.drawRect((b.left / mapW * w).toFloat(), (b.top / mapH * h).toFloat(), (b.right / mapW * w).toFloat(), (b.bottom / mapH * h).toFloat(), p)
        p.color = Color.GREEN
        p.strokeWidth = 3f
        focus.title?.let { c.drawRect((it.left * w).toFloat(), (it.top * h).toFloat(), (it.right * w).toFloat(), (it.bottom * h).toFloat(), p) }
        val span = minOf(1.0, cover.width.toDouble() / cover.height) * h
        val smart = SmartCrop.offset(focus, h.toDouble(), span, vertical = true)
        p.color = Color.RED
        c.drawRect(1f, 1f, w - 1f, span.toFloat(), p)
        p.color = Color.CYAN
        c.drawRect(4f, smart.toFloat() + 3, w - 4f, (smart + span).toFloat() - 3, p)
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out.recycle()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Cover focus check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking cover focus")
            .setOngoing(true)
            .build()
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/focus.jsonl")

    private fun log(o: JSONObject) {
        Log.i(TAG, o.toString())
        runCatching { out.appendText(o.toString() + "\n") }
    }

    private companion object {
        const val TAG = "CoverFocusCheck"
        const val CHANNEL = "cover_focus_check"
        const val NOTIFICATION_ID = 7310
    }
}
