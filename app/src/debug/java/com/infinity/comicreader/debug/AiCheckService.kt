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
import android.graphics.BitmapFactory
import android.net.Uri
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
import com.infinity.comicreader.ai.AiConditions
import com.infinity.comicreader.ai.AiPages
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.Fallback
import com.infinity.comicreader.ai.GlesShaders
import com.infinity.comicreader.ai.GrayPages
import com.infinity.comicreader.ai.ImportState
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloader
import com.infinity.comicreader.ai.ModelFiles
import com.infinity.comicreader.ai.ModelImports
import com.infinity.comicreader.ai.ModelMemory
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.PageLimits
import com.infinity.comicreader.ai.ReaderAi
import com.infinity.comicreader.ai.Session
import com.infinity.comicreader.ai.Sessions
import com.infinity.comicreader.ai.ShaderCatalog
import com.infinity.comicreader.ai.ShaderSessions
import com.infinity.comicreader.ai.TileChoices
import com.infinity.comicreader.ai.TileEngine
import com.infinity.comicreader.ai.TilePlan
import com.infinity.comicreader.ai.TileSizing
import com.infinity.comicreader.ai.TileTiming
import com.infinity.comicreader.ai.TileTuning
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
 *  --es model <path to .param, its .bin beside it> [--es do check|time|limits|sustain|all] [--es engines gpu,cpu]
 *  [--es tiles 128,192,256,384, or auto for the size it chose] [--es size 1000x1500] [--es small 300x420] [--ei reps 3] [--ei cooldown 10] [--ef cool <headroom>] [--ez fp16 true]
 *  [--ei threads 4] [--ei seconds 300] [--ez tune false]`
 *
 * or, for every model in a folder: `--es do survey --es dir <folder, searched for .param files> [--es size 1000x1500]
 *  [--ei cap 120] [--ef cool 0.85] [--ez fp16 true] [--ei threads 4]`
 *
 * or, for catalog models: `--es do download --es ids <id,id,…> [--ei cut 0] [--ez keep false]`
 *
 * or, to import a model as Models does: `--es do import --es model <.param> [--es bin <.bin, else beside it>]
 *  [--es name <name, to take it in>] [--es step artifacts|descreen|upscale]`
 *
 * or, to check gray pages stay gray: `--es do gray [--es ids <catalog id,id,…, else both descreen models>
 *  | --es model <.param>] [--es engines gpu,cpu] [--es size 1000x1500] [--ef cool <headroom>] [--ez fp16 true] [--ei threads 4]`
 *
 * - open: per engine, the time to open, measure and check the model, its geometry, tile size
 *   and precision (the checks themselves go to the log under "AiSessions"). With `tune`, GPU
 *   tiles are chosen as the reader chooses a catalog model's: timed among
 *   [TileSizing.CANDIDATES] ([TileTiming]), from a store cleared first (`tuned`: the choice kept).
 * - check: tiled output at each size, the chosen one included, against one tile covering the
 *   whole of a `small` page on the CPU in fp32 (seams, plus fp16 rounding on the GPU), and the
 *   GPU's against the CPU's.
 * - time: per engine and tile size, `reps` pages of `size` after one warm-up: time per tile
 *   and per page, peak resident and graphics memory, thermal status and headroom. Given
 *   `cool`, first waits for the thermal headroom to fall below it, up to [COOL_TIME_MAX_S].
 * - limits: a page whose time runs out falls back to exactly the Lanczos3 page; a page
 *   canceled after three tiles stops within one; a sample of each output saved as PNG.
 * - sustain (not part of all): pages of `size` back to back for `seconds`, through the gate
 *   reading heat and battery saver ([AiConditions]), each with its time, thermal status and
 *   headroom, until the time is up or the gate stops a page.
 * - download: per catalog model, deletes it from the app's store, downloads it (stopping once
 *   after `cut` bytes when given, then resuming), checks it is installed, opens it and runs one
 *   page of `size` within the default page time; deletes it again unless `keep`.
 * - import: copies and checks the pair ([ModelImports]), with the time it took, the device's
 *   model memory and the peak resident memory; given a name, takes a fit model in at `step`
 *   (else the first its scale suits).
 * - survey: per model, how it opens on the GPU within the device's model memory
 *   ([ModelMemory]; or why not, with the memory it would need) and one page of `size` at the
 *   tile size it chose, given at most `cap` seconds; a page that would take longer stops early
 *   on its pace ([TileEngine]) and reports its time estimated from the tiles run. Waits for
 *   the thermal headroom to fall below `cool` before each model, up to [COOL_MAX_S].
 * - gray: per model (installed from the catalog, or `model`) and engine, a black-and-white
 *   screentone page of `size` ([GrayRuns.screentone]) through the model twice. `raw`: its
 *   output with the collapse off, as 8-bit channels (clamped, so overshoot tint is clipped):
 *   the color the model adds ([GrayRuns.spread]). `shown`: the page as the reader shows it at
 *   3/4 size, through [AiPages.chain], which finds the page gray and collapses each model's
 *   output before resampling; `chainRan` 0 or `chainFallback` UNNEEDED means it skipped the
 *   model. A line with `failed` is void: the GPU asked but the CPU opened, the raw run
 *   collapsed or fell back, or the chain skipped the model ([GrayRuns.runProblem]). Waits for
 *   the thermal headroom to fall below `cool` (default [COOL_HEADROOM]) before each run, up to
 *   [COOL_TIME_MAX_S]; `rawMs` is not a benchmark. Saves the page, and each raw output holding
 *   color, as PNG.
 *
 * or, for shader chains ([ShaderModel], downloaded first when missing): `--es do shaders --es ids <catalog id,id,…>
 *  [--es sizes 1000x1500,2000x3000] [--es tiles <sizes, else the one it chose>] [--ei reps 3] [--ef cool <headroom>]
 *  [--ez save false] [--ez off false] [--es input <png,png,…>]`
 *
 * - shaders: per chain, how it opens on the GPU within the device's model memory (or why not):
 *   open time (compile, measure, seam check), geometry and largest tile; per page size and
 *   tile size (by default the page's own, as the reader picks it), `reps` synthetic pages
 *   after one warm-up, as `time` logs them; the page at its own tile against the same page in
 *   256 or 192 px tiles (`seams`: 0 when tiles join exactly); a page canceled after three tiles (`stopMs`). With `save`, each page and output
 *   as PNG (`shader-page-WxH.png`, `shader-<id>-WxH.png`) for comparison with a PC reference.
 *   With `off`, the GPU path is then held off while the first chain opens again (`refusal`
 *   RUNTIME: its pages show without it), and back on after. With `input` (image files), each
 *   chain runs on those images instead of synthetic pages, once each at its own tile, and
 *   saves its output as `shader-<id>-<image name>.png`, for comparison with a PC reference
 *   of the same shaders on the same image.
 *
 * or, for the shader runner against the CPU: `--es do shaderref [--es sizes 1000x1500,…] [--ez save false]`
 *
 * - shaderref: [ShaderReference]'s synthetic chain on synthetic pages, the GPU's output in
 *   tiles against the same arithmetic on the CPU in fp32 (`psnr`, `maxDiff`): orientation,
 *   texture offsets, depth-to-space, bilinear sampling, channels and stages as the runner
 *   should have them. With `save`, both as PNG.
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
        val seconds = intent?.getIntExtra("seconds", 300)?.coerceIn(10, 3600) ?: 300
        val dir = intent?.getStringExtra("dir")?.let(::File)
        val cap = intent?.getIntExtra("cap", 120)?.coerceIn(5, 3600) ?: 120
        val cool = intent?.getFloatExtra("cool", COOL_HEADROOM)?.coerceIn(0.1f, 1f) ?: COOL_HEADROOM
        val timeCool = if (intent?.hasExtra("cool") == true) cool else null
        val tune = intent?.getBooleanExtra("tune", false) ?: false
        thread(name = "ai-check") { Calibrator.hold {
            try {
                out.parentFile?.mkdirs()
                if (what == "download") {
                    require(size.size == 2) { "unreadable size" }
                    val ids = intent?.getStringExtra("ids").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    download(ids, intent?.getIntExtra("cut", 0) ?: 0, intent?.getBooleanExtra("keep", false) ?: false, size[0], size[1], fp16, threads)
                    return@hold
                }
                if (what == "import") {
                    require(param != null && param.isFile) { "no model at $param" }
                    val bin = intent?.getStringExtra("bin")?.let(::File) ?: File(param.path.removeSuffix(".param") + ".bin")
                    importModel(param, bin, intent?.getStringExtra("name"), intent?.getStringExtra("step"))
                    return@hold
                }
                if (what == "gray") {
                    require(size.size == 2) { "unreadable size" }
                    val ids = intent?.getStringExtra("ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: GrayRuns.DESCREEN_IDS
                    gray(ids, param, engines, size[0], size[1], fp16, threads, cool)
                    return@hold
                }
                if (what == "shaders") {
                    val ids = intent?.getStringExtra("ids").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val pages = (intent?.getStringExtra("sizes") ?: "1000x1500").split(',').map { s ->
                        s.trim().split('x').mapNotNull { it.toIntOrNull() }.also { require(it.size == 2) { "unreadable size $s" } }.let { it[0] to it[1] }
                    }
                    val asked = intent?.getStringExtra("tiles")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
                    val inputs = intent?.getStringExtra("input")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(::File).orEmpty()
                    shaders(ids, pages, asked, reps, timeCool, intent?.getBooleanExtra("save", false) ?: false, intent?.getBooleanExtra("off", false) ?: false, inputs)
                    return@hold
                }
                if (what == "shaderref") {
                    val pages = (intent?.getStringExtra("sizes") ?: "1000x1500").split(',').map { s ->
                        s.trim().split('x').mapNotNull { it.toIntOrNull() }.also { require(it.size == 2) { "unreadable size $s" } }.let { it[0] to it[1] }
                    }
                    shaderReference(pages, intent?.getBooleanExtra("save", false) ?: false)
                    return@hold
                }
                if (what == "survey") {
                    require(dir != null && dir.isDirectory) { "no folder at $dir" }
                    require(size.size == 2) { "unreadable size" }
                    survey(dir, size[0], size[1], cap, cool, fp16, threads)
                    return@hold
                }
                require(param != null && param.isFile) { "no model at $param" }
                require(size.size == 2 && small.size == 2) { "unreadable size" }
                val files = ModelFiles(param, File(param.path.removeSuffix(".param") + ".bin"))
                log(
                    JSONObject().put("model", param.name).put("loaded", LibNcnn.available)
                        .put("gpu", LibNcnn.gpu()?.let { JSONObject().put("name", it.name).put("budgetMb", it.budgetMb).put("fp16", it.fp16).put("maxBufferMb", it.maxBufferBytes shr 20) })
                        .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else JSONObject.NULL)
                        .put("threads", threads).put("graphicsMb", graphicsKb() / 1024).put("rssMb", rssKb() / 1024)
                        .put("conditions", conditions()).put("state", state())
                )
                for (gpu in engines) {
                    val t0 = SystemClock.elapsedRealtime()
                    val tuning = if (tune && gpu) tuningFor(param) else null
                    val session = Sessions.open(LibNcnn, files, threads, gpu = gpu, fp16 = fp16, tuning = tuning)
                    val openMs = SystemClock.elapsedRealtime() - t0
                    if (session == null) {
                        log(JSONObject().put("open", if (gpu) "gpu" else "cpu").put("failed", true))
                        continue
                    }
                    session.use { s ->
                        val g = s.geometry
                        log(
                            JSONObject().put("open", if (gpu) "gpu" else "cpu").put("onGpu", s.model.onGpu).put("ms", openMs)
                                .put("scale", g.scale).put("trim", g.trim).put("context", g.context).put("margin", g.margin).put("tile", s.size).put("fp16", s.fp16)
                                .put("tuned", tuning?.let { t -> keptChoice(t, s.fp16) } ?: JSONObject.NULL)
                        )
                        if (what == "check" || what == "all") seams(s, files, threads, small[0], small[1])
                        if (what == "time" || what == "all") time(s, tiles.ifEmpty { listOf(s.size) }, size[0], size[1], reps, cooldown, timeCool)
                        if (what == "limits" || what == "all") limits(s, size[0], size[1])
                        if (what == "sustain") sustain(s, size[0], size[1], seconds)
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

    /** Tile tuning for the model of [param] as the reader tunes a catalog model's, from a store cleared first, so it times afresh. */
    private fun tuningFor(param: File): TileTuning {
        val dir = File(out.parentFile, "tile-choices").apply { deleteRecursively() }
        return TileTuning(TileSizing.CANDIDATES, TileChoices(dir), TileTuning.keyOf(param.name))
    }

    /** What [tuning] keeps for the GPU in fp16 or fp32 ([fp16]): the size chosen and the largest that may run. */
    private fun keptChoice(tuning: TileTuning, fp16: Boolean): JSONObject {
        val c = tuning.choices.get("${LibNcnn.gpu()?.name}|${if (fp16) "fp16" else "fp32"}|${tuning.key}")
        return JSONObject().put("size", c.size).put("ceiling", if (c.ceiling == Int.MAX_VALUE) JSONObject.NULL else c.ceiling)
    }

    // ------------------------------------------------------------------ check

    /**
     * Tiled output at each size against one tile covering the whole page on the CPU in fp32
     * (no GPU buffer limit, no fp16 rounding): the largest and mean difference.
     */
    private fun seams(s: Session, files: ModelFiles, threads: Int, w: Int, h: Int) {
        val page = SyntheticPage.draw(w, h)
        val ref = Sessions.open(LibNcnn, files, threads, gpu = false)?.use { cpu ->
            val whole = Session(cpu.model, cpu.geometry, max(page.width, page.height) + 2 * cpu.geometry.margin)
            TileEngine.run(whole, page, UNLIMITED).bitmap?.let { b -> pixels(b).also { b.recycle() } }
        } ?: return log(JSONObject().put("seams", "reference failed"))
        for (size in (listOf(64, 96, 128, 192, 256) + s.size).distinct().filter { TilePlan.fits(it, s.geometry) }) {
            val got = TileEngine.run(Session(s.model, s.geometry, size), page).bitmap ?: continue
            val d = diff(ref, pixels(got))
            got.recycle()
            log(JSONObject().put("seams", if (s.model.onGpu) "gpu" else "cpu").put("fp16", s.fp16).put("tile", size).put("maxDiff", d.max).put("meanDiff", Stats.round1(d.mean * 100) / 100).put("over2", d.over2))
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

    private fun time(s: Session, sizes: List<Int>, w: Int, h: Int, reps: Int, cooldown: Int, cool: Float?) {
        val page = SyntheticPage.draw(w, h)
        for (size in sizes.filter { TilePlan.fits(it, s.geometry) }) {
            if (cooldown > 0) Thread.sleep(cooldown * 1000L)
            val waitedS = cool?.let { coolDown(it, COOL_TIME_MAX_S) } ?: 0L
            val session = Session(s.model, s.geometry, size)
            val before = state()
            val rssBefore = rssKb()
            val w0 = SystemClock.elapsedRealtime()
            TileEngine.run(session, page, UNLIMITED).bitmap?.recycle()
            val warmMs = SystemClock.elapsedRealtime() - w0
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
                JSONObject().put("time", if (s.model.onGpu) "gpu" else "cpu").put("tile", size).put("page", "${w}x$h").put("tiles", tiles).put("cooledS", waitedS)
                    .put("warmUpMs", warmMs).put("pageMs", Stats.summary(pages)).put("tileMs", Stats.summary(tileMs))
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
        // Shown 1.5 times larger, as a low-resolution page on a large screen: the model runs.
        val plan = Plan(w * 3 / 2, h * 3 / 2, 0.0, 0.0, w.toDouble(), h.toDouble())
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

    // ------------------------------------------------------------------ sustain

    private fun sustain(s: Session, w: Int, h: Int, seconds: Int) {
        val page = SyntheticPage.draw(w, h)
        val gate = AiConditions.gate(this)
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        val t0 = SystemClock.elapsedRealtime()
        var pages = 0
        log(JSONObject().put("sustain", if (s.model.onGpu) "gpu" else "cpu").put("start", state()))
        while (SystemClock.elapsedRealtime() < end) {
            val p0 = SystemClock.elapsedRealtime()
            val run = TileEngine.run(s, page, UNLIMITED, gate = gate)
            run.bitmap?.recycle()
            pages++
            log(
                JSONObject().put("page", pages).put("atS", (SystemClock.elapsedRealtime() - t0) / 1000)
                    .put("ms", SystemClock.elapsedRealtime() - p0).put("tiles", run.stats.tiles)
                    .put("fallback", run.fallback?.name ?: JSONObject.NULL).put("state", state())
            )
            if (run.fallback != null) break
        }
        log(JSONObject().put("sustained", pages).put("seconds", (SystemClock.elapsedRealtime() - t0) / 1000).put("end", state()))
        page.recycle()
    }

    // ------------------------------------------------------------------ download

    private fun download(ids: List<String>, cut: Int, keep: Boolean, w: Int, h: Int, fp16: Boolean, threads: Int) {
        val store = ModelStore.of(this)
        val page = SyntheticPage.draw(w, h)
        for (id in ids) {
            val m = ModelCatalog.byId(id) ?: run { log(JSONObject().put("download", id).put("failed", "not in the catalog")); null } ?: continue
            store.delete(m)
            val downloader = ModelDownloader(store)
            val result = JSONObject().put("download", id).put("bytes", m.bytes)
            try {
                if (cut > 0) {
                    var held = 0L
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        downloader.download(m, canceled = { held >= cut }) { done, _ -> held = done }
                    } catch (e: CancellationException) {
                        result.put("stoppedAt", store.bytesHeld(m)).put("stoppedMs", SystemClock.elapsedRealtime() - t0)
                    }
                }
                val t1 = SystemClock.elapsedRealtime()
                downloader.download(m)
                result.put("ms", SystemClock.elapsedRealtime() - t1).put("installed", store.installed(m))
                    .put("sha256", m.files.all { ModelDownloader.sha256(java.io.File(store.dir(m), it.name)) == it.sha256 })
                val session = Sessions.open(LibNcnn, store.files(m)!!, threads, gpu = true, fp16 = fp16)
                if (session == null) {
                    result.put("opened", false)
                } else session.use { s ->
                    val run = TileEngine.run(s, page)
                    run.bitmap?.recycle()
                    result.put("opened", true).put("onGpu", s.model.onGpu).put("pageMs", run.stats.totalMs)
                        .put("fallback", run.fallback?.name ?: JSONObject.NULL)
                }
            } catch (e: Exception) {
                result.put("failed", "${e.javaClass.simpleName}: ${e.message}")
            }
            if (!keep) store.delete(m)
            log(result)
        }
        page.recycle()
    }

    // ------------------------------------------------------------------ import

    private fun importModel(param: File, bin: File, name: String?, step: String?) {
        val memory = ModelMemory.of(this)
        val reset = resetPeakRss()
        val t0 = SystemClock.elapsedRealtime()
        val state = ModelImports.importNow(this, listOf(Uri.fromFile(param), Uri.fromFile(bin)))
        val ms = SystemClock.elapsedRealtime() - t0
        val line = JSONObject().put("import", param.name).put("ms", ms).put("memoryMb", memory shr 20).put("peakRssMb", peakRssKb() / 1024).put("peakReset", reset)
        when (state) {
            is ImportState.Fit -> {
                line.put("fit", true).put("scale", state.scale).put("pageMs", state.pageMs)
                    .put("headroom", if (state.headroom.isNaN()) JSONObject.NULL else Stats.round1(state.headroom * 100.0) / 100)
                if (name != null) {
                    val at = AiStep.entries.firstOrNull { it.key == step } ?: ModelImports.steps(state.scale).first()
                    val m = ModelImports.saveNow(ModelStore.of(this), state, name, at)
                    line.put("saved", m.id).put("step", m.step.key)
                } else {
                    ModelStore.of(this).staging(state.id).deleteRecursively()
                }
            }
            is ImportState.Refused -> line.put("fit", false).put("refusal", state.why.name).put("neededMb", state.bytes shr 20)
                .put("limitMb", state.limit shr 20).put("pageMs", state.pageMs)
            else -> line.put("state", state.toString())
        }
        log(line)
    }

    // ------------------------------------------------------------------ survey

    private fun survey(dir: File, w: Int, h: Int, capS: Int, cool: Float, fp16: Boolean, threads: Int) {
        val params = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".param") }.sortedBy { it.path }.toList()
        log(JSONObject().put("survey", params.size).put("page", "${w}x$h").put("capS", capS).put("gpu", LibNcnn.gpu()?.name ?: JSONObject.NULL).put("state", state()))
        val page = SyntheticPage.draw(w, h)
        for (param in params) {
            val name = param.relativeTo(dir).path
            val waitedS = coolDown(cool)
            val files = ModelFiles(param, File(param.path.removeSuffix(".param") + ".bin"))
            val memory = ModelMemory.of(this)
            val reset = resetPeakRss()
            val t0 = SystemClock.elapsedRealtime()
            val opening = try {
                Sessions.attempt(LibNcnn, files, threads, gpu = true, fp16 = fp16, memoryBytes = memory)
            } catch (t: Throwable) {
                log(JSONObject().put("model", name).put("failed", "${t.javaClass.simpleName}: ${t.message}"))
                continue
            }
            val openMs = SystemClock.elapsedRealtime() - t0
            val session = opening.session
            if (session == null) {
                log(
                    JSONObject().put("model", name).put("opened", false).put("refusal", opening.refusal?.name ?: JSONObject.NULL)
                        .put("neededMb", opening.bytes shr 20).put("memoryMb", memory shr 20).put("openMs", openMs).put("waitedS", waitedS)
                        .put("peakRssMb", peakRssKb() / 1024).put("peakReset", reset)
                )
                continue
            }
            session.use { s ->
                val g = s.geometry
                val before = state()
                val p0 = SystemClock.elapsedRealtime()
                val run = TileEngine.run(s, page, PageLimits(timeoutMs = capS * 1000L))
                val pageMs = SystemClock.elapsedRealtime() - p0
                run.bitmap?.recycle()
                val tiles = run.stats.tileMs
                // Cut short: the first tile (the GPU's warm-up) once, the rest at their pace; null before a pace.
                val estimate: Any = if (run.fallback == null) pageMs else run.stats.fullMs ?: JSONObject.NULL
                log(
                    JSONObject().put("model", name).put("opened", true).put("openMs", openMs).put("waitedS", waitedS)
                        .put("onGpu", s.model.onGpu).put("fp16", s.fp16).put("scale", g.scale).put("trim", g.trim).put("context", g.context)
                        .put("margin", g.margin).put("tile", s.size).put("memoryMb", memory shr 20).put("peakRssMb", peakRssKb() / 1024)
                        .put("peakReset", reset).put("tiles", run.stats.planned).put("tilesRun", run.stats.tiles)
                        .put("fallback", run.fallback?.name ?: JSONObject.NULL).put("pageMs", pageMs).put("estimatedMs", estimate)
                        .put("tileMs", Stats.summary(tiles.toList())).put("graphicsMb", graphicsKb() / 1024)
                        .put("before", before).put("after", state())
                )
            }
        }
        page.recycle()
        log(JSONObject().put("surveyed", params.size).put("state", state()))
    }

    // ------------------------------------------------------------------ shaders

    private fun shaders(ids: List<String>, pages: List<Pair<Int, Int>>, tiles: List<Int>, reps: Int, cool: Float?, save: Boolean, off: Boolean, inputs: List<File> = emptyList()) {
        val store = ModelStore.of(this)
        val available = GlesShaders.available
        log(
            JSONObject().put("shaders", ids.size).put("available", available).put("renderer", GlesShaders.renderer ?: JSONObject.NULL)
                .put("floatBytes", GlesShaders.floatBytes).put("maxSide", GlesShaders.maxSide).put("state", state())
        )
        val chains = ids.mapNotNull { id ->
            ShaderCatalog.byId(id) ?: run { log(JSONObject().put("shader", id).put("failed", "not in the catalog")); null }
        }
        for (m in chains) {
            if (!store.installed(m)) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    ModelDownloader(store).download(m)
                    log(JSONObject().put("shader", m.id).put("downloaded", m.bytes).put("ms", SystemClock.elapsedRealtime() - t0))
                } catch (e: Exception) {
                    log(JSONObject().put("shader", m.id).put("failed", "download: ${e.javaClass.simpleName}: ${e.message}"))
                    continue
                }
            }
            val files = store.shaderFiles(m)!!
            val memory = ModelMemory.of(this)
            val t0 = SystemClock.elapsedRealtime()
            val opening = ShaderSessions.attempt(GlesShaders, { files.map { it.readText() } }, m.scale, memory, cache = ReaderAi.programsIn(this))
            val openMs = SystemClock.elapsedRealtime() - t0
            val s = opening.session
            if (s == null) {
                log(JSONObject().put("shader", m.id).put("opened", false).put("refusal", opening.refusal?.name ?: JSONObject.NULL).put("neededMb", opening.bytes shr 20).put("openMs", openMs))
                continue
            }
            s.use {
                val g = s.geometry
                log(
                    JSONObject().put("shader", m.id).put("opened", true).put("openMs", openMs).put("scale", g.scale).put("context", g.context)
                        .put("margin", g.margin).put("tile", s.size).put("memoryMb", memory shr 20).put("graphicsMb", graphicsKb() / 1024)
                )
                for (input in inputs) shaderInput(m.id, s, input)
                for ((w, h) in pages.takeIf { inputs.isEmpty() }.orEmpty()) {
                    val page = SyntheticPage.draw(w, h)
                    if (save) {
                        val name = "shader-page-${w}x$h.png"
                        if (!File(out.parentFile, name).isFile) save(page, name)
                    }
                    // 0: the session's own tile for the page, as the reader runs it.
                    for (asked in tiles.ifEmpty { listOf(0) }.filter { it == 0 || TilePlan.fits(it, g) }) {
                        val waitedS = cool?.let { coolDown(it, COOL_TIME_MAX_S) } ?: 0L
                        val session = if (asked == 0) s else Session(s.model, g, asked)
                        val size = session.sizeFor(w, h)
                        val before = state()
                        val w0 = SystemClock.elapsedRealtime()
                        TileEngine.run(session, page, UNLIMITED).bitmap?.recycle()
                        val warmMs = SystemClock.elapsedRealtime() - w0
                        val times = ArrayList<Long>()
                        val tileMs = ArrayList<Long>()
                        var planned = 0
                        var fallback: Fallback? = null
                        for (r in 0 until reps) {
                            val p0 = SystemClock.elapsedRealtime()
                            val run = TileEngine.run(session, page, UNLIMITED)
                            times += SystemClock.elapsedRealtime() - p0
                            tileMs += run.stats.tileMs.toList()
                            planned = run.stats.planned
                            fallback = fallback ?: run.fallback
                            run.bitmap?.recycle()
                        }
                        log(
                            JSONObject().put("shaderTime", m.id).put("page", "${w}x$h").put("tile", size).put("tiles", planned).put("cooledS", waitedS)
                                .put("warmUpMs", warmMs).put("pageMs", Stats.summary(times)).put("tileMs", Stats.summary(tileMs))
                                .put("fallback", fallback?.name ?: JSONObject.NULL).put("graphicsMb", graphicsKb() / 1024).put("before", before).put("after", state())
                        )
                    }
                    // Tiles of another size meet elsewhere: any difference is a seam.
                    val main = TileEngine.run(s, page, UNLIMITED).bitmap
                    val other = listOf(256, 192).firstOrNull { it != s.sizeFor(w, h) && TilePlan.fits(it, g) }
                    val small = other?.let { TileEngine.run(Session(s.model, g, it), page, UNLIMITED).bitmap }
                    val line = JSONObject().put("shaderSeams", m.id).put("page", "${w}x$h").put("tile", s.sizeFor(w, h)).put("against", other ?: JSONObject.NULL)
                    if (main != null && small != null) {
                        val d = diff(pixels(main), pixels(small))
                        line.put("maxDiff", d.max).put("over2", d.over2)
                    } else {
                        line.put("failed", true)
                    }
                    log(line)
                    if (save && main != null) save(main, "shader-${m.id}-${w}x$h.png")
                    main?.recycle()
                    small?.recycle()
                    var done = 0
                    var canceledAt = 0L
                    try {
                        TileEngine.run(s, page, UNLIMITED, canceled = {
                            if (done >= 3 && canceledAt == 0L) canceledAt = SystemClock.elapsedRealtime()
                            done >= 3
                        }, onTile = { d, _, _ -> done = d })
                        log(JSONObject().put("shaderCancel", m.id).put("page", "${w}x$h").put("failed", "ran to the end"))
                    } catch (e: CancellationException) {
                        log(JSONObject().put("shaderCancel", m.id).put("page", "${w}x$h").put("tilesRun", done).put("stopMs", SystemClock.elapsedRealtime() - canceledAt))
                    }
                    page.recycle()
                }
            }
        }
        val first = chains.firstOrNull { store.installed(it) }
        if (off && first != null) {
            val files = store.shaderFiles(first)!!
            val line = GlesShaders.whileOff {
                val o = ShaderSessions.attempt(GlesShaders, { files.map { it.readText() } }, first.scale, ModelMemory.of(this))
                o.session?.close()
                JSONObject().put("shaderOff", first.id).put("available", GlesShaders.available).put("opened", o.session != null).put("refusal", o.refusal?.name ?: JSONObject.NULL)
            }
            log(line.put("availableAfter", GlesShaders.available))
        }
        log(JSONObject().put("shaded", chains.size).put("state", state()))
    }

    /** [input] (an image file) through [s] at its own tile, the output saved as `shader-<id>-<image name>.png`. */
    private fun shaderInput(id: String, s: Session, input: File) {
        val line = JSONObject().put("shaderInput", id).put("input", input.name)
        val page = runCatching { BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()
        if (page == null) {
            log(line.put("failed", "unreadable"))
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val run = TileEngine.run(s, page, UNLIMITED)
        line.put("page", "${page.width}x${page.height}").put("tile", s.sizeFor(page.width, page.height)).put("ms", SystemClock.elapsedRealtime() - t0)
        val result = run.bitmap
        if (result == null) {
            log(line.put("failed", run.fallback?.name ?: "no output"))
        } else {
            val name = "shader-$id-${input.nameWithoutExtension}.png"
            save(result, name)
            log(line.put("saved", name))
            result.recycle()
        }
        page.recycle()
    }

    /**
     * The GLES runner against the CPU on [ShaderReference]'s chain, per synthetic page size: at
     * the page's own tile and at 192 px tiles, PSNR and the largest difference (codes).
     */
    private fun shaderReference(pages: List<Pair<Int, Int>>, save: Boolean) {
        val o = ShaderSessions.attempt(GlesShaders, { listOf(ShaderReference.CHAIN) }, 2, ModelMemory.of(this))
        val s = o.session
        if (s == null) {
            log(JSONObject().put("shaderRef", false).put("available", GlesShaders.available).put("refusal", o.refusal?.name ?: JSONObject.NULL))
            return
        }
        s.use {
            val g = s.geometry
            log(
                JSONObject().put("shaderRef", true).put("renderer", GlesShaders.renderer ?: JSONObject.NULL).put("floatBytes", GlesShaders.floatBytes)
                    .put("context", g.context).put("margin", g.margin).put("tile", s.size)
            )
            for ((w, h) in pages) {
                val page = SyntheticPage.draw(w, h)
                val t0 = SystemClock.elapsedRealtime()
                val truth = ShaderReference.render(page)
                val cpuMs = SystemClock.elapsedRealtime() - t0
                if (save) save(truth, "shaderref-cpu-${w}x$h.png")
                for (session in listOf(s, Session(s.model, g, 192)).filter { TilePlan.fits(it.size, g) }) {
                    val out = TileEngine.run(session, page, UNLIMITED).bitmap
                    val line = JSONObject().put("shaderRef", "${w}x$h").put("tile", session.sizeFor(w, h)).put("cpuMs", cpuMs)
                    if (out == null) {
                        log(line.put("failed", true))
                        continue
                    }
                    val c = ShaderReference.compare(out, truth)
                    log(line.put("psnr", c.psnr).put("maxDiff", c.maxDiff).put("meanDiff", Stats.round1(c.meanDiff * 100) / 100))
                    if (save) save(out, "shaderref-gpu-${w}x$h-${session.sizeFor(w, h)}.png")
                    out.recycle()
                }
                truth.recycle()
                page.recycle()
            }
        }
    }

    // ------------------------------------------------------------------ gray

    private fun gray(ids: List<String>, param: File?, engines: List<Boolean>, w: Int, h: Int, fp16: Boolean, threads: Int, cool: Float) {
        val store = ModelStore.of(this)
        val models: List<Pair<String, ModelFiles?>> = if (param != null) {
            listOf(param.name to ModelFiles(param, File(param.path.removeSuffix(".param") + ".bin")).takeIf { param.isFile && it.bin.isFile })
        } else {
            ids.map { id -> id to ModelCatalog.byId(id)?.let(store::files) }
        }
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(GrayRuns.screentone(w, h), 0, w, 0, 0, w, h)
            setHasAlpha(false)
        }
        save(page, "gray-page.png")
        log(
            JSONObject().put("gray", models.size).put("page", "${w}x$h").put("pageGray", GrayPages.isGray(page))
                .put("gpu", LibNcnn.gpu()?.name ?: JSONObject.NULL).put("state", state())
        )
        // Shown smaller than its pixels, as descreened pages usually are.
        val plan = Plan(w * 3 / 4, h * 3 / 4, 0.0, 0.0, w.toDouble(), h.toDouble())
        for ((name, files) in models) {
            if (files == null) {
                log(JSONObject().put("grayModel", name).put("failed", "not installed (do download --ez keep true)"))
                continue
            }
            for (gpu in engines) {
                val asked = if (gpu) "gpu" else "cpu"
                val session = Sessions.open(LibNcnn, files, threads, gpu = gpu, fp16 = fp16)
                if (session == null) {
                    log(JSONObject().put("grayModel", name).put("engine", asked).put("failed", "open"))
                    continue
                }
                session.use { s ->
                    val line = JSONObject().put("grayModel", name).put("engine", asked).put("onGpu", s.model.onGpu).put("fp16", s.fp16)
                        .put("tile", s.size).put("scale", s.geometry.scale)
                    GrayRuns.engineProblem(gpu, s.model.onGpu)?.let { log(line.put("failed", it)); return@use }
                    // The model's own output: gray false keeps its color, whatever the page is.
                    line.put("cooledS", coolDown(cool, COOL_TIME_MAX_S)).put("before", state())
                    val t0 = SystemClock.elapsedRealtime()
                    val rawRun = TileEngine.run(s, page, UNLIMITED, gray = false)
                    line.put("rawMs", SystemClock.elapsedRealtime() - t0).put("rawGray", rawRun.gray)
                    val raw = rawRun.bitmap?.let { b ->
                        val px = pixels(b)
                        val spread = GrayRuns.spread(px, b.width)
                        if (spread.max > 0) save(b, "gray-$name-$asked-raw.png")
                        b.recycle()
                        spread
                    }
                    // The page as the reader shows it: the chain finds the page gray and collapses each model's output.
                    line.put("shownCooledS", coolDown(cool, COOL_TIME_MAX_S))
                    val chain = AiPages.chain(listOf(s), page, plan, Scaling.DEFAULT, limits = UNLIMITED)
                    val shown = chain.bitmap?.let { b -> GrayRuns.spread(pixels(b), b.width).also { b.recycle() } }
                    line.put("raw", raw?.let(::spreadJson) ?: JSONObject.NULL).put("rawFallback", rawRun.fallback?.name ?: JSONObject.NULL)
                        .put("shown", shown?.let(::spreadJson) ?: JSONObject.NULL).put("chainRan", chain.ran)
                        .put("chainFallback", chain.fallback?.name ?: JSONObject.NULL).put("after", state())
                    GrayRuns.runProblem(rawRun.gray, rawRun.fallback, chain.ran, chain.fallback)?.let { line.put("failed", it) }
                    log(line)
                }
            }
        }
        page.recycle()
        log(JSONObject().put("grayed", models.size).put("state", state()))
    }

    private fun spreadJson(s: GrayRuns.Spread): JSONObject =
        JSONObject().put("max", s.max).put("mean", Stats.round1(s.mean * 100) / 100).put("over2", s.over2).put("over16", s.over16)
            .put("at", if (s.atX < 0) JSONObject.NULL else "${s.atX},${s.atY}")

    /** Waits, up to [maxS] seconds, for the thermal headroom to fall below [below]; the seconds waited. */
    private fun coolDown(below: Float, maxS: Int = COOL_MAX_S): Long {
        if (Build.VERSION.SDK_INT < 30) return 0
        val power = getSystemService(PowerManager::class.java)
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < maxS * 1000L) {
            val h = power.getThermalHeadroom(0)
            if (!h.isNaN() && h < below) break
            Thread.sleep(5_000)
        }
        return (SystemClock.elapsedRealtime() - t0) / 1000
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

    /**
     * Starts the process's peak resident memory ([peakRssKb]) again from what it holds now, so
     * the next reading is one model's; false where the kernel refuses.
     */
    private fun resetPeakRss(): Boolean = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess

    /** The most resident memory this process has held since start or [resetPeakRss], in KB. */
    private fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.split(Regex("\\s+"))[1].toLong()
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
            .put("plugged", (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            // Charging (2), discharging (3), not charging (4) or full (5). No temperature: the
            // sticky broadcast carries the one from its last level or status change.
            .put("batteryStatus", sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
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

        /** The thermal headroom a survey waits below before each model by default, and its longest wait. */
        const val COOL_HEADROOM = 0.85f
        const val COOL_MAX_S = 180

        /** The longest a timed run waits to cool. */
        const val COOL_TIME_MAX_S = 900

        /** Limits no timed page reaches. */
        val UNLIMITED = PageLimits(timeoutMs = 3_600_000L)
    }
}
