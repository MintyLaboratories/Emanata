package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.debug.ArchiveRuns.Via
import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.ChannelZipReader
import com.infinity.comicreader.reader.LibArchive
import com.infinity.comicreader.reader.RarMain
import com.infinity.comicreader.reader.RarReader
import com.infinity.comicreader.reader.SevenZipReader
import com.infinity.comicreader.util.Formats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import kotlin.concurrent.thread

/**
 * Debug and bench builds only: times the app's archive readers on real archives and checks they
 * return the same bytes whatever the route and order (RAR through libarchive, F119). Only the
 * shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.ArchiveCheckService
 *  [--es do time,verify,scan] [--es paths /path/a.cbr,/path/b.cbr] [--es via file|channel|both]
 *  [--ei rounds 3] [--ei random 20] [--el seed 1] [--ez warmup true] [--es pages images|all] [--es dir /path --ei depth 3]`
 *
 * Archives are `paths`, named in the log by their place in it (`a1`, `a2`...). `via`: the file,
 * or a channel over it, as the app reads books on a share. For RAR both are one route
 * ([RarReader] over a `FileChannel`): `channel` repeats `file`, and a share's own channel
 * ([com.infinity.comicreader.net.RemoteChannel]) is not exercised.
 *
 * - time (default): per archive and `via`, a warm-up run, then `rounds` runs, each one line:
 *   `openMs` (opening and listing), `firstPageMs` (that plus the first page in the reader's
 *   order), `random` (`random` pages in an order fixed by `seed`; each page's ms in `eachMs`, by
 *   `order`), `sequential` (every entry in archive order on a new reader, as a solid archive
 *   streams), process CPU time per step, the peak native heap and Java heap in use above the start
 *   (sampled every 5 ms), and the conditions (`valid` from a bench build with the app in front:
 *   see ResampleCheckService). Bytes are read as the app reads a page (`BitmapUtils.readCapped`).
 *   `pages all` counts every entry as a page, not only images.
 * - verify (default): every entry's CRC-32 read in archive order from the file, against a new
 *   reader by `via` reading them backward, then in an order fixed by `seed`: listings compared
 *   (paths, sizes, order), mismatched entries by index (`firstDiffers`); for RAR, how many reads
 *   went straight to their entry (`spliced`) and how many had to walk from the start (`unspliced`).
 * - scan (not in default): each archive under `dir`, `depth` folders down: its kind, RAR version
 *   and solid flag, to find test archives. It names files: keep its lines out of reports.
 *
 * Results go to the log under "ArchiveCheck", one JSON object per line, ending with "done",
 * and to `files/bench/archive.jsonl` in the app's external files.
 */
class ArchiveCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private class Plan(val vias: List<Via>, val rounds: Int, val random: Int, val seed: Long, val warmup: Boolean, val anyEntry: Boolean)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = (intent?.getStringExtra("do") ?: "time,verify").split(',').map { it.trim() }.toSet()
        val paths = intent?.getStringExtra("paths")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val vias = Via.list(intent?.getStringExtra("via"))
        val plan = Plan(
            vias ?: listOf(Via.FILE),
            intent?.getIntExtra("rounds", 3)?.coerceIn(1, 50) ?: 3,
            intent?.getIntExtra("random", 20)?.coerceIn(1, 1000) ?: 20,
            intent?.getLongExtra("seed", 1L) ?: 1L,
            intent?.getBooleanExtra("warmup", true) ?: true,
            intent?.getStringExtra("pages") == "all"
        )
        val dir = intent?.getStringExtra("dir")
        val depth = intent?.getIntExtra("depth", 3)?.coerceIn(0, 10) ?: 3
        thread(name = "archive-check") {
            try {
                out.parentFile?.mkdirs()
                if (vias == null) log(JSONObject().put("failed", "unreadable via"))
                val loaded = LibArchive.loaded
                log(
                    JSONObject().put("libarchive", if (loaded) LibArchive.nVersion() else JSONObject.NULL).put("loaded", loaded)
                        .put("conditions", conditions())
                )
                if ("scan" in what) dir?.let { scan(File(it), depth) } ?: log(JSONObject().put("failed", "scan needs dir"))
                for ((i, path) in paths.withIndex()) {
                    val file = File(path)
                    val label = "a${i + 1}"
                    if (!file.isFile) {
                        log(JSONObject().put("archive", label).put("failed", "not a file").put("exists", file.exists()))
                        continue
                    }
                    log(describe(file, label))
                    for (via in plan.vias) {
                        if ("time" in what) time(file, label, via, plan)
                        if ("verify" in what) verify(file, label, via, plan.seed)
                    }
                }
            } catch (t: Throwable) {
                log(JSONObject().put("failed", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                Log.i(TAG, "done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private val out: File get() = File(getExternalFilesDir(null), "bench/archive.jsonl")

    private fun conditions(): JSONObject {
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return JSONObject().put("cpuset", cpuset).put("debuggable", debuggable).put("valid", !debuggable && cpuset == "/top-app")
            .put("state", ProcStats.now(this, memory = false).toJson())
    }

    // ------------------------------------------------------------------ readers

    /** The app's reader of [file], from the file or through a channel over it, as books on a share are read. */
    private fun open(file: File, via: Via): ArchiveReader {
        val kind = ArchiveKind.detect(file) ?: throw IllegalArgumentException("not an archive")
        if (via == Via.FILE) return ArchiveReader.open(file, "UTF-8", kind) ?: throw IllegalArgumentException("no reader for $kind")
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        return try {
            when (kind) {
                ArchiveKind.RAR -> RarReader.of(channel)
                ArchiveKind.SEVEN_ZIP -> SevenZipReader(channel)
                ArchiveKind.ZIP -> ChannelZipReader(channel, "UTF-8")
                else -> throw IllegalArgumentException("no channel reader for $kind")
            }
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    /** The bytes of [entry] as the app reads a page. */
    private fun bytes(reader: ArchiveReader, entry: ArchiveEntry): ByteArray = reader.read(entry) { BitmapUtils.readCapped(it, entry.size) }

    /** The archive's kind, size and RAR header, by the app's own checks. */
    private fun describe(file: File, label: String): JSONObject {
        val head = file.inputStream().use { input -> ByteArray(RarMain.HEAD_BYTES).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        val rar = RarMain.of(head)
        return JSONObject().put("archive", label).put("fileBytes", file.length()).put("kind", ArchiveKind.detect(file)?.name ?: JSONObject.NULL)
            .put("rar", rar?.version ?: JSONObject.NULL).put("solid", rar?.solid ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------------ time

    private fun time(file: File, label: String, via: Via, plan: Plan) {
        if (plan.warmup) log(run(file, label, via, plan).put("warmup", true))
        repeat(plan.rounds) { round -> log(run(file, label, via, plan).put("round", round)) }
    }

    /** One timed run on [file]; a step that fails ends the run, named in `failed`. */
    private fun run(file: File, label: String, via: Via, plan: Plan): JSONObject {
        val line = JSONObject().put("run", "time").put("archive", label).put("via", via.key)
        val before = conditions()
        System.gc()
        Runtime.getRuntime().gc()
        var step = "open"
        PeakMemory().use { memory ->
            try {
                val t0 = System.nanoTime()
                val c0 = Process.getElapsedCpuTime()
                open(file, via).use { reader ->
                    val openNs = System.nanoTime() - t0
                    val openCpu = Process.getElapsedCpuTime() - c0
                    line.put("reader", reader.javaClass.simpleName).put("entries", reader.entries.size).put("solid", reader.solid ?: JSONObject.NULL)
                        .put("bytes", reader.entries.sumOf { it.size.coerceAtLeast(0) })
                        .put("openMs", ms(openNs)).put("openCpuMs", openCpu)
                    val pages = ArchiveRuns.pages(reader.entries, plan.anyEntry)
                    line.put("pages", pages.size)
                    if (pages.isEmpty()) throw IllegalStateException("no pages")
                    step = "firstPage"
                    bytes(reader, pages.first())
                    line.put("firstPageMs", ms(System.nanoTime() - t0)).put("firstPageCpuMs", Process.getElapsedCpuTime() - c0)
                    step = "random"
                    val order = ArchiveRuns.randomOrder(pages.size, plan.random, plan.seed)
                    val each = ArrayList<Double>()
                    val r0 = System.nanoTime()
                    val rc0 = Process.getElapsedCpuTime()
                    for (i in order) {
                        val p0 = System.nanoTime()
                        bytes(reader, pages[i])
                        each += ms(System.nanoTime() - p0)
                    }
                    line.put(
                        "random", JSONObject().put("pages", order.size).put("ms", ms(System.nanoTime() - r0)).put("cpuMs", Process.getElapsedCpuTime() - rc0)
                            .put("perPage", spread(each)).put("order", JSONArray(order)).put("eachMs", JSONArray(each))
                    )
                }
                step = "sequential"
                open(file, via).use { reader ->
                    val s0 = System.nanoTime()
                    val sc0 = Process.getElapsedCpuTime()
                    var total = 0L
                    for (entry in reader.entries.sortedBy { it.index }) total += bytes(reader, entry).size
                    val ns = System.nanoTime() - s0
                    line.put(
                        "sequential", JSONObject().put("entries", reader.entries.size).put("bytes", total).put("ms", ms(ns))
                            .put("cpuMs", Process.getElapsedCpuTime() - sc0).put("mbPerS", Stats.round1(total / 1048576.0 / (ns / 1e9)))
                    )
                }
            } catch (t: Throwable) {
                line.put("failed", "$step: ${t.javaClass.simpleName}: ${t.message}")
            }
            line.put("peakNativeMb", Stats.round1(memory.peakNative / 1048576.0)).put("peakJavaMb", Stats.round1(memory.peakJava / 1048576.0))
        }
        val after = conditions()
        return line.put("valid", before.getBoolean("valid") && after.getBoolean("valid")).put("before", before).put("after", after)
    }

    private fun ms(ns: Long): Double = Stats.round1(ns / 1e6)

    private fun spread(values: List<Double>): JSONObject {
        val s = values.sorted()
        if (s.isEmpty()) return JSONObject().put("n", 0)
        return JSONObject().put("n", s.size).put("median", s[(s.size - 1) / 2]).put("min", s.first()).put("max", s.last())
    }

    /**
     * The native heap and Java heap in use above what they held when made, at their highest,
     * sampled every [SAMPLE_MS] ms until closed.
     */
    private class PeakMemory : AutoCloseable {
        private val nativeBase = Debug.getNativeHeapAllocatedSize()
        private val javaBase = javaUsed()

        @Volatile
        var peakNative = 0L
            private set

        @Volatile
        var peakJava = 0L
            private set

        @Volatile
        private var running = true
        private val sampler = thread(name = "archive-memory") {
            while (running) {
                sample()
                SystemClock.sleep(SAMPLE_MS)
            }
        }

        private fun sample() {
            peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize() - nativeBase)
            peakJava = maxOf(peakJava, javaUsed() - javaBase)
        }

        override fun close() {
            running = false
            sampler.join()
            sample()
        }

        private companion object {
            const val SAMPLE_MS = 5L

            fun javaUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        }
    }

    // ------------------------------------------------------------------ verify

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun verify(file: File, label: String, via: Via, seed: Long) {
        val line = JSONObject().put("verify", label).put("via", via.key)
        val t0 = System.nanoTime()
        try {
            val reference = open(file, Via.FILE).use { r -> r.entries to r.entries.sortedBy { it.index }.associate { it.index to runCatching { crc(bytes(r, it)) }.getOrNull() } }
            open(file, via).use { reader ->
                val diff = ArchiveRuns.compare(reference.first, reader.entries)
                line.put(
                    "listing", JSONObject().put("same", diff.same).put("onlyFile", diff.onlyA).put("onlyVia", diff.onlyB)
                        .put("sizeDiffers", diff.sizeDiffers).put("sameOrder", diff.sameOrder)
                )
                val byIndex = reader.entries.sortedBy { it.index }
                val order = byIndex.asReversed() + ArchiveRuns.randomOrder(byIndex.size, byIndex.size, seed).map { byIndex[it] }
                var same = 0
                var failed = 0
                val differs = ArrayList<Int>()
                for (entry in order) {
                    val expected = reference.second[entry.index]
                    val got = runCatching { crc(bytes(reader, entry)) }.getOrNull()
                    when {
                        expected == null || got == null -> failed++
                        expected == got -> same++
                        else -> differs += entry.index
                    }
                }
                line.put("reads", order.size).put("same", same).put("differ", differs.size).put("failed", failed)
                    .put("firstDiffers", JSONArray(differs.distinct().take(5)))
                var spliceOk = true
                (reader as? RarReader)?.let {
                    line.put("spliced", it.spliced).put("unspliced", it.unspliced)
                    spliceOk = it.unspliced == 0
                }
                line.put("ok", diff.same && failed == 0 && differs.isEmpty() && spliceOk)
            }
        } catch (t: Throwable) {
            line.put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message}")
        }
        log(line.put("ms", ms(System.nanoTime() - t0)))
    }

    // ------------------------------------------------------------------ scan

    private fun scan(root: File, depth: Int) {
        val counts = sortedMapOf<String, Int>()
        var level = listOf(root)
        repeat(depth + 1) {
            val next = ArrayList<File>()
            for (d in level) {
                val listed = d.listFiles()?.sortedBy { it.name } ?: continue
                for (f in listed) {
                    if (f.isDirectory) {
                        if (!f.name.startsWith(".")) next += f
                        continue
                    }
                    if (!Formats.isArchiveName(f.name)) continue
                    val path = f.relativeTo(root).path
                    val line = runCatching { describe(f, path) }.getOrElse { JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message}") }
                        .apply { remove("archive") }.put("scan", path)
                    log(line)
                    val key = line.optString("kind", "?") + (if (line.has("rar") && !line.isNull("rar")) line.getInt("rar").toString() + (if (line.optBoolean("solid")) "-solid" else "") else "")
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
            level = next
        }
        log(JSONObject().put("scanned", JSONObject(counts as Map<*, *>)))
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Archive check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking archives")
            .setOngoing(true)
            .build()
    }

    private fun log(o: JSONObject) {
        Log.i(TAG, o.toString())
        runCatching { out.appendText(o.toString() + "\n") }
    }

    private companion object {
        const val TAG = "ArchiveCheck"
        const val CHANNEL = "archive_check"
        const val NOTIFICATION_ID = 7311
    }
}
