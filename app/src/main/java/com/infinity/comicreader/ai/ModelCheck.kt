package com.infinity.comicreader.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import com.infinity.comicreader.reader.SyntheticPage
import java.util.concurrent.CancellationException
import kotlin.math.abs

/**
 * Checks a model the user imports before it is taken in: it opens as every model does
 * ([Sessions.attempt]: scale, edge trim, context, memory, the GPU against the CPU), then
 * - its output must resemble its input: a smooth picture with a few hard shapes, run through
 *   it and averaged back down to the input's size, may differ from it by at most
 *   [UNLIKE_MEAN] codes on average (a model expecting pixels scaled otherwise, or files that
 *   do not belong together, give something else);
 * - it is timed on a synthetic [PAGE_W] x [PAGE_H] page, the size catalog models are timed
 *   on: [TIMED_TILES] tiles, the first (the GPU's warm-up) counted once and the rest at their
 *   pace, stopping sooner once the page would take longer than [ReaderAi.MAX_TIMEOUT_MS],
 *   the most the reader gives a page: such a model is refused.
 * All within [TIME_LIMIT_MS], else the model is refused as too slow.
 */
internal object ModelCheck {
    private const val TAG = "ModelCheck"

    /** The longest a check may take. */
    const val TIME_LIMIT_MS = 120_000L

    /** Mean difference, in codes, past which output does not resemble its input. */
    const val UNLIKE_MEAN = 24.0

    /** Tiles timed. */
    const val TIMED_TILES = TileEngine.PACE_AFTER + 2

    const val PAGE_W = 1000
    const val PAGE_H = 1500

    /** The side of the picture output is compared on. */
    private const val LIKE_SIDE = 96

    sealed interface Result {
        /** Fit to take in: [scale] times larger out than in, about [pageMs] for a page here. */
        data class Fit(val scale: Int, val pageMs: Long) : Result

        /**
         * Refused for [why]; for memory, the [bytes] it would need and the [limit] it would
         * pass; for time, the [pageMs] a page would take (0: unknown).
         */
        data class Refused(val why: ModelRefusal, val bytes: Long = 0, val limit: Long = 0, val pageMs: Long = 0) : Result
    }

    /**
     * [files] checked through [runtime] on [threads] CPU threads, within [memoryBytes], holding
     * the engine throughout ([TileEngine.exclusive]): no page runs between its steps. Throws
     * [CancellationException] once [canceled].
     */
    fun check(
        runtime: InferenceRuntime,
        files: ModelFiles,
        threads: Int,
        memoryBytes: Long,
        canceled: () -> Boolean = { false },
        clock: () -> Long = SystemClock::elapsedRealtime
    ): Result = TileEngine.exclusive(canceled) {
        // Timed from here: a page the reader is running is waited for, not counted.
        checkAlone(runtime, files, threads, memoryBytes, canceled, clock)
    }

    private fun checkAlone(
        runtime: InferenceRuntime, files: ModelFiles, threads: Int, memoryBytes: Long, canceled: () -> Boolean, clock: () -> Long
    ): Result {
        val deadline = clock() + TIME_LIMIT_MS
        val late = { clock() > deadline }
        val stop = { canceled() || late() }
        try {
            val opening = Sessions.attempt(runtime, files, threads, memoryBytes = memoryBytes, canceled = stop)
            val session = opening.session ?: return Result.Refused(opening.refusal ?: ModelRefusal.NOT_LOADED, opening.bytes, opening.limit)
            session.use { s ->
                val unlike = unlikeness(s, stop) ?: return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                Log.i(TAG, "${files.param.name}: output differs from its input by $unlike on average")
                if (unlike > UNLIKE_MEAN) return Result.Refused(ModelRefusal.UNLIKE)
                val ms = pageMs(s, stop, clock) ?: return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                Log.i(TAG, "${files.param.name}: about $ms ms for a ${PAGE_W}x$PAGE_H page")
                if (ms > ReaderAi.MAX_TIMEOUT_MS) return Result.Refused(ModelRefusal.TOO_SLOW, pageMs = ms)
                return Result.Fit(s.geometry.scale, ms)
            }
        } catch (e: CancellationException) {
            if (!canceled() && late()) return Result.Refused(ModelRefusal.TOO_SLOW)
            throw e
        }
    }

