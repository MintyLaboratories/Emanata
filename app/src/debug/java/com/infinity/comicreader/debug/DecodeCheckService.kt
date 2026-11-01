package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlDecodingSpeed
import com.awxkee.jxlcoder.JxlEffort
import com.awxkee.jxlcoder.PreferredColorConfig
import com.infinity.comicreader.R
import com.infinity.comicreader.compact.LibJxl
import com.infinity.comicreader.debug.DecodeRuns.Engine
import com.infinity.comicreader.debug.DecodeRuns.Setup
import com.infinity.comicreader.debug.ResampleRuns.Cores
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.CpuCores
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.ImageKind
import com.infinity.comicreader.reader.JxlPages
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.SyntheticPage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Debug and bench builds only: times whole-page decodes by each decoder setup, and checks
 * native JPEG XL page decodes against jxl-coder's. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.DecodeCheckService
 *  [--es do check,time,cancel,fixtures,cold|all] [--es dirs /path/a,/path/b] [--ei pages 16] [--ez synthetic true]
 *  [--es setups coder,coder/big,page/4/big,page/8/any,platform,platform/prime] [--ei reps 5] [--ei gap 0] [--es fixtures /path]`
 *
 * Pages: synthetic 2000x3000 and 4000x6000 pages (JPEG XL at quality 91, JPEG at 90, PNG),
 * made once into the app's cache, and the first `pages` images of each of `dirs`, read
 * whole into memory first: file reads are not timed. One folder is held at a time, each
 * checked and timed before the next is read, and of it no more than half the heap: pages
 * past that are left out, logged as "cut". A setup is `engine[/threads[/cores]]`
 * ([DecodeRuns.Setup]); JPEG XL setups take JPEG XL pages, the others the rest.
 *
 * - check: each JPEG XL page by the native setups against jxl-coder: the largest channel
 *   difference, and how many pixels differ.
 * - time: each page by each setup, 1 warm-up round and `reps` timed ones, the setups
 *   interleaved (A B, B A...), `gap` ms of rest before each decode (a reading pace lets the
 *   cores clock down): wall and process CPU per page.
 * - cancel: each JPEG XL page by the native setup, asked to stop 30 ms in: how long it
 *   takes to return, and what it returned.
 * - fixtures (not in all): each `<name>.jxl` of the `fixtures` folder by the native page
 *   decode and by jxl-coder, against `<name>.png` or `<name>.jpg` decoded by the platform in
 *   sRGB (made by `tools/bench/bench.py fixtures`): the largest and mean difference of the
 *   premultiplied channels, and how many pixels differ, for each pair.
 * - cold (not in all; start the process anew for each run): after 2 s of rest, the first
 *   decode this process runs (libraries loaded, no decode run yet), by the first setup, of
 *   the first page it takes, then its other pages warm; and the process's peak resident
 *   memory over what it held before the first decode.
 *
 * Timings are valid only from a bench build with the app in front (see
 * ResampleCheckService). Results go to the log under "DecodeCheck", one JSON object per
 * line, ending with "done", and to `files/bench/decode.jsonl` in the app's external files.
 */
class DecodeCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** An encoded page, its size as shown, and whether it is a JPEG XL. */
    private class Page(val name: String, val bytes: ByteArray, val width: Int, val height: Int) {
        val jxl: Boolean = ImageKind.sniff(bytes) == ImageKind.JXL
    }

    private class Group(val name: String, val pages: List<Page>)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = (intent?.getStringExtra("do") ?: "all").split(',').map { it.trim() }.toSet()
        val threads = JxlPages.threads
        val asked = intent?.getStringExtra("setups")?.split(',')
        val big = CpuCores.device.let { if (it.big != 0L) NativeResampler.defaultThreads(it, Runtime.getRuntime().availableProcessors()) else 0 }
        val setups = asked?.mapNotNull { Setup.of(it, threads) } ?: DecodeRuns.defaultSetups(threads, JxlPages.pinned, big)
        val dirs = intent?.getStringExtra("dirs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val most = intent?.getIntExtra("pages", 16)?.coerceIn(1, 1000) ?: 16
        val synthetic = intent?.getBooleanExtra("synthetic", true) ?: true
        val reps = intent?.getIntExtra("reps", 5)?.coerceIn(1, 100) ?: 5
        val gap = intent?.getIntExtra("gap", 0)?.coerceIn(0, 10_000) ?: 0
        thread(name = "decode-check") { Calibrator.hold {
            try {
                out.parentFile?.mkdirs()
                if (asked != null && setups.size != asked.size) log(JSONObject().put("failed", "unreadable setups: $asked"))
                val cores = CpuCores.device
                log(
                    JSONObject().put("big", java.lang.Long.toBinaryString(cores.big)).put("little", java.lang.Long.toBinaryString(cores.little))
                        .put("threads", threads).put("conditions", conditions())
                )
                val sources = ArrayList<() -> List<Group>>()
                if (synthetic) sources += { syntheticGroups() }
                for (dir in dirs) sources += { listOf(read(File(dir), most)) }
                var coldDone = "cold" !in what
                // Each source's pages are dropped before the next is read.
                for (source in sources) coldDone = checkAndTime(source(), what, setups, reps, gap, threads, coldDone)
                intent?.getStringExtra("fixtures")?.takeIf { "fixtures" in what }?.let { fixtures(File(it)) }
            } catch (t: Throwable) {
                log(JSONObject().put("failed", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                Log.i(TAG, "done")
                stopSelfResult(startId)
            }
        } }
        return START_NOT_STICKY
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/decode.jsonl")

    /** Checks and times [groups] as [what] asks, and the cold decode unless [coldDone]; true once that has run. */
    private fun checkAndTime(groups: List<Group>, what: Set<String>, setups: List<Setup>, reps: Int, gap: Int, threads: Int, coldDone: Boolean): Boolean {
        for (g in groups) log(JSONObject().put("group", g.name).put("pages", g.pages.size).put("bytes", g.pages.sumOf { it.bytes.size.toLong() }))
        if ("check" in what || "all" in what) check(groups)
        if ("time" in what || "all" in what) time(groups, setups, reps, gap)
        if ("cancel" in what || "all" in what) cancel(groups, threads)
        return coldDone || cold(groups, setups.first())
    }

    private fun conditions(): JSONObject {
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return JSONObject().put("cpuset", cpuset).put("debuggable", debuggable).put("valid", !debuggable && cpuset == "/top-app")
            .put("state", ProcStats.now(this, memory = false).toJson())
    }

    // ------------------------------------------------------------------ pages

    /** The first [most] images of [dir] by name, read whole, as many as fit in half the heap. */
    private fun read(dir: File, most: Int): Group {
        val listed = dir.listFiles()
        if (listed.isNullOrEmpty()) log(JSONObject().put("unlisted", dir.name).put("exists", dir.exists()).put("listed", listed?.size ?: -1))
        val listedFiles = listed?.filter { it.isFile }?.sortedBy { it.name }.orEmpty().take(most)
        val budget = Runtime.getRuntime().maxMemory() / 2
        val files = listedFiles.take(DecodeRuns.fitting(listedFiles.map { it.length() }, budget))
        if (files.size < listedFiles.size) {
            log(JSONObject().put("cut", dir.name).put("pages", files.size).put("of", listedFiles.size).put("budgetMb", budget shr 20))
        }
        val pages = files.mapNotNull { f ->
            val bytes = f.readBytes()
            val size = Decoders.size(bytes)
            if (size == null) log(JSONObject().put("unsized", f.name))
            size?.let { Page(f.name, bytes, it[0], it[1]) }
        }
        return Group(dir.name, pages)
    }

    /** Synthetic pages at 2000x3000 and 4000x6000, each as JPEG XL q91, JPEG q90 and PNG; made once into the cache. */
    private fun syntheticGroups(): List<Group> {
        val groups = ArrayList<Group>()
        for ((w, h) in listOf(2000 to 3000, 4000 to 6000)) {
            val label = if (w == 2000) "2k" else "4k"
            val formats = listOf("jxl", "jpg", "png")
            val files = formats.associateWith { File(cacheDir, "decode-check/synthetic-$label.$it") }
            if (files.values.any { !it.exists() }) {
                files.values.first().parentFile?.mkdirs()
                val page = SyntheticPage.draw(w, h)
                try {
                    files.getValue("jxl").writeBytes(
                        JxlCoder.encode(page, JxlChannelsConfiguration.RGB, JxlCompressionOption.LOSSY, JxlEffort.SQUIRREL, 91, JxlDecodingSpeed.SLOWEST)
                    )
                    files.getValue("jpg").writeBytes(ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())
                    files.getValue("png").writeBytes(ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
                } finally {
                    page.recycle()
                }
            }
            for (format in formats) {
                val bytes = files.getValue(format).readBytes()
                groups += Group("synthetic-$label-$format", listOf(Page("synthetic-$label.$format", bytes, w, h)))
            }
        }
        return groups
    }

    // ------------------------------------------------------------------ decoders

    /** Where the reader's JPEG XL threads run: the large cores when pinned ([JxlPages.pinned]), else anywhere. */
    private val jxlCores: Cores get() = DecodeRuns.jxlCores(JxlPages.pinned)

    private fun mask(cores: Cores): Long = when (cores) {
        Cores.BIG -> CpuCores.device.big
        Cores.LITTLE -> CpuCores.device.little
        Cores.PRIME -> CpuCores.device.prime
        Cores.ANY -> 0L
    }

    private fun decode(setup: Setup, page: Page): Bitmap = when (setup.engine) {
        Engine.CODER -> LibJxl.onCores(mask(setup.cores)) { JxlCoder.decode(page.bytes, PreferredColorConfig.RGBA_8888) }
        Engine.PAGE -> {
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            val r = LibJxl.page(page.bytes, bitmap, setup.threads, mask(setup.cores))
            if (r != LibJxl.PAGE_OPAQUE && r != LibJxl.PAGE_ALPHA) {
                bitmap.recycle()
                error("${setup.key} returned $r for ${page.name}")
            }
            bitmap.apply { if (r == LibJxl.PAGE_OPAQUE) setHasAlpha(false) }
        }
        Engine.PLATFORM -> LibJxl.onCores(mask(setup.cores)) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(page.bytes))) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        Engine.FACTORY -> LibJxl.onCores(mask(setup.cores)) {
            BitmapFactory.decodeByteArray(page.bytes, 0, page.bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("BitmapFactory failed on ${page.name}")
    }

    // ------------------------------------------------------------------ check

    private fun check(groups: List<Group>) {
        val natives = listOf(Setup(Engine.PAGE, JxlPages.threads, jxlCores))
        for (group in groups) {
            for (page in group.pages.filter { it.jxl }) {
                val reference = decode(Setup(Engine.CODER, 1, Cores.ANY), page)
                val want = IntArray(reference.width * reference.height).also { reference.getPixels(it, 0, reference.width, 0, 0, reference.width, reference.height) }
                val refAlpha = reference.hasAlpha()
                reference.recycle()
                for (setup in natives) {
                    val got = decode(setup, page)
                    val pixels = IntArray(got.width * got.height).also { got.getPixels(it, 0, got.width, 0, 0, got.width, got.height) }
                    var worst = 0
                    var off = 0L
                    if (pixels.size == want.size) {
                        for (i in pixels.indices) {
                            val d = channelDiff(pixels[i], want[i])
                            if (d > 0) off++
                            worst = max(worst, d)
                        }
                    }
                    log(
                        JSONObject().put("check", setup.key).put("group", group.name).put("page", page.name)
                            .put("sameSize", pixels.size == want.size).put("maxDiff", worst).put("pixelsOff", off).put("pixels", pixels.size)
                            .put("alpha", got.hasAlpha()).put("referenceAlpha", refAlpha)
                    )
                    got.recycle()
                }
            }
        }
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var worst = 0
        for (s in 0..24 step 8) worst = max(worst, abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)))
        return worst
    }

    // ------------------------------------------------------------------ time

    private fun time(groups: List<Group>, setups: List<Setup>, reps: Int, gap: Int) {
        for (group in groups) {
            val jxl = group.pages.firstOrNull()?.jxl ?: continue
            val taking = setups.filter { it.takes(jxl) }
            if (taking.isEmpty()) continue
            val walls = taking.associateWith { ArrayList<Double>() }
            val cpus = taking.associateWith { ArrayList<Double>() }
            val before = conditions()
            for ((round, order) in ResampleRuns.interleaved(taking, reps + 1).withIndex()) {
                for (setup in order) {
                    for (page in group.pages) {
                        if (gap > 0) SystemClock.sleep(gap.toLong())
                        val cpu0 = Process.getElapsedCpuTime()
                        val t0 = System.nanoTime()
                        val bitmap = decode(setup, page)
                        val wall = (System.nanoTime() - t0) / 1e6
                        val cpu = (Process.getElapsedCpuTime() - cpu0).toDouble()
                        bitmap.recycle()
                        if (round > 0) {
                            walls.getValue(setup) += wall
                            cpus.getValue(setup) += cpu
                        }
                    }
                }
            }
            val after = conditions()
            for (setup in taking) {
                log(
                    JSONObject().put("time", setup.key).put("group", group.name).put("pages", group.pages.size).put("reps", reps).put("gapMs", gap)
                        .put("wallMs", summary(walls.getValue(setup))).put("cpuMs", summary(cpus.getValue(setup)))
                        .put("before", before).put("after", after)
                )
            }
        }
    }

    // ------------------------------------------------------------------ cancel

    private fun cancel(groups: List<Group>, threads: Int) {
        val mask = mask(jxlCores)
        for (group in groups) {
            for (page in group.pages.filter { it.jxl }) {
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                val t0 = System.nanoTime()
                var asked = 0
                val r = LibJxl.page(page.bytes, bitmap, threads, mask) {
                    asked++
                    System.nanoTime() - t0 > 30_000_000L
                }
                val ms = (System.nanoTime() - t0) / 1e6
                bitmap.recycle()
                log(
                    JSONObject().put("cancel", group.name).put("page", page.name).put("result", r).put("returnedMs", Stats.round1(ms))
                        .put("asked", asked).put("ok", r == LibJxl.PAGE_CANCELED && ms < 60)
                )
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun fixtures(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jxl") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) log(JSONObject().put("failed", "no fixtures in ${dir.name}"))
        for (f in files) {
            val base = f.name.removeSuffix(".jxl")
            val bytes = f.readBytes()
            val o = JSONObject().put("fixture", base)
            val size = Decoders.size(bytes)
            if (size == null) {
                log(o.put("failed", "unsized"))
                continue
            }
            val native = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
            val r = LibJxl.page(bytes, native, JxlPages.threads, mask(jxlCores))
            o.put("native", r)
            val coder = runCatching { JxlCoder.decode(bytes, PreferredColorConfig.RGBA_8888) }
                .onFailure { o.put("coderFailed", "${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
            coder?.let { o.put("coderConfig", it.config?.name).put("coderSpace", it.colorSpace?.name).put("coderAlpha", it.hasAlpha()) }
            val ref = listOf("png", "jpg").map { File(dir, "$base.$it") }.firstOrNull { it.exists() }?.let { platformSrgb(it.readBytes()) }
            val decoded = r == LibJxl.PAGE_OPAQUE || r == LibJxl.PAGE_ALPHA
            if (decoded && coder != null) o.put("nativeVsCoder", diff(native, coder))
            if (decoded && ref != null) o.put("nativeVsReference", diff(native, ref))
            if (coder != null && ref != null) o.put("coderVsReference", diff(coder, ref))
            native.recycle()
            coder?.recycle()
            ref?.recycle()
            log(o)
        }
    }

    /** [bytes] decoded by the platform into software ARGB_8888 pixels in sRGB. */
    private fun platformSrgb(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }

    /** The premultiplied RGBA bytes of [b], as ARGB_8888. */
    private fun rawRgba(b: Bitmap): ByteArray {
        val argb = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        val out = ByteBuffer.allocate(argb.byteCount)
        argb.copyPixelsToBuffer(out)
        if (argb !== b) argb.recycle()
        return out.array()
    }

    /** How [a] and [b] differ, channel by channel, premultiplied as bitmaps hold them. */
    private fun diff(a: Bitmap, b: Bitmap): JSONObject {
        if (a.width != b.width || a.height != b.height) return JSONObject().put("sameSize", false)
        val x = rawRgba(a)
        val y = rawRgba(b)
        var worst = 0
        var sum = 0L
        var off = 0L
        var i = 0
        while (i < x.size) {
            var pixel = 0
            for (c in 0 until 4) {
                val d = abs((x[i + c].toInt() and 0xFF) - (y[i + c].toInt() and 0xFF))
                sum += d
                pixel = max(pixel, d)
            }
            if (pixel > 0) off++
            worst = max(worst, pixel)
            i += 4
        }
        return JSONObject().put("max", worst).put("mean", Math.round(sum * 1000.0 / x.size) / 1000.0).put("pixelsOff", off)
            .put("pixels", x.size / 4)
    }

    // ------------------------------------------------------------------ cold

    /** False when no page of [groups] is [setup]'s to take. */
    private fun cold(groups: List<Group>, setup: Setup): Boolean {
        val group = groups.firstOrNull { g -> g.pages.firstOrNull()?.let { setup.takes(it.jxl) } == true } ?: return false
        val first = group.pages.first()
        // Libraries loaded, no decode run.
        LibJxl.version
        runCatching { JxlCoder.getSize(first.bytes) }
        Runtime.getRuntime().gc()
        SystemClock.sleep(2000)
        val reset = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
        val rssBefore = status("VmRSS")
        val cpu0 = Process.getElapsedCpuTime()
        val t0 = System.nanoTime()
        decode(setup, first).recycle()
        val coldMs = (System.nanoTime() - t0) / 1e6
        val coldCpu = Process.getElapsedCpuTime() - cpu0
        val peakFirst = status("VmHWM")
        val warm = ArrayList<Double>()
        for (page in group.pages.drop(1)) {
            val t = System.nanoTime()
            decode(setup, page).recycle()
            warm += (System.nanoTime() - t) / 1e6
        }
        val peakAll = status("VmHWM")
        log(
            JSONObject().put("cold", setup.key).put("group", group.name).put("coldMs", Stats.round1(coldMs)).put("coldCpuMs", coldCpu)
                .put("warmMs", summary(warm)).put("peakReset", reset).put("rssBeforeMb", rssBefore / 1024)
                .put("peakFirstMb", peakFirst / 1024).put("peakAllMb", peakAll / 1024).put("peakOverBeforeMb", (peakAll - rssBefore) / 1024)
                .put("conditions", conditions())
        )
        return true
    }

    /** A kB figure of /proc/self/status, or -1. */
    private fun status(key: String): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.substringAfter(':').trim().substringBefore(' ').toLong()
    }.getOrDefault(-1L)

    private fun summary(values: List<Double>): JSONObject {
        val s = values.sorted()
        if (s.isEmpty()) return JSONObject().put("n", 0)
        fun at(p: Double) = Stats.round1(s[((s.size - 1) * p).toInt()])
        return JSONObject().put("n", s.size).put("median", at(0.5)).put("p10", at(0.1)).put("p90", at(0.9))
            .put("min", Stats.round1(s.first())).put("max", Stats.round1(s.last()))
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Decode check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking decoding")
            .setOngoing(true)
            .build()
    }

    private fun log(o: JSONObject) {
        Log.i(TAG, o.toString())
        runCatching { out.appendText(o.toString() + "\n") }
    }

    private companion object {
        const val TAG = "DecodeCheck"
        const val CHANNEL = "decode_check"
        const val NOTIFICATION_ID = 7306
    }
}
