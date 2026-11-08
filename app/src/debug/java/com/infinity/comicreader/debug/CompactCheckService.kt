package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.infinity.comicreader.R
import com.infinity.comicreader.compact.AndroidImages
import com.infinity.comicreader.compact.CompactWorkers
import com.infinity.comicreader.compact.FlatNames
import com.infinity.comicreader.compact.JxlVersion
import com.infinity.comicreader.compact.LibJxl
import com.infinity.comicreader.compact.PageWork
import com.infinity.comicreader.compact.Pixels
import com.infinity.comicreader.compact.Png
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.ConvertRates
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.ui.FileJobs
import com.infinity.comicreader.ui.TimeText
import com.infinity.comicreader.util.Formats
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * Debug builds only: checks compaction on the device, in the app's own process (its
 * library). Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.CompactCheckService --es do <what> ...`
 *
 * - `selftest`: libjxl through the JNI binding, and each page route on made-up pages;
 * - `extract --es path <book> --es out <dir>`: every page of a book into a folder, by its file name;
 * - `rebuild --es path <book or folder> [--ez compatible true] [--ez inplace true]`: Compact
 *   (or Make compatible) each book as the ⋮ menu does, timed one by one;
 * - `compare --es path <cbz> --es out <dir>`: each JPEG page of a book against the file of its name in a folder, byte for byte;
 * - `estimate --es path <book or folder> --es how compact|compatible|repack|pack|pack-compacted [--ez inplace true]`: the
 *   books (or, to pack, the subfolders) estimated as the Convert dialog does, then run as one queued operation, the
 *   time left logged every few seconds and the actual time at the end.
 *
 * Results go to the log under "CompactCheck", ending with "done".
 */
class CompactCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = intent?.getStringExtra("do")
        val path = intent?.getStringExtra("path")
        val out = intent?.getStringExtra("out")
        val compatible = intent?.getBooleanExtra("compatible", false) == true
        val inPlace = intent?.getBooleanExtra("inplace", false) == true
        val how = intent?.getStringExtra("how")
        thread(name = "compact-check") {
            try {
                when (what) {
                    "selftest" -> selfTest()
                    "extract" -> extract(File(path!!), File(out!!))
                    "rebuild" -> rebuild(File(path!!), compatible, inPlace)
                    "compare" -> compare(File(path!!), File(out!!))
                    "estimate" -> estimate(File(path!!), how ?: "compact", inPlace)
                    else -> log("unknown: $what")
                }
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.w(TAG, t)
            } finally {
                log("done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun selfTest() {
        log("libjxl ${LibJxl.version}, workers ${CompactWorkers.max()} of ${Runtime.getRuntime().availableProcessors()} cores")
        check(JxlVersion.matches(LibJxl.version)) { "libjxl ${LibJxl.version} is not the ${JxlVersion.MAJOR}.${JxlVersion.MINOR} release the binding is compiled against" }
        log("libjxl matches the binding's headers: ${LibJxl.available}")
        val pages = PageWork(LibJxl, AndroidImages)
        val color = picture(1200, 1700, gray = false, alpha = false)
        val gray = picture(1200, 1700, gray = true, alpha = false)
        val clear = picture(800, 600, gray = false, alpha = true)

        val jpeg = compress(color, Bitmap.CompressFormat.JPEG, 90)
        var t = System.nanoTime()
        val jxl = pages.compact(jpeg)
        log("jpeg ${jpeg.size} -> ${jxl?.ext} ${jxl?.bytes?.size} in ${ms(t)} ms")
        t = System.nanoTime()
        val back = jxl?.let { pages.compatible(it.bytes) }
        log("  back: ${back?.ext}, same bytes: ${back?.bytes?.contentEquals(jpeg)} in ${ms(t)} ms")

        for ((name, bitmap) in listOf("color" to color, "gray" to gray, "alpha" to clear)) {
            val png = compress(bitmap, Bitmap.CompressFormat.PNG, 100)
            t = System.nanoTime()
            val made = pages.compact(png)
            log("png $name ${png.size} -> ${made?.ext} ${made?.bytes?.size} in ${ms(t)} ms")
            val original = AndroidImages.decode(png)
            t = System.nanoTime()
            val webp = made?.let { pages.compatible(it.bytes) }
            log("  back: ${webp?.ext} ${webp?.bytes?.size}, same pixels: ${original?.same(webp?.let { AndroidImages.decode(it.bytes) })} in ${ms(t)} ms")
            val repacked = made?.let { pages.compact(it.bytes) }
            log("  recompressed: ${repacked?.bytes?.size ?: "kept"}")
        }

        // A PNG with a color profile: its samples and profile kept as they are.
        val icc = LibJxl.encode(ByteArray(64 * 48 * 3) { it.toByte() }, 64, 48, 3, 8, null, 1)?.let { LibJxl.iccOfPixels(it) }
        val stored = Png.decode(compress(clear, Bitmap.CompressFormat.PNG, 100))?.let { Pixels(it.width, it.height, it.channels, 8, it.data) }
        if (icc != null && stored != null) {
            val made = pages.compact(Png.encode(stored, icc))
            log(
                "png with profile -> ${made?.ext}, profile kept: ${made?.let { LibJxl.iccOfPixels(it.bytes) }?.contentEquals(icc)}, " +
                    "same samples: ${made?.let { LibJxl.decode(it.bytes, stored.channels, 8) }?.contentEquals(stored.data)}"
            )
        }

        // 16 bits: a PNG of 16 bits back.
        val deep = ByteArray(300 * 200 * 3 * 2) { (it * 7 + it / 600).toByte() }
        val deepJxl = LibJxl.encode(deep, 300, 200, 3, 16, null, 7)
        val deepBack = deepJxl?.let { pages.compatible(it) }
        log("16-bit jxl ${deepJxl?.size} -> ${deepBack?.ext}, same: ${Png.decode(deepBack?.bytes ?: ByteArray(0))?.data?.contentEquals(deep)}")

        // A lossy JPEG XL from elsewhere.
        val lossy = JxlCoder.encode(color, compressionOption = JxlCompressionOption.LOSSY, quality = 80)
        val info = LibJxl.info(lossy)
        val lossyBack = pages.compatible(lossy)
        log("lossy jxl ${lossy.size} (original profile ${info?.originalProfile}) -> ${lossyBack?.ext} ${lossyBack?.bytes?.size}, reencoded ${lossyBack?.reencoded}")
        val jpegBack = jxl?.let { LibJxl.reconstruct(pages.compact(it.bytes)?.bytes ?: it.bytes) }
        log("repack keeps the JPEG: ${jpegBack?.contentEquals(jpeg)}")
    }

    private fun picture(width: Int, height: Int, gray: Boolean, alpha: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (x * 255 / width + y * 64 / height) and 0xFF
                val a = if (alpha) (y * 255 / height) else 255
                row[x] = if (gray) Color.argb(a, v, v, v) else Color.argb(a, v, (y * 255 / height), ((x + y) / 7) and 0xFF)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    private fun extract(book: File, out: File) {
        out.mkdirs()
        var n = 0
        var bytes = 0L
        ArchiveReader.open(book, "UTF-8")!!.use { reader ->
            for (entry in reader.entries) {
                if (!Formats.isImageName(entry.path)) continue
                val name = FlatNames.baseName(entry.path)
                reader.read(entry) { input -> File(out, name).outputStream().use { input.copyTo(it) } }
                n++
                bytes += entry.size
            }
        }
        log("extracted $n pages, $bytes bytes")
    }

    private fun rebuild(path: File, compatible: Boolean, inPlace: Boolean) {
        val books = if (path.isDirectory) path.listFiles().orEmpty().filter { it.isFile && Formats.isArchiveName(it.name) }.sortedBy { it.name } else listOf(path)
        val jobs = FileJobs(this, Library(this), Prefs(this))
        val all = System.nanoTime()
        var before = 0L
        var after = 0L
        for ((i, book) in books.withIndex()) {
            val size = book.length()
            val chosen = jobs.look(listOf(book.absolutePath))
            val t = System.nanoTime()
            val outcome = jobs.rebuild(listOf(book.absolutePath), compatible, inPlace, false, chosen, Quiet)
            val took = ms(t)
            val done = outcome as? FileWork.Outcome.Done
            val made = done?.path?.let { File(it) }
            val newSize = made?.length() ?: size
            before += size
            after += newSize
            log(
                "book ${i + 1} of ${books.size}: ${if (done != null) "done" else outcome.javaClass.simpleName} in $took ms, " +
                    "$size -> $newSize bytes (${if (size > 0) "%.1f".format(100.0 * newSize / size) else "-"}%), " +
                    "saved ${done?.summary?.saved}, unchanged ${done?.summary?.unchanged}, reencoded ${done?.summary?.reencoded?.values?.sumOf { it.size }}, " +
                    "not converted ${done?.summary?.failedPages}" +
                    ((outcome as? FileWork.Outcome.Failed)?.error?.let { ", error ${it.javaClass.simpleName}: ${it.message}" } ?: "")
            )
        }
        log("all ${books.size} books in ${ms(all)} ms: $before -> $after bytes")
    }

    private fun estimate(path: File, how: String, inPlace: Boolean) {
        val jobs = FileJobs(this, Library(this), Prefs(this))
        val pack = how.startsWith("pack")
        val items = when {
            !path.isDirectory -> listOf(path)
            pack -> path.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
            else -> path.listFiles().orEmpty().filter { it.isFile && Formats.isArchiveName(it.name) }.sortedBy { it.name }
        }.map { it.absolutePath }
        if (items.isEmpty()) return log("nothing to $how")
        val rebuild = when (how) {
            "compatible" -> FileJobs.Rebuild.COMPATIBLE
            "repack" -> FileJobs.Rebuild.REPACK
            else -> FileJobs.Rebuild.COMPACT
        }
        val t = System.nanoTime()
        val forecast = if (pack) jobs.forecastPack(items, how == "pack-compacted") else jobs.forecast(items, rebuild)
        val plan = forecast.get()
        val away = plan.sum() * ConvertRates.slowdown()
        log("estimate $how of ${items.size} in ${ms(t)} ms: %.1f s, away %.1f s (%s); per book %s".format(plan.sum(), away, TimeText.takes(this, plan.sum(), away), plan.joinToString { "%.1f".format(it) }))
        val chosen = jobs.look(items)
        val name = File(items.first()).name
        val kind = if (pack) FileWork.Kind.PACK else rebuild.kind
        val started = System.nanoTime()
        val id = if (pack) {
            val plans = items.map { FileJobs.PackPlan(it, File(it).name + ".cbz") }
            FileWork.start(kind, name, items.size) { p -> jobs.pack(plans, how == "pack-compacted", chosen, p) }
        } else {
            FileWork.start(kind, name, items.size) { p -> jobs.rebuild(items, rebuild, inPlace, false, chosen, p, forecast) }
        }
        var logged = 0L
        while (true) {
            // A library screen in front acknowledges the outcome itself.
            val status = FileWork.operations.value.firstOrNull { it.id == id }
            val outcome = status?.outcome
            if (status == null || outcome != null) {
                FileWork.acknowledge(id)
                log("actual $how: %.1f s, estimated %.1f s; %s".format((System.nanoTime() - started) / 1e9, plan.sum(), outcome?.javaClass?.simpleName ?: "told"))
                break
            }
            val at = System.nanoTime() - started
            if (at - logged >= LOG_EVERY_MS * 1_000_000) {
                logged = at
                status.secondsLeft(FileWork.clock())?.let {
                    val where = if (FileWork.inFront()) "front" else "away"
                    log("at %.0f s ($where): item %d of %d, %.0f%%, left %.1f s (%s)".format(at / 1e9, status.index + 1, status.count, 100 * (status.fraction ?: 0f), it, TimeText.left(this, it)))
                }
            }
            Thread.sleep(POLL_MS)
        }
        log("rates:\n" + ConvertRates.get(this).encode())
    }

    private fun compare(book: File, dir: File) {
        var same = 0
        var differ = 0
        var missing = 0
        val seen = HashSet<String>()
        ZipFile(book).use { zip ->
            for (entry in zip.entries()) {
                if (Formats.ext(entry.name) !in setOf("jpg", "jpeg")) continue
                val name = FlatNames.baseName(entry.name)
                seen += name
                val original = File(dir, name)
                if (!original.isFile) {
                    missing++
                    continue
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.contentEquals(original.readBytes())) same++ else differ++
            }
        }
        val unmatched = dir.listFiles().orEmpty().count { it.name !in seen }
        log("compare: $same same, $differ differ, $missing not in the folder, $unmatched in the folder not in the book")
    }

    private object Quiet : FileWork.Progress {
        override val alive: Boolean get() = true

        override fun report(done: Long, total: Long) = Unit

        override fun stoppable(can: Boolean) = Unit

        override fun item(name: String, index: Int, count: Int) = Unit
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Compaction check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_compress)
            .setContentTitle("Checking compaction")
            .setOngoing(true)
            .build()
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "CompactCheck"
        private const val LOG_EVERY_MS = 5_000L
        private const val POLL_MS = 100L
        const val CHANNEL = "compact_check"
        const val NOTIFICATION_ID = 7304
    }
}
