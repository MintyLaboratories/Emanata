package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.LibPixels
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PixelEngine
import com.infinity.comicreader.reader.Rcas
import com.infinity.comicreader.reader.ScaleFilter
import com.infinity.comicreader.reader.SyntheticPage
import com.infinity.comicreader.ui.PageShader
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Debug and bench builds only: checks native sharpening ([NativeResampler.sharpen], `rcas.c`)
 * against its Kotlin model ([Rcas]) and times it on synthetic pages. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.SharpenCheckService
 *  [--es do check|shader|time|all] [--ei reps 10] [--ei strength 50]`
 *
 * - check: noise images (translucent pixels and flat rows among them) of many shapes, at
 *   strengths 0, 35 and 100 %, without and with strips past their edges, on 1 and 4 workers:
 *   the largest channel difference from the model (0 means bit-identical), and a CRC-32 of
 *   every result (`digest`).
 * - shader (API 33+): the GPU zoom shader ([PageShader]) built with sharpening for every filter,
 *   with and without anti-ringing and the color stage; a build the platform's compiler refuses
 *   is reported with its error.
 * - time: a 2160 x 3840 page (4K, upright) and a 1848 x 2960 one (the reference tablet's
 *   screen), each sharpened `reps` times after 2 warm-up rounds on the workers pages use
 *   (one per large core, pinned) and on one: median wall and process CPU ms per page, the
 *   thermal status before and after.
 *
 * Timings are valid only from a bench build with the app in front ("conditions", as
 * [ResampleCheckService] says). Results go to the log under "SharpenCheck", one JSON object
 * per line, ending with "done", and to `files/bench/sharpen.jsonl` in the app's external files.
 */
class SharpenCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = intent?.getStringExtra("do") ?: "all"
        val reps = intent?.getIntExtra("reps", 10)?.coerceIn(1, 100) ?: 10
        val strength = intent?.getIntExtra("strength", 50)?.coerceIn(1, 100) ?: 50
        thread(name = "sharpen-check") {
            Calibrator.hold {
                try {
                    out.parentFile?.mkdirs()
                    log(JSONObject().put("loaded", LibPixels.loaded).put("threads", NativeResampler.threads).put("conditions", conditions()))
                    if (what == "check" || what == "all") check()
                    if ((what == "shader" || what == "all") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shaders()
                    if (what == "time" || what == "all") time(reps, strength)
                } catch (t: Throwable) {
                    log(JSONObject().put("failed", "${t.javaClass.simpleName}: ${t.message}"))
                } finally {
                    Log.i(TAG, "done")
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/sharpen.jsonl")

    private fun conditions(): JSONObject {
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return JSONObject().put("cpuset", cpuset).put("debuggable", debuggable).put("valid", !debuggable && cpuset == "/top-app")
    }

    // ------------------------------------------------------------------ check

    /** Noise with a translucent pixel in about 64 and every seventh row flat. */
    private fun noise(w: Int, h: Int, seed: Int): Bitmap {
        val r = Random(seed)
        val px = IntArray(w * h) { i ->
            if ((i / w) % 7 == 3) return@IntArray 0xFF6A6A6A.toInt()
            val a = if (r.nextInt(64) == 0) 0x80 else 0xFF
            (a shl 24) or r.nextInt(0x1000000)
        }
        // Premultiplied as the bitmap stores it, so translucent colors stay within their alpha.
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    /** [b]'s pixels as memory holds them (premultiplied), as ARGB ints. */
    private fun held(b: Bitmap): IntArray {
        val buf = ByteBuffer.allocate(b.byteCount).order(ByteOrder.LITTLE_ENDIAN)
        b.copyPixelsToBuffer(buf)
        buf.rewind()
        return IntArray(b.width * b.height) {
            val p = buf.getInt()
            (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p shr 16) and 0xFF)
        }
    }

    private fun check() {
        val shapes = listOf(61 to 37, 1 to 9, 9 to 1, 2 to 2, 5 to 4, 333 to 517, 1848 to 2960)
        var worst = 0
        var cases = 0
        var failed = 0
        var off = 0L
        val digest = CRC32()
        for ((n, shape) in shapes.withIndex()) {
            val (w, h) = shape
            for (strength in listOf(0, 35, 100)) {
                for (rimmed in listOf(false, true)) {
                    for (workers in listOf(1, 4)) {
                        val img = noise(w, h, n * 31 + strength)
                        val strips = if (rimmed) Rcas.Rim(noise(w, 1, 1), noise(w, 1, 2), noise(1, h, 3), noise(1, h, 4)) else Rcas.Rim()
                        val amount = Rcas.amount(strength)
                        val model = held(img)
                        val rim = strips.map { held(it) }
                        Rcas.sharpen(
                            w, h, amount, { y, row -> System.arraycopy(model, y * w, row, 0, w) },
                            { y, row -> System.arraycopy(row, 0, model, y * w, w) }, rim
                        )
                        cases++
                        if (!NativeResampler.sharpen(img, amount, strips, engine = PixelEngine.NATIVE, workers = workers)) {
                            failed++
                        } else {
                            val got = held(img)
                            for (k in got.indices) {
                                val d = channelDiff(got[k], model[k])
                                if (d > 0) off++
                                if (d > worst) worst = d
                                digest.update(got[k])
                                digest.update(got[k] ushr 8)
                                digest.update(got[k] ushr 16)
                                digest.update(got[k] ushr 24)
                            }
                        }
                        img.recycle()
                        listOfNotNull(strips.top, strips.bottom, strips.left, strips.right).forEach { it.recycle() }
                    }
                }
            }
        }
        log(
            JSONObject().put("check", "native vs model").put("cases", cases).put("failed", failed).put("maxDiff", worst)
                .put("pixelsOff", off).put("digest", "%08x".format(digest.value)).put("ok", failed == 0 && worst == 0)
        )
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var worst = 0
        for (s in 0..24 step 8) worst = maxOf(worst, kotlin.math.abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)))
        return worst
    }

    // ------------------------------------------------------------------ shader

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun shaders() {
        for (filter in ScaleFilter.entries) for (antiRing in listOf(false, true)) for (colored in listOf(false, true)) {
            val error = runCatching { PageShader(PageShader.Kernel.FILTERED, colored, filter, antiRing, sharpen = true) }.exceptionOrNull()
            log(JSONObject().put("shader", filter.key).put("antiRing", antiRing).put("colored", colored).put("built", error == null).put("error", error?.message))
        }
    }

    // ------------------------------------------------------------------ time

    private fun thermal(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(PowerManager::class.java).currentThermalStatus else -1

    private fun time(reps: Int, strength: Int) {
        val amount = Rcas.amount(strength)
        for ((w, h) in listOf(2160 to 3840, 1848 to 2960)) {
            val page = SyntheticPage.draw(w, h)
            val pristine = ByteBuffer.allocate(page.byteCount)
            page.copyPixelsToBuffer(pristine)
            for (workers in listOf(NativeResampler.threads, 1)) {
                val walls = ArrayList<Double>()
                val cpus = ArrayList<Double>()
                val before = thermal()
                var ran = true
                repeat(reps + 2) { rep ->
                    pristine.rewind()
                    page.copyPixelsFromBuffer(pristine)
                    val cpu0 = Process.getElapsedCpuTime()
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    ran = ran && NativeResampler.sharpen(page, amount, engine = PixelEngine.NATIVE, urgent = { true }, workers = workers, pin = true)
                    val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    val cpu = (Process.getElapsedCpuTime() - cpu0).toDouble()
                    if (rep >= 2) {
                        walls += wall
                        cpus += cpu
                    }
                }
                log(
                    JSONObject().put("time", "${w}x$h").put("strength", strength).put("workers", workers).put("native", ran)
                        .put("wallMs", summary(walls)).put("cpuMs", summary(cpus)).put("thermalBefore", before).put("thermalAfter", thermal())
                        .put("conditions", conditions())
                )
            }
            page.recycle()
        }
    }

    private fun summary(values: List<Double>): JSONObject {
        val s = values.sorted()
        return JSONObject().put("median", Stats.round1(s[s.size / 2])).put("min", Stats.round1(s.first())).put("max", Stats.round1(s.last()))
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Sharpen check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking sharpening")
            .setOngoing(true)
            .build()
    }

    private fun log(o: JSONObject) {
        Log.i(TAG, o.toString())
        runCatching { out.appendText(o.toString() + "\n") }
    }

    private companion object {
        const val TAG = "SharpenCheck"
        const val CHANNEL = "sharpen_check"
        const val NOTIFICATION_ID = 7320
    }
}
