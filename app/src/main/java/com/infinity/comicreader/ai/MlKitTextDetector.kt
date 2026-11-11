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
 * [lines] are ML Kit's ([MlKitLines.boxes]). [lines] blocks: never on the main thread; [close]
 * and [isOpen] never wait for it.
 */
internal class MlKitTextDetector(
    private val open: () -> LineFinder,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val idleMs: Long = IDLE_MS,
    private val mainThread: () -> Boolean = { Looper.getMainLooper().isCurrentThread }
) : CoverTextDetector {
    override val id: String get() = ID

    /** Held through a run of [lines], so covers take turns. */
    private val work = Any()

    /** Held briefly for the fields below, never while waiting for ML Kit. */
    private val state = Any()
    private var finder: LineFinder? = null

    /** The client a run uses now: closing it is left to that run. */
    private var inUse: LineFinder? = null
    private var closing: ScheduledFuture<*>? = null

    /** Counts runs, so a close timed after one run leaves the client to a later one. */
    private var runs = 0L

    /**
     * The lines on [src], in its pixels; null when ML Kit failed or gave no answer in time.
     * ML Kit reads a copy, so [src] may be recycled once this returns; the copy is recycled once
     * ML Kit is done with it. Throws when the client does not open, ML Kit's classes failing to
     * link included ([IllegalStateException]), and when interrupted ([InterruptedException]),
     * the thread left interrupted.
     */
    override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
        check(!mainThread()) { "ML Kit's text detector waits for its answer: not on the main thread" }
        synchronized(work) {
            try {
                val f = take()
                var timedOut = false
                try {
                    val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                    val found = try {
                        Tasks.await(f.find(copy), timeoutMs, TimeUnit.MILLISECONDS)
                    } catch (e: TimeoutException) {
                        Log.w(TAG, "no answer within $timeoutMs ms")
                        timedOut = true
                        return null
                    } catch (e: ExecutionException) {
                        Log.w(TAG, "text recognition failed: ${e.cause}")
                        copy.recycle()
                        return null
                    } catch (e: InterruptedException) {
                        // Stopped from outside: the thread stays interrupted, whoever catches this.
                        Thread.currentThread().interrupt()
                        throw e
                    }
                    copy.recycle()
                    return MlKitLines.boxes(found, src.width, src.height)
                } finally {
                    release(f, timedOut)
                }
            } finally {
                synchronized(state) {
                    val run = ++runs
                    closing = closer.schedule({ closeAfter(run) }, idleMs, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /** The open client, opened when there is none ([open]), now in use. Under [work]. */
    private fun take(): LineFinder {
        synchronized(state) {
            closing?.cancel(false)
            closing = null
            finder?.let {
                inUse = it
                return it
            }
        }
        val f = try {
            open()
        } catch (e: LinkageError) {
            throw IllegalStateException("ML Kit's classes do not link", e)
        }
        synchronized(state) {
            finder = f
            inUse = f
        }
        return f
    }

    /** Ends the use of [f]: closes it when it is to be [dropped] or was closed meanwhile ([close]). */
    private fun release(f: LineFinder, dropped: Boolean) {
        val done = synchronized(state) {
            inUse = null
            when {
                finder !== f -> f
                dropped -> f.also { finder = null }
                else -> null
            }
        }
        done?.let { runCatching { it.close() } }
    }

    /** Closes the client unless it ran, or runs, since [run]. */
    private fun closeAfter(run: Long) {
        val f = synchronized(state) {
            if (run != runs || inUse != null) return
            detach()
        }
        f?.let { runCatching { it.close() } }
    }

    /** True while the client is open. */
    val isOpen: Boolean get() = synchronized(state) { finder != null }

    /** Closes the client, at once or, while a cover uses it, when that one is done; the next [lines] opens it again. */
    fun close() {
        val f = synchronized(state) { detach() }
        f?.let { runCatching { it.close() } }
    }

    /** Forgets the client and its idle close; returns it unless a cover uses it. Under [state]. */
    private fun detach(): LineFinder? {
        closing?.cancel(false)
        closing = null
        return finder.also { finder = null }?.takeIf { it !== inUse }
    }

    /** ML Kit's recognizer [client] as a [LineFinder]. */
    private class Recognizer(private val client: TextRecognizer) : LineFinder {
        override fun find(src: Bitmap): Task<List<FoundLine>> =
            client.process(InputImage.fromBitmap(src, 0)).continueWith(DIRECT) { found(it.result) }

        override fun close() = client.close()
    }

    companion object {
        private const val TAG = "MlKitTextDetector"

        /**
         * Its [CoverTextDetector.id]: ML Kit text recognition v2, Latin script, its lines as boxes
         * by [MlKitLines.VERSION]. Play services updates the model on its own, unseen by the app,
         * so focuses found by an earlier model keep this id: a known limit of the unbundled model.
         */
        const val ID = "mlkit-latin-v2-lines${MlKitLines.VERSION}"

        /** The longest wait for ML Kit's answer on one cover; the first includes loading its model. */
        const val TIMEOUT_MS = 10_000L

        /** Closed this long after its last use. */
        const val IDLE_MS = 20_000L

        /** Runs a continuation on the thread that completed the task, never the main thread's queue. */
        private val DIRECT = Executor { it.run() }

        /** Times idle closes; a canceled one leaves its queue at once. */
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
