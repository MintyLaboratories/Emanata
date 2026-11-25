package minty.emanata.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import minty.emanata.reader.SyntheticPage
import java.util.concurrent.CancellationException

/**
 * Checks a model the user imports before it is taken in: it opens as every model does
 * ([Sessions.attempt]: scale, edge trim, context, memory, the GPU against the CPU), then
 * - it runs on the first of the import's test pictures, cut to one tile ([oneTile]), or without
 *   them on a small RGB picture, a smooth gradient with a few hard shapes; the other test
 *   pictures run only once it is found fit, so a refused model spends no more; how its output
 *   looks is not judged: the pictures' before and after ([Preview]) are for the user to weigh;
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

    /** A test picture [before], cut to one tile, and the model's output of it, [after]. */
    data class Preview(val before: Bitmap, val after: Bitmap)

    sealed interface Result {
        /**
         * Fit to take in: [scale] times larger out than in, about [pageMs] for a page here, timed
         * at thermal [headroom] (NaN: unknown); [warnings] for the user to weigh first; the
         * [previews] of the test pictures it was given, in their order.
         */
        data class Fit(
            val scale: Int,
            val pageMs: Long,
            val headroom: Float = Float.NaN,
            val warnings: Set<ImportWarning> = emptySet(),
            val previews: List<Preview> = emptyList()
        ) : Result

        /**
         * Refused for [why]; for memory, the [bytes] it would need and the [limit] it would
         * pass; for time, the [pageMs] a page would take (0: unknown).
         */
        data class Refused(val why: ModelRefusal, val bytes: Long = 0, val limit: Long = 0, val pageMs: Long = 0) : Result
    }

    /**
     * [files] checked through [runtime] on [threads] CPU threads, within [memoryBytes], holding
     * the engine throughout ([TileEngine.exclusive]): no page runs between its steps; the
     * device's thermal [headroom] read as timing starts; its [Preview]s of the test [pictures]
     * (opaque ARGB_8888, left to the caller). Throws [CancellationException] once [canceled].
     */
    fun check(
        runtime: InferenceRuntime,
        files: ModelFiles,
        threads: Int,
        memoryBytes: Long,
        canceled: () -> Boolean = { false },
        clock: () -> Long = SystemClock::elapsedRealtime,
        headroom: () -> Float = { Float.NaN },
        pictures: List<Bitmap> = emptyList()
    ): Result = TileEngine.exclusive(canceled) {
        // Timed from here: a page the reader is running is waited for, not counted.
        checkAlone(runtime, files, threads, memoryBytes, canceled, clock, headroom, pictures)
    }

    private fun checkAlone(
        runtime: InferenceRuntime, files: ModelFiles, threads: Int, memoryBytes: Long, canceled: () -> Boolean, clock: () -> Long,
        headroom: () -> Float, pictures: List<Bitmap>
    ): Result {
        val deadline = clock() + TIME_LIMIT_MS
        val late = { clock() > deadline }
        val stop = { canceled() || late() }
        try {
            val opening = Sessions.attempt(runtime, files, threads, memoryBytes = memoryBytes, canceled = stop)
            val session = opening.session ?: return Result.Refused(opening.refusal ?: ModelRefusal.NOT_LOADED, opening.bytes, opening.limit)
            session.use { s ->
                val made = ArrayList<Preview>()
                var kept = false
                try {
                    if (pictures.isEmpty()) {
                        if (!runs(s, stop)) return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                    } else {
                        made += preview(s, pictures[0], stop) ?: return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                    }
                    val heat = headroom()
                    val ms = pageMs(s, stop, clock) ?: return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                    Log.i(TAG, "${files.param.name}: about $ms ms for a ${PAGE_W}x$PAGE_H page, from thermal headroom $heat")
                    val r = when (val j = judge(s.geometry.scale, ms, heat)) {
                        is Result.Fit -> j
                        is Result.Refused -> return j
                    }
                    for (p in pictures.drop(1)) made += preview(s, p, stop) ?: return Result.Refused(ModelRefusal.DOES_NOT_RUN)
                    kept = true
                    return r.copy(previews = made)
                } finally {
                    if (!kept) for (p in made) drop(p, pictures)
                }
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

    /**
     * [s]'s output of [picture], cut to one tile first ([oneTile]), gray output collapsed to
     * gray as the reader's is; null when it fails.
     */
    private fun preview(s: Session, picture: Bitmap, canceled: () -> Boolean): Preview? {
        val before = oneTile(s, picture)
        var after: Bitmap? = null
        try {
            after = TileEngine.run(s, before, PageLimits(timeoutMs = TIME_LIMIT_MS), canceled, gray = null).bitmap
            return after?.let { Preview(before, it) }
        } finally {
            if (after == null && before !== picture) before.recycle()
        }
    }

    /** Recycles [p]'s bitmaps, but not a before that is one of the caller's [pictures]. */
    private fun drop(p: Preview, pictures: List<Bitmap>) {
        p.after.recycle()
        if (pictures.none { it === p.before }) p.before.recycle()
    }

    /** The middle of [p], cut to the largest square [s] runs in one tile; [p] itself when it fits whole. */
    internal fun oneTile(s: Session, p: Bitmap): Bitmap {
        var side = minOf(p.width, p.height)
        while (true) {
            val core = TilePlan.core(s.sizeFor(side, side), s.geometry)
            if (side <= core) break
            side = core
        }
        if (side == p.width && side == p.height) return p
        return Bitmap.createBitmap(p, (p.width - side) / 2, (p.height - side) / 2, side, side)
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
