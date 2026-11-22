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
import com.infinity.comicreader.debug.ArchiveRuns.Engine
import com.infinity.comicreader.debug.ArchiveRuns.Via
import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.ChannelZipReader
import com.infinity.comicreader.reader.LibArchive
import com.infinity.comicreader.reader.LibArchiveReader
import com.infinity.comicreader.reader.RarMain
import com.infinity.comicreader.reader.SevenZipReader
import com.infinity.comicreader.util.Formats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * Debug and bench builds only: times reading archives by the app's reader for the format
 * (junrar for RAR) against libarchive (F119), and checks both return the same bytes. Only
 * the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.ArchiveCheckService
 *  [--es do time,verify,sustain,selftest,scan|all] [--es paths /path/a.cbr,/path/b.cbr] [--es engines junrar,libarchive]
 *  [--es via file|channel|both] [--ei rounds 3] [--ei random 20] [--el seed 1] [--ez warmup true]
 *  [--ei seconds 60] [--ei energyRounds 2] [--ei cooldown 30] [--ei idle 5] [--es dir /path --ei depth 3]`
 *
 * Archives are `paths`, named in the log by their place in it (`a1`, `a2`...). `via`: the
 * file (libarchive reads its descriptor), or a channel over it, as the app reads books on a
 * share (`LibArchive.Source` callbacks; junrar through a channel volume). `junrar` stands
 * for the app's reader of the format: junrar for RAR, Commons Compress for 7z, its own for ZIP.
 *
 * - time (default): per archive and `via`, a warm-up run per engine, then `rounds` interleaved
 *   (A B, B A...), each one line: `openMs` (opening and listing), `firstPageMs` (that plus the
 *   first page in the reader's order), `random` (`random` pages in an order fixed by `seed`,
 *   the same for both engines), `sequential` (every entry in archive order on a new reader,
 *   as a solid archive streams), process CPU time per step, the peak native heap and Java heap
 *   in use above the start (sampled every 5 ms), and the conditions (`valid` from a bench
 *   build with the app in front: see ResampleCheckService). Bytes are read as the app reads a
 *   page (`BitmapUtils.readCapped`).
 * - verify (default): both engines' listings compared (paths, sizes, order) and every entry's
 *   bytes; mismatched entries by index (`firstDiffers`).
 * - sustain (not in all): energy per page on battery alone ([EnergyMeter], as
 *   DecodeCheckService's sustain): per archive and engine, `energyRounds` interleaved rounds of
 *   a `cooldown` s pause, an `idle` s baseline, then entries read in archive order back to back
 *   for `seconds` (reopening at the end); then each engine's energy per page across valid rounds.
 * - selftest: a ZIP (stored and deflated, with an archive comment) and a 7z (LZMA2) made in the
 *   cache, read by libarchive through a descriptor and a channel against their bytes; and whether
 *   the ZIP comment survives (read by java.util.zip) while libarchive offers none (it has no API).
 * - scan (not in all): each archive under `dir`, `depth` folders down: its kind, RAR version
 *   and solid flag, to find test archives. It names files: keep its lines out of reports.
 *
 * Results go to the log under "ArchiveCheck", one JSON object per line, ending with "done",
 * and to `files/bench/archive.jsonl` in the app's external files.
 */
class ArchiveCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private class Plan(
        val engines: List<Engine>,
        val vias: List<Via>,
        val rounds: Int,
        val random: Int,
        val seed: Long,
        val warmup: Boolean,
        val energy: Energy
    )

    /** How `sustain` runs: [seconds] of reading per run, [rounds], a [cooldown] s pause and an [idle] s baseline before each. */
    private class Energy(val seconds: Int, val rounds: Int, val cooldown: Int, val idle: Int)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val what = (intent?.getStringExtra("do") ?: "time,verify").split(',').map { it.trim() }.toSet()
        val paths = intent?.getStringExtra("paths")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val askedEngines = intent?.getStringExtra("engines")?.split(',')
        val engines = askedEngines?.mapNotNull { Engine.of(it) }?.distinct() ?: Engine.entries
        val vias = Via.list(intent?.getStringExtra("via"))
        val plan = Plan(
            engines, vias ?: listOf(Via.FILE),
            intent?.getIntExtra("rounds", 3)?.coerceIn(1, 50) ?: 3,
            intent?.getIntExtra("random", 20)?.coerceIn(1, 1000) ?: 20,
            intent?.getLongExtra("seed", 1L) ?: 1L,
            intent?.getBooleanExtra("warmup", true) ?: true,
            Energy(
                intent?.getIntExtra("seconds", 60)?.coerceIn(3, 600) ?: 60, intent?.getIntExtra("energyRounds", 2)?.coerceIn(1, 20) ?: 2,
                intent?.getIntExtra("cooldown", 30)?.coerceIn(0, 600) ?: 30, intent?.getIntExtra("idle", 5)?.coerceIn(1, 600) ?: 5
            )
        )
        val dir = intent?.getStringExtra("dir")
        val depth = intent?.getIntExtra("depth", 3)?.coerceIn(0, 10) ?: 3
        thread(name = "archive-check") {
            try {
                out.parentFile?.mkdirs()
                if (askedEngines != null && engines.size != askedEngines.size) log(JSONObject().put("failed", "unreadable engines: $askedEngines"))
                if (vias == null) log(JSONObject().put("failed", "unreadable via"))
                val loaded = LibArchive.available
                log(
                    JSONObject().put("libarchive", if (loaded) LibArchive.nVersion() else JSONObject.NULL).put("loaded", loaded)
                        .put("conditions", conditions())
                )
                val all = "all" in what
                if ("selftest" in what || all) selftest()
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
                        if ("time" in what || all) time(file, label, via, plan)
                        if ("verify" in what || all) verify(file, label, via)
                    }
                    if ("sustain" in what) sustain(file, label, plan)
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

    private fun open(engine: Engine, file: File, via: Via): ArchiveReader {
        val kind = ArchiveKind.detect(file) ?: throw IllegalArgumentException("not an archive")
        fun channel() = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        return when (engine) {
            Engine.LIBARCHIVE -> if (via == Via.FILE) LibArchiveReader.of(file) else LibArchiveReader.of(channel())
            Engine.CURRENT -> when (via) {
                Via.FILE -> ArchiveReader.open(file, "UTF-8", kind) ?: throw IllegalArgumentException("no reader for $kind")
                Via.CHANNEL -> when (kind) {
                    ArchiveKind.RAR -> ChannelRar.reader(channel())
                    ArchiveKind.SEVEN_ZIP -> channel().let { c -> runCatching { SevenZipReader(c) }.getOrElse { c.close(); throw it } }
                    ArchiveKind.ZIP -> channel().let { c -> runCatching { ChannelZipReader(c, "UTF-8") }.getOrElse { c.close(); throw it } }
                    else -> throw IllegalArgumentException("no channel reader for $kind")
                }
            }
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
        if (plan.warmup) for (engine in plan.engines) log(run(engine, file, label, via, plan).put("warmup", true))
        for ((round, order) in ResampleRuns.interleaved(plan.engines, plan.rounds).withIndex()) {
            for (engine in order) log(run(engine, file, label, via, plan).put("round", round))
        }
    }

    /** One timed run of [engine] on [file]; a step that fails ends the run, named in `failed`. */
    private fun run(engine: Engine, file: File, label: String, via: Via, plan: Plan): JSONObject {
        val line = JSONObject().put("run", engine.key).put("archive", label).put("via", via.key)
        val before = conditions()
        System.gc()
        Runtime.getRuntime().gc()
        var step = "open"
        PeakMemory().use { memory ->
            try {
                val t0 = System.nanoTime()
                val c0 = Process.getElapsedCpuTime()
                open(engine, file, via).use { reader ->
                    val openNs = System.nanoTime() - t0
                    val openCpu = Process.getElapsedCpuTime() - c0
                    line.put("reader", reader.javaClass.simpleName).put("entries", reader.entries.size).put("solid", reader.solid ?: JSONObject.NULL)
                        .put("bytes", reader.entries.sumOf { it.size.coerceAtLeast(0) })
                        .put("openMs", ms(openNs)).put("openCpuMs", openCpu)
                    (reader as? LibArchiveReader)?.let { line.put("format", it.format ?: JSONObject.NULL) }
                    val pages = ArchiveRuns.pages(reader.entries)
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
                            .put("perPage", spread(each))
                    )
                }
                step = "sequential"
                open(engine, file, via).use { reader ->
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

    private fun verify(file: File, label: String, via: Via) {
        val line = JSONObject().put("verify", label).put("via", via.key)
        val t0 = System.nanoTime()
        try {
            open(Engine.CURRENT, file, via).use { a ->
                open(Engine.LIBARCHIVE, file, via).use { b ->
                    val diff = ArchiveRuns.compare(a.entries, b.entries)
                    line.put(
                        "listing", JSONObject().put("same", diff.same).put("onlyCurrent", diff.onlyA).put("onlyLibarchive", diff.onlyB)
                            .put("sizeDiffers", diff.sizeDiffers).put("sameOrder", diff.sameOrder)
                    )
                    val byPath = b.entries.associateBy { it.path }
                    var same = 0
                    var failed = 0
                    val differs = ArrayList<Int>()
                    for (entry in a.entries.sortedBy { it.index }) {
                        val other = byPath[entry.path] ?: continue
                        val x = runCatching { bytes(a, entry) }.getOrNull()
                        val y = runCatching { bytes(b, other) }.getOrNull()
                        when {
                            x == null || y == null -> failed++
                            x.contentEquals(y) -> same++
                            else -> differs += entry.index
                        }
                    }
                    line.put("entries", a.entries.size).put("same", same).put("differ", differs.size).put("failed", failed)
                        .put("firstDiffers", JSONArray(differs.take(5))).put("ok", diff.same && failed == 0 && differs.isEmpty())
                }
            }
        } catch (t: Throwable) {
            line.put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message}")
        }
        log(line.put("ms", ms(System.nanoTime() - t0)))
    }

    // ------------------------------------------------------------------ sustain

    private fun sustain(file: File, label: String, plan: Plan) {
        val energy = plan.energy
        val meter = EnergyMeter(this)
        val via = plan.vias.first()
        val runs = plan.engines.associateWith { EnergyRuns.Rounds() }
        for ((round, order) in ResampleRuns.interleaved(plan.engines, energy.rounds).withIndex()) {
            for (engine in order) {
                SystemClock.sleep(energy.cooldown * 1000L)
                val before = conditions()
                val heat = ResampleRuns.Heat().apply { see(meter.thermal()) }
                var error: String? = null
                val run = meter.measure(energy.idle, energy.seconds, heat) { until ->
                    var pages = 0
                    var made = EnergyMeter.Made(0)
                    try {
                        while (SystemClock.elapsedRealtime() < until) {
                            open(engine, file, via).use { reader ->
                                for (entry in reader.entries.sortedBy { it.index }) {
                                    if (SystemClock.elapsedRealtime() >= until) break
                                    bytes(reader, entry)
                                    pages++
                                    made = EnergyMeter.Made(pages)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        error = "${t.javaClass.simpleName}: ${t.message}"
                    }
                    made
                }
                val after = conditions()
                heat.see(meter.thermal())
                val valid = error == null && before.getBoolean("valid") && after.getBoolean("valid") && heat.cool
                runs.getValue(engine).add(run, valid)
                val line = JSONObject().put("sustain", engine.key).put("archive", label).put("via", via.key).put("round", round)
                meter.json(run, energy.idle).let { m -> m.keys().forEach { line.put(it, m.get(it)) } }
                log(
                    line.put("failed", error ?: JSONObject.NULL).put("before", before).put("after", after).put("thermalPeak", heat.peak)
                        .put("valid", valid)
                )
            }
        }
        for (engine in plan.engines) log(meter.summary(engine.key, runs.getValue(engine)).put("archive", label))
    }

    // ------------------------------------------------------------------ selftest

    private fun selftest() {
        if (!LibArchive.available) {
            log(JSONObject().put("selftest", "all").put("ok", false).put("error", "libarchive not loaded"))
            return
        }
        val dir = File(cacheDir, "archive-check").apply { mkdirs() }
        val entries = ArchiveRuns.selfTestEntries()
        val comment = "Emanata archive check"
        val zip = File(dir, "check.zip").also { ArchiveRuns.writeZip(it, entries, comment) }
        val sevenZ = File(dir, "check.7z").also { ArchiveRuns.write7z(it, entries) }
        // Whether libarchive read the commented ZIP whole by every route.
        var zipRead = true
        for (archive in listOf(zip, sevenZ)) {
            for (via in Via.entries) {
                val line = JSONObject().put("selftest", archive.extension).put("via", via.key)
                try {
                    open(Engine.LIBARCHIVE, archive, via).use { reader ->
                        reader as LibArchiveReader
                        val byPath = reader.entries.associateBy { it.path }
                        var same = 0
                        // Backward too: each read before the last one read reopens the archive.
                        for ((name, expected) in entries.asReversed() + entries) {
                            val entry = byPath[name] ?: continue
                            if (bytes(reader, entry).contentEquals(expected) && reader.bytes(entry).contentEquals(expected)) same++
                        }
                        line.put("format", reader.format ?: JSONObject.NULL).put("entries", reader.entries.size).put("same", same)
                            .put("ok", reader.entries.size == entries.size && same == entries.size * 2)
                    }
                } catch (t: Throwable) {
                    line.put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message}")
                }
                if (archive == zip && !line.optBoolean("ok")) zipRead = false
                log(line)
            }
        }
        val javaComment = runCatching { ZipFile(zip).use { it.comment } }.getOrNull()
        log(
            JSONObject().put("zipComment", JSONObject().put("written", javaComment == comment).put("libarchiveReadsArchive", zipRead)
                .put("libarchiveApi", false).put("note", "libarchive has no call that returns or writes a ZIP archive comment"))
        )
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
