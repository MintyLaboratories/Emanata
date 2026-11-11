package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.data.SmartCrop
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Finds where text is in an image (not what it says), as a score map ([ScoreMap]: near 1 on
 * the core of each line of text): PaddleOCR's mobile text detector (PP-OCRv5 mobile det,
 * Apache-2.0), an ncnn conversion downloaded unchanged when first needed
 * ([ModelCatalog.TEXT_DETECTOR], [DetectorModel]). For the Smart crop anchor of covers. Opened
 * through [runtime] on first use from [files]; while they are missing it finds nothing and
 * [ready] says so, which asks for them ([inPlace]). Runs on the [gpu] or [threads] CPU threads,
 * and closes after [idleMs] without use, so its memory goes when covers are done. Feed it
 * images whose sides are multiples of 32. Its [lines] are the map's text regions
 * ([SmartCrop.lines]).
 */
internal class TextDetector(
    private val runtime: InferenceRuntime,
    private val files: () -> ModelFiles?,
    private val gpu: Boolean = false,
    private val threads: Int = THREADS,
    private val idleMs: Long = IDLE_MS,
    /** True once the model is in place, asking for it while not; null: [files] tell. */
    private val inPlace: (() -> Boolean)? = null
) : CoverTextDetector {
    override val id: String get() = ID

    /** True where the runtime does not load (nothing to wait for) or the model is in place. */
    override fun ready(): Boolean = !runtime.available || (inPlace?.invoke() ?: (files() != null))

    override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
        val map = detect(src) ?: return null
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
     * not run this time (its files missing, or not opening). Blocking; one image at a time.
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

    /** The model from [files]; null while they are missing, and for good once they do not open. */
    private fun open(): TileModel? {
        val f = files() ?: return null
        val m = runtime.open(f, gpu, threads, fp16 = true)
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

        /** Its [CoverTextDetector.id]: PP-OCRv5 mobile det and the start of its files' SHA-256. */
        val ID: String = ModelCatalog.TEXT_DETECTOR.let { "ppocrv5-${it.param.sha256.take(6)}${it.bin.sha256.take(6)}" }

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

        private val closer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "text-detector-idle").apply { isDaemon = true } }

        @Volatile
        private var shared: TextDetector? = null

        /** The app's detector ([forApp]) on [LibNcnn.cpuLane], apart from AI page tiles; made once per process. */
        fun shared(context: Context): TextDetector = shared ?: synchronized(this) {
            shared ?: forApp(context, LibNcnn.cpuLane).also { shared = it }
        }

        /** A detector on [runtime] of the downloaded model ([downloaded]), downloaded when first needed ([DetectorModel.shared]). */
        fun forApp(context: Context, runtime: InferenceRuntime): TextDetector {
            val app = context.applicationContext
            val store = ModelStore.of(app)
            val model = DetectorModel.shared(app)
            return TextDetector(runtime, { downloaded(store) }, inPlace = model::ready)
        }

        /** The downloaded model's files in [store]; null until it is downloaded and verified. */
        fun downloaded(store: ModelStore): ModelFiles? = store.files(ModelCatalog.TEXT_DETECTOR)
    }
}
