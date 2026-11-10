package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.data.SmartCrop
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Finds where text is in an image (not what it says), as a score map ([ScoreMap]: near 1 on
 * the core of each line of text): PaddleOCR's mobile text detector (PP-OCRv5 mobile det,
 * Apache-2.0). Two copies: the app's assets, converted to ncnn by `tools/text-detector/convert.py`,
 * and a third-party ncnn conversion downloaded unchanged ([ModelCatalog.TEXT_DETECTOR]); their
 * maps differ slightly, so each has its own [id]. For the Smart crop anchor of covers. Opened
 * through [runtime] on first use from [files] (id [filesId]); once those do not open, from
 * [fallback] (id [fallbackId]) for the rest of its life. Runs on the [gpu] or [threads] CPU
 * threads, and closes after [idleMs] without use, so its memory goes when covers are done. Feed
 * it images whose sides are multiples of 32. Its [lines] are the map's text regions
 * ([SmartCrop.lines]).
 */
internal class TextDetector(
    private val runtime: InferenceRuntime,
    private val files: () -> ModelFiles?,
    private val gpu: Boolean = false,
    private val threads: Int = THREADS,
    private val idleMs: Long = IDLE_MS,
    private val fallback: () -> ModelFiles? = { null },
    private val filesId: String = ID,
    private val fallbackId: String = ID
) : CoverTextDetector {
    /** True once [files] did not open: [fallback] runs from then on. */
    @Volatile
    private var fellBack = false

    override val id: String get() = if (fellBack) fallbackId else filesId

    /** Null too on the run that fell back to a copy of another [id]: what it found is not under the id the caller read. */
    override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
        val asked = id
        val map = detect(src) ?: return null
        if (id != asked) return null
        return SmartCrop.lines(map, src.width, src.height)
    }

    private var model: TileModel? = null

    /** True once the model failed to open: it is not tried again this session. */
    private var broken = false
    private var closing: ScheduledFuture<*>? = null

    /** Counts runs, so a close timed after one run leaves the model to a later one. */
    private var runs = 0L

    /**
     * [src]'s score map; [ABSENT] where the runtime does not load, null when the detector did
     * not run this time. Blocking; one image at a time.
     */
    @Synchronized
    fun detect(src: Bitmap): ScoreMap? {
        if (!runtime.available) return ABSENT
        if (broken) return null
        closing?.cancel(false)
        val m = model ?: open() ?: return null
        try {
            return m.map(src, MEAN, NORM, bgr = true)
        } finally {
            val run = ++runs
            closing = closer.schedule({ closeAfter(run) }, idleMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Closes the model unless it ran again since [run]. */
    @Synchronized
    private fun closeAfter(run: Long) {
        if (run == runs) close()
    }

    /** True while the model is open. */
    val isOpen: Boolean @Synchronized get() = model != null

    private fun open(): TileModel? {
        var tried: ModelFiles? = null
        if (!fellBack) {
            tried = files()
            tried?.let(::load)?.let {
                model = it
                return it
            }
            fellBack = true
        }
        val m = fallback()?.takeIf { it != tried }?.let(::load)
        if (m == null) {
            Log.w(TAG, "text detector does not open")
            broken = true
        }
        model = m
        return m
    }

    private fun load(f: ModelFiles): TileModel? = runtime.open(f, gpu, threads, fp16 = true)

    /** One copy of the model: its files and its [CoverTextDetector.id]. */
    data class Copy(val files: ModelFiles, val id: String)

    /** Closes the model; the next [detect] opens it again. */
    @Synchronized
    fun close() {
        closing?.cancel(false)
        closing = null
        model?.close()
        model = null
    }

    companion object {
        private const val TAG = "TextDetector"

        /** Its [CoverTextDetector.id] with the assets' copy: PP-OCRv5 mobile det. */
        const val ID = "ppocrv5"

        /** Its [CoverTextDetector.id] with the downloaded copy: [ID] and the start of its files' SHA-256. */
        val DOWNLOADED_ID: String = ModelCatalog.TEXT_DETECTOR.let { "$ID-${it.param.sha256.take(6)}${it.bin.sha256.take(6)}" }

        /**
         * CPU threads it runs on, of the large cores. On the reference tablet a cover's input took
         * about as long on one thread as on two, four or the GPU, so one, which costs least.
         */
        const val THREADS = 1

        /** What [detect] gives where it never runs: a map without text. */
        val ABSENT = ScoreMap(0, 0, FloatArray(0))

        /** Closed this long after its last use. */
        const val IDLE_MS = 20_000L

        /** The model's input normalization, ImageNet's mean and deviation per channel, fed in B, G, R order as PaddleOCR does. */
        val MEAN = floatArrayOf(0.485f * 255, 0.456f * 255, 0.406f * 255)
        val NORM = floatArrayOf(1 / (0.229f * 255), 1 / (0.224f * 255), 1 / (0.225f * 255))

        /** Folder under `assets/` and under the app's no-backup files holding the model. */
        const val DIR = "text-detector"
        const val PARAM = "ppocrv5-mobile-det.ncnn.param"
        const val BIN = "ppocrv5-mobile-det.ncnn.bin"

        /** The shipped files' sizes: a copy of another size is replaced. */
        const val PARAM_BYTES = 23_386L
        const val BIN_BYTES = 2_357_216L

        /** Beside the copy: when the app that made it was installed, so an update copies its model anew. */
        private const val STAMP = "installed"

        private val closer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "text-detector-idle").apply { isDaemon = true } }

        @Volatile
        private var shared: TextDetector? = null

        /** The app's detector ([forApp]) on [LibNcnn.cpuLane], apart from AI page tiles; made once per process. */
        fun shared(context: Context): TextDetector = shared ?: synchronized(this) {
            shared ?: forApp(context, LibNcnn.cpuLane).also { shared = it }
        }

        /**
         * A detector on [runtime] of the copy in place now, kept for its life: the downloaded one
         * ([downloaded], id [DOWNLOADED_ID]) falling back to the assets' ([install], id [ID]), else
         * the assets' alone. A download that lands later is used from the next process on.
         */
        fun forApp(context: Context, runtime: InferenceRuntime): TextDetector {
            val app = context.applicationContext
            val store = ModelStore.of(app)
            return if (downloaded(store) != null) {
                TextDetector(runtime, { downloaded(store) }, fallback = { install(app) }, filesId = DOWNLOADED_ID)
            } else {
                TextDetector(runtime, { install(app) })
            }
        }

        /** The copy [forApp] picks now: the downloaded one ([downloaded]) when in place, else the assets' ([install]). */
        fun copy(context: Context): Copy? =
            downloaded(ModelStore.of(context))?.let { Copy(it, DOWNLOADED_ID) } ?: install(context)?.let { Copy(it, ID) }

        /** The downloaded model's files in [store]; null until it is downloaded and verified. */
        fun downloaded(store: ModelStore): ModelFiles? = store.files(ModelCatalog.TEXT_DETECTOR)

        /**
         * The model's files, copied from the assets to the app's no-backup files (ncnn reads files
         * by path) once per install or update of the app; null when they cannot be.
         */
        fun install(context: Context): ModelFiles? {
            val dir = File(context.noBackupFilesDir, DIR)
            val param = File(dir, PARAM)
            val bin = File(dir, BIN)
            val stamp = File(dir, STAMP)
            try {
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
                val current = runCatching { stamp.readText() }.getOrNull() == installed
                for ((file, bytes) in listOf(param to PARAM_BYTES, bin to BIN_BYTES)) {
                    if (current && file.length() == bytes) continue
                    dir.mkdirs()
                    val part = File(dir, file.name + ".part")
                    context.assets.open("$DIR/${file.name}").use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (part.length() != bytes) {
                        part.delete()
                        return null
                    }
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
                if (!current) stamp.writeText(installed)
            } catch (e: Exception) {
                Log.w(TAG, "cannot install the text detector: ${e.message}")
                return null
            }
            return ModelFiles(param, bin)
        }
    }
}
