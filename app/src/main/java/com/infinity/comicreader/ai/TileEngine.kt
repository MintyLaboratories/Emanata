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

    /** Conditions say stop: the device is hot, or battery saver is on ([AiConditions]). */
    PAUSED,

    /** The model failed on a tile. */
    FAILED
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
class RunStats(val tiles: Int, val planned: Int, val tileMs: LongArray, val totalMs: Long)

/** A page's run: [bitmap] when it finished, else why not ([fallback]). */
class PageRun(val bitmap: Bitmap?, val fallback: Fallback?, val stats: RunStats)

/**
 * A model ready to run tiles: opened ([model]), measured ([geometry]), with its tile [size].
 * Closing it closes the model.
 */
internal class Session(val model: TileModel, val geometry: Geometry, val size: Int) : AutoCloseable {
    init {
        require(TilePlan.fits(size, geometry)) { "Tile $size too small for margin ${geometry.margin}" }
    }

    override fun close() = model.close()
}

/**
 * Runs a model over whole pages in fixed-size tiles ([TilePlan]), one page at a time in the
 * process: a run waits for the one before. Between tiles a run stops for:
 * - [canceled]: throws [CancellationException], nothing kept;
 * - its time ([PageLimits.timeoutMs]): falls back, also as soon as the tiles left would
 *   take longer than the time left at the pace so far, so no work is spent on a page that
 *   would miss it anyway;
 * - [gate] (heat, battery saver): falls back.
 * Output memory is bounded by [PageLimits.maxOutputPixels]; the model's own buffers are freed
 * after every page ([TileModel.trim]), on the GPU and the CPU.
 */
internal object TileEngine {
    private val lock = ReentrantLock(true)

    /** How often a waiting run checks whether it was canceled. */
    private const val WAIT_POLL_MS = 50L

    /** Tiles timed before the pace predicts a miss. */
    private const val PACE_AFTER = 2

    /** [src] through [session]'s model, the output [Geometry.scale] times its size. */
    fun run(
        session: Session,
        src: Bitmap,
        limits: PageLimits = PageLimits(),
        canceled: () -> Boolean = { false },
        gate: () -> Fallback? = { null },
        clock: () -> Long = SystemClock::elapsedRealtime,
        onTile: (done: Int, of: Int, ms: Long) -> Unit = { _, _, _ -> }
    ): PageRun {
        while (!lock.tryLock(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (canceled()) throw CancellationException("AI page canceled while waiting")
        }
        try {
            return runLocked(session, src, limits, canceled, gate, clock, onTile)
        } finally {
            session.model.trim()
            lock.unlock()
        }
    }

    private fun runLocked(
        session: Session, src: Bitmap, limits: PageLimits, canceled: () -> Boolean, gate: () -> Fallback?,
        clock: () -> Long, onTile: (Int, Int, Long) -> Unit
    ): PageRun {
        val g = session.geometry
        val tiles = TilePlan.tiles(src.width, src.height, session.size, g)
        val times = LongArray(tiles.size)
        val start = clock()
        fun stats(done: Int) = RunStats(done, tiles.size, times.copyOf(done), clock() - start)
        fun fallback(why: Fallback, done: Int) = PageRun(null, why, stats(done))

        if (src.hasAlpha()) return fallback(Fallback.TRANSLUCENT, 0)
        val outW = src.width.toLong() * g.scale
        val outH = src.height.toLong() * g.scale
        if (outW * outH > limits.maxOutputPixels) return fallback(Fallback.TOO_LARGE, 0)
        gate()?.let { return fallback(it, 0) }
        val out = try {
            Bitmap.createBitmap(outW.toInt(), outH.toInt(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return fallback(Fallback.TOO_LARGE, 0)
        }
        var kept = false
        try {
            for ((i, t) in tiles.withIndex()) {
                if (canceled()) throw CancellationException("AI page canceled")
                if (i > 0) {
                    gate()?.let { return fallback(it, i) }
                    val spent = clock() - start
                    val left = limits.timeoutMs - spent
                    if (left <= 0 || (i >= PACE_AFTER && spent.toDouble() * (tiles.size - i) > left.toDouble() * i)) {
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
}
