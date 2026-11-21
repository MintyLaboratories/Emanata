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

/**
 * Checks a model the user imports before it is taken in: it opens as every model does
 * ([Sessions.attempt]: scale, edge trim, context, memory, the GPU against the CPU), then
 * - it runs on a small RGB picture, a smooth gradient with a few hard shapes; how its output
 *   looks is not judged;
 * - it is timed on a synthetic [PAGE_W] x [PAGE_H] page, the size catalog models are timed
 *   on: the whole page while its pace says it takes at most [FULL_PAGE_MS], else from its
 *   first [TIMED_TILES] tiles, the first (the GPU's warm-up) counted once and the rest at
 *   their pace; stopping sooner once the page would take longer than
 *   [ReaderAi.MAX_TIMEOUT_MS], the most the reader gives a page: such a model is refused,
 *   as none of its pages could show. Past [WARN_PAGE_MS] it is fit with a warning
 *   ([ImportWarning.SLOW]). One measurement at whatever heat the device has then (its
 *   thermal headroom is kept beside it): a rough figure.
 * All within [TIME_LIMIT_MS], else the model is refused as too slow.
 */
internal object ModelCheck {
    private const val TAG = "ModelCheck"

    /** The longest a check may take. */
    const val TIME_LIMIT_MS = 120_000L

    /** The page time past which a model is warned of as slow. */
    const val WARN_PAGE_MS = 30_000L

    /** Tiles timed at least. */
    const val TIMED_TILES = TileEngine.PACE_AFTER + 2

    /** The longest page timed whole; a slower one is estimated from its first tiles. */
    const val FULL_PAGE_MS = 20_000L

    const val PAGE_W = 1000
    const val PAGE_H = 1500

    /** The side of the picture it is first run on. */
    private const val PICTURE_SIDE = 96

    sealed interface Result {
        /**
         * Fit to take in: [scale] times larger out than in, about [pageMs] for a page here, timed
         * at thermal [headroom] (NaN: unknown); [warnings] for the user to weigh first.
         */
        data class Fit(val scale: Int, val pageMs: Long, val headroom: Float = Float.NaN, val warnings: Set<ImportWarning> = emptySet()) : Result

        /**
         * Refused for [why]; for memory, the [bytes] it would need and the [limit] it would
         * pass; for time, the [pageMs] a page would take (0: unknown).
         */
        data class Refused(val why: ModelRefusal, val bytes: Long = 0, val limit: Long = 0, val pageMs: Long = 0) : Result
    }

    /**
     * [files] checked through [runtime] on [threads] CPU threads, within [memoryBytes], holding
     * the engine throughout ([TileEngine.exclusive]): no page runs between its steps; the
     * device's thermal [headroom] read as timing starts. Throws [CancellationException] once
     * [canceled].
     */
    fun check(
        runtime: InferenceRuntime,
        files: ModelFiles,
        threads: Int,
        memoryBytes: Long,
        canceled: () -> Boolean = { false },
        clock: () -> Long = SystemClock::elapsedRealtime,
        headroom: () -> Float = { Float.NaN }
    ): Result = TileEngine.exclusive(canceled) {
        // Timed from here: a page the reader is running is waited for, not counted.
        checkAlone(runtime, files, threads, memoryBytes, canceled, clock, headroom)
    }

    private fun checkAlone(
        runtime: InferenceRuntime, files: ModelFiles, threads: Int, memoryBytes: Long, canceled: () -> Boolean, clock: () -> Long,
        headroom: () -> Float
    ): Result {
        val deadline = clock() + TIME_LIMIT_MS
        val late = { clock() > deadline }
        val stop = { canceled() || late() }
        try {
            val opening = Sessions.attempt(runtime, files, threads, memoryBytes = memoryBytes, canceled = stop)
            val session = opening.session ?: return Result.Refused(opening.refusal ?: ModelRefusal.NOT_LOADED, opening.bytes, opening.limit)
            session.use { s ->
                if (!runs(s, stop)) return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                val heat = headroom()
                val ms = pageMs(s, stop, clock) ?: return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                Log.i(TAG, "${files.param.name}: about $ms ms for a ${PAGE_W}x$PAGE_H page, from thermal headroom $heat")
                return judge(s.geometry.scale, ms, heat)
            }
        } catch (e: CancellationException) {
            if (!canceled() && late()) return Result.Refused(ModelRefusal.TOO_SLOW)
            throw e
        }
    }

    /**
     * A model of [scale] whose page takes [pageMs] here, timed at [headroom]: refused past
     * [ReaderAi.MAX_TIMEOUT_MS], else fit, warned of past [WARN_PAGE_MS].
     */
    internal fun judge(scale: Int, pageMs: Long, headroom: Float = Float.NaN): Result {
        if (pageMs > ReaderAi.MAX_TIMEOUT_MS) return Result.Refused(ModelRefusal.TOO_SLOW, pageMs = pageMs)
        val warnings = if (pageMs > WARN_PAGE_MS) setOf(ImportWarning.SLOW) else emptySet()
        return Result.Fit(scale, pageMs, headroom, warnings)
    }

    /** True when [s] runs on an RGB picture and gives output. */
    private fun runs(s: Session, canceled: () -> Boolean): Boolean {
        val src = picture(PICTURE_SIDE)
        try {
            val out = TileEngine.run(s, src, PageLimits(timeoutMs = TIME_LIMIT_MS), canceled).bitmap ?: return false
            out.recycle()
            return true
        } finally {
            src.recycle()
        }
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
     * [s]'s time for a synthetic page here: all its tiles while the page's pace keeps it within
     * [FULL_PAGE_MS], else, once [TIMED_TILES] are timed, the first once and the rest at their
     * pace; null when it fails. A model whose output of the page would pass [PageLimits.DEFAULT_MAX_OUTPUT_PIXELS]
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
                gate = { if (enough(times, planned, shrink)) Fallback.PAUSED else null },
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

    /**
     * True once the [times] of a page of [planned] tiles, timed [shrink] times smaller each way,
     * say enough: past [ReaderAi.MAX_TIMEOUT_MS], or past [FULL_PAGE_MS] after [TIMED_TILES].
     */
    internal fun enough(times: List<Long>, planned: Int, shrink: Int): Boolean {
        if (times.size < 2) return false
        val page = estimate(times, planned) * shrink * shrink
        return page > ReaderAi.MAX_TIMEOUT_MS || (times.size >= TIMED_TILES && page > FULL_PAGE_MS)
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
