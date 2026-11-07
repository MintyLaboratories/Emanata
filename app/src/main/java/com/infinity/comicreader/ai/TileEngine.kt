package com.infinity.comicreader.ai

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Why a page kept its plain (Lanczos3) rendering instead of a model's. */
enum class Fallback {
    /** The page would not finish within its time. */
    TIMEOUT,

    /** Its output would take more memory than a page may. */
    TOO_LARGE,

    /** It has transparency, which models do not keep. */
    TRANSLUCENT,

    /** Its pixels are not readable: recycled, or not ARGB_8888 (a hardware or 16-bit bitmap). */
    UNREADABLE,

    /** It is shown no larger than it is, so an upscale would be resampled away ([AiPages]). */
    UNNEEDED,

    /** Conditions say stop: the device is hot, or battery saver is on ([AiConditions]). */
    PAUSED,

    /** The model failed on a tile, or was closed. */
    FAILED,

    /** A model of its chain would not open ([ReaderAi]): tried again once the reader's models close. */
    UNOPENED
}

/** Bounds on one page's run: [timeoutMs] for all its tiles, at most [maxOutputPixels] of output. */
data class PageLimits(val timeoutMs: Long = DEFAULT_TIMEOUT_MS, val maxOutputPixels: Long = DEFAULT_MAX_OUTPUT_PIXELS) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** 32 MP, 128 MB of ARGB_8888: a 2000 x 4000 page at 2x. */
        const val DEFAULT_MAX_OUTPUT_PIXELS = 32L shl 20
    }
}

/** What one page's run did: tiles run of all planned, each tile's milliseconds, the whole run's. */
class RunStats(val tiles: Int, val planned: Int, val tileMs: LongArray, val totalMs: Long) {
    /**
     * The run's time for all [planned] tiles: [totalMs] once all ran; else, with at least
     * [TileEngine.PACE_AFTER] run, the time outside tiles, the first tile's (it carries the
     * GPU's warm-up) and the later ones' mean for each tile left; null with fewer.
     */
    val fullMs: Long?
        get() = when {
            planned <= 0 -> null
            tiles >= planned -> totalMs
            tiles < TileEngine.PACE_AFTER -> null
            else -> {
                val inTiles = tileMs.take(tiles).sum()
                val later = (inTiles - tileMs[0]).toDouble() / (tiles - 1)
                totalMs - inTiles + tileMs[0] + (later * (planned - 1)).toLong()
            }
        }

    companion object {
        /** A run that never started. */
        val NONE = RunStats(0, 0, LongArray(0), 0)
    }
}

/** A page's run: [bitmap] when it finished, else why not ([fallback]). */
class PageRun(val bitmap: Bitmap?, val fallback: Fallback?, val stats: RunStats)

/**
 * A model ready to run tiles: opened ([model]), measured ([geometry]), with its tile [size],
 * computing in fp16 when [fp16]. Closing it closes the model; a run in progress then falls back.
 */
internal class Session(val model: TileModel, val geometry: Geometry, val size: Int, val fp16: Boolean = false) : AutoCloseable {
    init {
        require(TilePlan.fits(size, geometry)) { "Tile $size too small for margin ${geometry.margin}" }
    }

    override fun close() = model.close()
}

/**
 * Runs a model over whole pages in fixed-size tiles ([TilePlan]), one page at a time in the
 * process: a run waits for the one before ([exclusive]). Between tiles a run stops for:
 * - [canceled]: throws [CancellationException], nothing kept;
 * - its time ([PageLimits.timeoutMs]): falls back, also as soon as the tiles left would
 *   take longer than the time left at the pace so far, so no work is spent on a page that
 *   would miss it anyway;
 * - [gate] (heat, battery saver): falls back;
 * - [hold] (the reader turning a page or zooming): waits, the time waited not counted, so
 *   the GPU leaves the reader's frames alone.
 * Output memory is bounded by [PageLimits.maxOutputPixels]; the model's own buffers are freed
 * after every page ([TileModel.trim]), on the GPU and the CPU.
 */
internal object TileEngine {
    private val lock = ReentrantLock(true)

    /** How often a waiting run checks whether it was canceled. */
    private const val WAIT_POLL_MS = 50L

    /** How often a held run checks whether it may go on. */
    private const val HOLD_POLL_MS = 16L

    /**
     * Tiles timed before the pace predicts a miss, the first left out of it: it carries the
     * GPU's warm-up, often several times a later tile's time.
     */
    internal const val PACE_AFTER = 4

    /** Rows of a page read at a time when looking for transparency. */
    private const val ALPHA_ROWS = 64

