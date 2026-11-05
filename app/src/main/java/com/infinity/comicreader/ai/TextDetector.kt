package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Finds where text is in an image (not what it says), as a score map ([ScoreMap]: near 1 on
 * the core of each line of text): PaddleOCR's mobile text detector (PP-OCRv5 mobile det,
 * Apache-2.0), converted to ncnn by `tools/text-detector/convert.py` and shipped in the app's
 * assets. For the Smart crop anchor of covers. Opened through [runtime] on first use, on the
 * [gpu] or [threads] CPU threads, and closed after [idleMs] without use, so its memory goes
 * when covers are done. Feed it images whose sides are multiples of 32.
 */
internal class TextDetector(
    private val runtime: InferenceRuntime,
    private val files: () -> ModelFiles?,
    private val gpu: Boolean = false,
    private val threads: Int = THREADS,
    private val idleMs: Long = IDLE_MS
) {
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
        val f = files()
        val m = f?.let { runtime.open(it, gpu, threads, fp16 = true) }
        if (m == null) {
            Log.w(TAG, "text detector does not open")
            broken = true
        }
        model = m
        return m
    }

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

        /** The app's detector, on [LibNcnn.cpuLane]: apart from AI page tiles. */
        fun shared(context: Context): TextDetector = shared ?: synchronized(this) {
            shared ?: TextDetector(LibNcnn.cpuLane, { install(context.applicationContext) }).also { shared = it }
        }

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