    /** How far [s]'s output, averaged back to its input's size, is from its input, in codes; null when it fails. */
    private fun unlikeness(s: Session, canceled: () -> Boolean): Double? {
        val src = picture(LIKE_SIDE)
        try {
            val out = TileEngine.run(s, src, PageLimits(timeoutMs = TIME_LIMIT_MS), canceled).bitmap ?: return null
            try {
                return meanDifference(src, out, s.geometry.scale)
            } finally {
                out.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    /** The mean channel difference, in codes, between [src] and [out] (it [scale] times larger) averaged over each [scale] x [scale] block. */
    internal fun meanDifference(src: Bitmap, out: Bitmap, scale: Int): Double {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val ow = out.width
        val b = IntArray(ow * out.height).also { out.getPixels(it, 0, ow, 0, 0, ow, out.height) }
        val n = scale * scale
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var bl = 0
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val p = b[(y * scale + dy) * ow + x * scale + dx]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                bl += p and 0xFF
            }
            val q = a[y * w + x]
            sum += abs(r / n - ((q shr 16) and 0xFF)) + abs(g / n - ((q shr 8) and 0xFF)) + abs(bl / n - (q and 0xFF))
        }
        return sum.toDouble() / (3L * w * h)
    }

    /** An opaque [side] x [side] picture: a smooth two-way gradient with a few hard dark and light shapes, as line art has. */
    internal fun picture(side: Int): Bitmap {
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), Color.rgb(240, 200, 150), Color.rgb(70, 120, 200), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.shader = null
        paint.color = Color.rgb(20, 20, 20)
        c.drawCircle(side * 0.3f, side * 0.35f, side * 0.12f, paint)
        paint.color = Color.WHITE
        c.drawRect(side * 0.55f, side * 0.5f, side * 0.85f, side * 0.7f, paint)
        b.setHasAlpha(false)
        return b
    }

    /**
     * [s]'s time for a synthetic page here: all its tiles when there are no more than
     * [TIMED_TILES], else the first once and the rest at the pace of the next few; null when
     * it fails. A model whose output of the page would pass [PageLimits.DEFAULT_MAX_OUTPUT_PIXELS]
     * is timed on a page [shrink] times smaller each way, its time taken [shrink] squared times.
     */
    private fun pageMs(s: Session, canceled: () -> Boolean, clock: () -> Long): Long? {
        val shrink = shrink(s.geometry.scale)
        val page = SyntheticPage.draw(PAGE_W / shrink, PAGE_H / shrink)
        try {
            val times = ArrayList<Long>()
            var planned = 0
            val run = TileEngine.run(
                s, page, PageLimits(timeoutMs = Long.MAX_VALUE), canceled, clock = clock,
                gate = {
                    val over = times.size >= 2 && estimate(times, planned) * shrink * shrink > ReaderAi.MAX_TIMEOUT_MS
                    if (times.size >= TIMED_TILES || over) Fallback.PAUSED else null
                },
                onTile = { _, of, ms ->
                    planned = of
                    times += ms
                }
            )
            run.bitmap?.let {
                it.recycle()
                return run.stats.totalMs * shrink * shrink
            }
            if (run.fallback != Fallback.PAUSED || times.size < 2) return null
            return estimate(times, planned) * shrink * shrink
        } finally {
            page.recycle()
        }
    }

    /** How many times smaller each way the timed page is for a model of [scale], so its output fits [PageLimits.DEFAULT_MAX_OUTPUT_PIXELS]. */
    internal fun shrink(scale: Int): Int {
        var k = 1
        while (PAGE_W.toLong() / k * (PAGE_H / k) * scale * scale > PageLimits.DEFAULT_MAX_OUTPUT_PIXELS) k++
        return k
    }

    /** A page of [planned] tiles from the [times] of its first: the first once, each other at the mean of the rest. */
    internal fun estimate(times: List<Long>, planned: Int): Long {
        if (times.size >= planned) return times.sum()
        val later = times.drop(1).average()
        return times[0] + (later * (planned - 1)).toLong()
    }
}