    /**
     * [block] run while no page or model check runs; throws [CancellationException] once
     * [canceled] while waiting for one.
     */
    fun <T> exclusive(canceled: () -> Boolean, block: () -> T): T {
        while (!lock.tryLock(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (canceled()) throw CancellationException("AI work canceled while waiting")
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** [src] through [session]'s model, the output [Geometry.scale] times its size. */
    fun run(
        session: Session,
        src: Bitmap,
        limits: PageLimits = PageLimits(),
        canceled: () -> Boolean = { false },
        gate: () -> Fallback? = { null },
        clock: () -> Long = SystemClock::elapsedRealtime,
        hold: () -> Boolean = { false },
        onTile: (done: Int, of: Int, ms: Long) -> Unit = { _, _, _ -> }
    ): PageRun = exclusive(canceled) {
        try {
            runAlone(session, src, limits, canceled, gate, clock, hold, onTile)
        } finally {
            session.model.trim()
        }
    }

    /** Waits while [hold] says so; throws [CancellationException] once [canceled]. The milliseconds waited by [clock]. */
    fun held(hold: () -> Boolean, canceled: () -> Boolean, clock: () -> Long = SystemClock::elapsedRealtime): Long {
        val t0 = clock()
        if (!hold()) return 0
        while (hold()) {
            if (canceled()) throw CancellationException("AI work canceled while held")
            Thread.sleep(HOLD_POLL_MS)
        }
        return clock() - t0
    }

    private fun runAlone(
        session: Session, src: Bitmap, limits: PageLimits, canceled: () -> Boolean, gate: () -> Fallback?,
        clock: () -> Long, hold: () -> Boolean, onTile: (Int, Int, Long) -> Unit
    ): PageRun {
        if (src.isRecycled || src.config != Bitmap.Config.ARGB_8888) return PageRun(null, Fallback.UNREADABLE, RunStats.NONE)
        val g = session.geometry
        val tiles = TilePlan.tiles(src.width, src.height, session.size, g)
        val times = LongArray(tiles.size)
        // Moved on by the time held, which counts toward neither the limit nor the pace.
        var start = clock()
        // Time spent once the first tile was done.
        var firstSpent = 0L
        fun stats(done: Int) = RunStats(done, tiles.size, times.copyOf(done), clock() - start)
        fun fallback(why: Fallback, done: Int) = PageRun(null, why, stats(done))

        val outW = src.width.toLong() * g.scale
        val outH = src.height.toLong() * g.scale
        if (outW * outH > limits.maxOutputPixels) return fallback(Fallback.TOO_LARGE, 0)
        gate()?.let { return fallback(it, 0) }
        if (!opaque(src)) return fallback(Fallback.TRANSLUCENT, 0)
        val out = try {
            Bitmap.createBitmap(outW.toInt(), outH.toInt(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return fallback(Fallback.TOO_LARGE, 0)
        }
        var kept = false
        try {
            for ((i, t) in tiles.withIndex()) {
                if (canceled()) throw CancellationException("AI page canceled")
                start += held(hold, canceled, clock)
                if (i > 0) {
                    gate()?.let { return fallback(it, i) }
                    val spent = clock() - start
                    val left = limits.timeoutMs - spent
                    if (i == 1) firstSpent = spent
                    // The tiles left at the pace of those after the first.
                    val ahead = if (i >= PACE_AFTER) (spent - firstSpent).toDouble() / (i - 1) * (tiles.size - i) else 0.0
                    if (left <= 0 || ahead > left) {
                        return fallback(Fallback.TIMEOUT, i)
                    }
                }
                val t0 = clock()
                session.model.run(src, t.input, out, t.crop, t.dx, t.dy) ?: return fallback(Fallback.FAILED, i)
                times[i] = clock() - t0
                onTile(i + 1, tiles.size, times[i])
            }
            out.setHasAlpha(false)
            kept = true
            return PageRun(out, null, stats(tiles.size))
        } finally {
            if (!kept) out.recycle()
        }
    }

    /**
     * True when every pixel of [src] is opaque: at once when it says it has no alpha, else by
     * reading its alpha, [ALPHA_ROWS] rows at a time (decoders mark opaque RGBA pages as
     * having alpha).
     */
    fun opaque(src: Bitmap): Boolean {
        if (!src.hasAlpha()) return true
        val w = src.width
        val rows = IntArray(w * minOf(ALPHA_ROWS, src.height))
        var y = 0
        while (y < src.height) {
            val n = minOf(ALPHA_ROWS, src.height - y)
            src.getPixels(rows, 0, w, 0, y, w, n)
            for (i in 0 until w * n) if (rows[i] ushr 24 != 0xFF) return false
            y += n
        }
        return true
    }
}
