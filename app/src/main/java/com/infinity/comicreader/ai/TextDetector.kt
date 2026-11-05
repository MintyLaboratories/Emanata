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

    /** [src]'s score map; null when the detector cannot run. Blocking; one image at a time. */
    @Synchronized
    fun detect(src: Bitmap): ScoreMap? {
        if (broken || !runtime.available) return null
        closing?.cancel(false)
        val m = model ?: open() ?: return null
        try {
            return m.map(src, MEAN, NORM, bgr = true)
        } finally {
            closing = closer.schedule({ close() }, idleMs, TimeUnit.MILLISECONDS)
        }
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

        /** CPU threads it runs on: covers come one after another, and the model is small. */
        const val THREADS = 2

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

        private val closer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "text-detector-idle").apply { isDaemon = true } }

        @Volatile
        private var shared: TextDetector? = null

        /** The app's detector, on [LibNcnn]. */
        fun shared(context: Context): TextDetector = shared ?: synchronized(this) {
            shared ?: TextDetector(LibNcnn, { install(context.applicationContext) }).also { shared = it }
        }

        /**
         * The model's files, copied once from the assets to the app's no-backup files (ncnn reads
         * files by path); null when they cannot be.
         */
        fun install(context: Context): ModelFiles? {
            val dir = File(context.noBackupFilesDir, DIR)
            val param = File(dir, PARAM)
            val bin = File(dir, BIN)
            try {
                for ((file, bytes) in listOf(param to PARAM_BYTES, bin to BIN_BYTES)) {
                    if (file.length() == bytes) continue
                    dir.mkdirs()
                    val part = File(dir, file.name + ".part")
                    context.assets.open("$DIR/${file.name}").use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (part.length() != bytes) {
                        part.delete()
                        return null
                    }
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot install the text detector: ${e.message}")
                return null
            }
            return ModelFiles(param, bin)
        }
    }
}
