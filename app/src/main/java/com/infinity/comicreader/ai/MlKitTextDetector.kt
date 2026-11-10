package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.infinity.comicreader.data.SmartCrop
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Finds the lines of text on an image: ML Kit's recognizer client, or a stand-in. */
internal interface LineFinder : Closeable {
    /** The lines on [src], which is its own until the task completes. */
    fun find(src: Bitmap): Task<List<FoundLine>>
}

/**
 * Finds where the lines of text on a cover are with Google's ML Kit text recognition (v2,
 * Latin script) through Play services, whose module must be on the device. What the lines say
 * is dropped as they are read. Its client ([open]) opens on first use and closes [idleMs] after
 * the last, or when it gave no answer within [timeoutMs] (that cover then gets null). Its
 * [lines] are ML Kit's ([MlKitLines.boxes]). Blocking: never on the main thread.
 */
internal class MlKitTextDetector(
    private val open: () -> LineFinder,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val idleMs: Long = IDLE_MS,
    private val mainThread: () -> Boolean = { Looper.getMainLooper().isCurrentThread }
) : CoverTextDetector {
    override val id: String get() = ID

    private var finder: LineFinder? = null
    private var closing: ScheduledFuture<*>? = null

    /** Counts runs, so a close timed after one run leaves the client to a later one. */
    private var runs = 0L

    /**
     * The lines on [src], in its pixels; null when ML Kit failed or gave no answer in time.
     * ML Kit reads a copy, so [src] may be recycled once this returns. Throws when the client
     * does not open, ML Kit's classes failing to link included ([IllegalStateException]).
     */
    @Synchronized
    override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
        check(!mainThread()) { "ML Kit's text detector waits for its answer: not on the main thread" }
        closing?.cancel(false)
        try {
            val f = finder ?: opened().also { finder = it }
            val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val found = try {
                Tasks.await(f.find(copy), timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                Log.w(TAG, "no answer within $timeoutMs ms")
                close()
                return null
            } catch (e: ExecutionException) {
                Log.w(TAG, "text recognition failed: ${e.cause}")
                return null
            }
            return MlKitLines.boxes(found, src.width, src.height)
        } finally {
            val run = ++runs
            closing = closer.schedule({ closeAfter(run) }, idleMs, TimeUnit.MILLISECONDS)
        }
    }

    /** A new client ([open]); ML Kit's classes failing to link as an [IllegalStateException]. */
    private fun opened(): LineFinder = try {
        open()
    } catch (e: LinkageError) {
        throw IllegalStateException("ML Kit's classes do not link", e)
    }

    /** Closes the client unless it ran again since [run]. */
    @Synchronized
    private fun closeAfter(run: Long) {
        if (run == runs) close()
    }

    /** True while the client is open. */
    val isOpen: Boolean @Synchronized get() = finder != null

    /** Closes the client; the next [lines] opens it again. */
    @Synchronized
    fun close() {
        closing?.cancel(false)
        closing = null
        finder?.let { runCatching { it.close() } }
        finder = null
    }

    /** ML Kit's recognizer [client] as a [LineFinder]. */
    private class Recognizer(private val client: TextRecognizer) : LineFinder {
        override fun find(src: Bitmap): Task<List<FoundLine>> =
            client.process(InputImage.fromBitmap(src, 0)).continueWith(DIRECT) { found(it.result) }

        override fun close() = client.close()
    }

    companion object {
        private const val TAG = "MlKitTextDetector"

        /** Its [CoverTextDetector.id]: ML Kit text recognition v2, Latin script. */
        const val ID = "mlkit-latin-v2"

        /** The longest wait for ML Kit's answer on one cover; the first includes loading its model. */
        const val TIMEOUT_MS = 10_000L

        /** Closed this long after its last use. */
        const val IDLE_MS = 20_000L

        /** Runs a continuation on the thread that completed the task, never the main thread's queue. */
        private val DIRECT = Executor { it.run() }

        /** Times idle closes; a cancelled one leaves its queue at once. */
        private val closer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "mlkit-text-idle").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

        /**
         * The detector on Play services' recognizer. Loads ML Kit's classes, so where they are
         * missing or do not link this throws ([LinkageError]); ML Kit starts on first use, not before.
         */
        fun create(context: Context): MlKitTextDetector {
            val app = context.applicationContext
            val options = TextRecognizerOptions.DEFAULT_OPTIONS
            listOf(MlKitContext::class.java, TextRecognition::class.java, InputImage::class.java, Tasks::class.java, Recognizer::class.java)
            return MlKitTextDetector({
                MlKitContext.initializeIfNeeded(app)
                Recognizer(TextRecognition.getClient(options))
            })
        }

        /** The lines of [text], without what they say. */
        fun found(text: Text): List<FoundLine> = text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val b = line.boundingBox
                FoundLine(
                    line.cornerPoints?.map { Corner(it.x.toDouble(), it.y.toDouble()) },
                    b?.left?.toDouble() ?: Double.NaN, b?.top?.toDouble() ?: Double.NaN,
                    b?.right?.toDouble() ?: Double.NaN, b?.bottom?.toDouble() ?: Double.NaN,
                    line.confidence
                )
            }
        }
    }
}
