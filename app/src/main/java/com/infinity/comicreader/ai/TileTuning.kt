package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.max

/**
 * A model's tile sizes to choose among on the GPU ([candidates]; those past [TileSizing.DEFAULT_MAX]
 * only where they fit), and where what this device learns of them is kept: [choices], under
 * [key] (what identifies the model's files).
 */
internal class TileTuning(val candidates: List<Int>, val choices: TileChoices, val key: String)

/**
 * What this device learned of one model's tiles on its GPU in one precision: the [size] chosen
 * (0: none yet, so the next open times the candidates), the largest size that may run
 * ([ceiling]: a larger one failed to run, or the app ended while running it), and the size
 * being tried ([trying], 0: none), written before a size not yet known to run is tried and
 * cleared after, so a size that ends the app (out of memory) is not tried again; [timings]:
 * the times the sizes were timed.
 */
internal data class TileChoice(val size: Int = 0, val ceiling: Int = Int.MAX_VALUE, val trying: Int = 0, val timings: Int = 0) {
    /** As an open finds it: a size left [trying] ended the app, so the ceiling falls below it and a choice at or past it is dropped. */
    fun settled(): TileChoice = if (trying <= 0) this else below(trying)

    /** After [failed] did not run: the ceiling below it, a choice at or past it dropped. */
    fun below(failed: Int): TileChoice = copy(size = if (size >= failed) 0 else size, ceiling = minOf(ceiling, failed - 1), trying = 0)
}

/**
 * [TileChoice]s by key, one small file each under [dir]: kept across runs, never backed up
 * (another device learns its own), written whole so a file is never half written.
 */
internal class TileChoices(private val dir: File) {
    /** The choice kept under [key]; none learned yet when missing or unreadable. */
    @Synchronized
    fun get(key: String): TileChoice = runCatching {
        val v = fileOf(key).readText().trim().split(' ').map { it.toInt() }
        TileChoice(v[0], v[1], v[2], v[3]).takeIf { v.size == 4 && it.size >= 0 && it.ceiling > 0 && it.trying >= 0 && it.timings >= 0 }
    }.getOrNull() ?: TileChoice()

    /** Keeps [choice] under [key]; a failed write is logged and forgotten. */
    @Synchronized
    fun put(key: String, choice: TileChoice) {
        val file = fileOf(key)
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(String.format(Locale.ROOT, "%d %d %d %d\n", choice.size, choice.ceiling, choice.trying, choice.timings))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.w(TAG, "cannot keep tile choice: ${it.message}") }
    }

    private fun fileOf(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash.take(32))
    }

    companion object {
        private const val TAG = "AiTiles"

        /** Where [context] keeps them. */
        fun dirIn(context: Context): File = File(context.noBackupFilesDir, "tile-choices")
    }
}

/** A [TileChoice] in [choices] under [key], read once and settled ([TileChoice.settled]) as an open starts. */
internal class TileSlot(private val choices: TileChoices, private val key: String) {
    @Volatile
    var choice: TileChoice = choices.get(key).let { c -> c.settled().also { if (it != c) choices.put(key, it) } }
        private set

    fun save(c: TileChoice) {
        if (c == choice) return
        choice = c
        choices.put(key, c)
    }

    /** Marks [size] as being tried (0: none), before a run that may end the app. */
    fun trying(size: Int) = save(choice.copy(trying = size))

    /** Remembers that [size] did not run: smaller tiles from now on. */
    fun failed(size: Int) = save(choice.below(size))

    /** Drops the choice, so the next open times the sizes again. */
    fun retime() = save(choice.copy(size = 0))
}

/**
 * Times a model's tile sizes on this device ([time]) and ranks them ([rank]): first the size
 * whose tiles cost the least per pixel kept, among those whose tiles end within [TILE_MS] (at
 * the median; none past [SPIKE] times that: tiles are where a run stops for a cancel, a page
 * turn or the reader's frames) and hold no more working memory than the budget. Sizes are
 * timed in turn, round after round, so a device whose speed drifts over minutes (heat, clocks)
 * treats them alike. A choice timed while the device was slow is timed again once pages show
 * the next larger size would now end its tiles in time ([roomFor]), at most [MAX_TIMINGS]
 * times in all.
 */
internal object TileTiming {
    /**
     * The longest a tile may take, in ms, as for shader chains ([ShaderSessions.TILE_MS]): the
     * wait before a cancel or a page turn takes effect.
     */
    const val TILE_MS = 150L

    /** Timed runs of each size, after one untimed (its first run sets up the GPU's work for that shape). */
    const val RUNS = 3

    /** Costs within this share of the least count as equal: the smaller size wins, with the shorter stop and less memory. */
    const val TIE = 0.05

    /** A size whose tile would take more than this many times [TILE_MS] at the pace of the size below is not tried. */
    const val SKIP_PAST = 2.0

    /** Times past [TILE_MS] the slowest timed tile of a size may take. */
    const val SPIKE = 2

    /** The most times a model's sizes are timed on a device. */
    const val MAX_TIMINGS = 3

    /** Tiles of a page, past its first (the GPU's warm-up), whose pace [roomFor] reads at least. */
    const val PACED_TILES = 3

    /** A size's timed runs ([ms]), whether a run of it [failed], and the most working memory one held ([peakBytes]; 0 where not counted). */
    class Timing(val size: Int, val ms: List<Long>, val failed: Boolean = false, val peakBytes: Long = 0) {
        val worst: Long get() = ms.maxOrNull() ?: Long.MAX_VALUE
        val median: Long get() = ms.sorted().let { if (it.isEmpty()) Long.MAX_VALUE else it[it.size / 2] }

        override fun toString() = if (failed) "$size px failed" else "$size px ${ms.joinToString("/")} ms, ${peakBytes shr 20} MB"
    }

    /**
     * [model]'s tiles of each of [sizes] at [geometry], on noise, read back whole as a page's
     * tiles are: smallest first, each run once untimed and once timed, stopping at a size that
     * fails, takes past [SPIKE] times [TILE_MS], or would take past [SKIP_PAST] times that at
     * the least time per pixel of the sizes below; then [RUNS] - 1 more rounds over those that
     * may still pass [rank]'s bounds (one slow run is not the median), each size again run
     * once untimed before it is timed (a page's tiles all have one shape), its buffers freed
     * before another size runs (as after a page). [trying] hears each
     * size before its first run. A size that fails is timed no more; failing in the first round,
     * it ends the climb.
     * Throws [CancellationException] once [canceled], checked before every run.
     */
    fun time(
        model: TileModel, sizes: List<Int>, geometry: Geometry, clock: () -> Long, canceled: () -> Boolean, trying: (Int) -> Unit = {}
    ): List<Timing> {
        val asc = sizes.distinct().filter { TilePlan.fits(it, geometry) }.sorted()
        if (asc.isEmpty()) return emptyList()
        val largest = asc.last()
        val src = ModelProbe.content(largest, largest, ModelProbe.Pattern.NOISE)
        val side = TilePlan.outputSize(largest, geometry)
        val dst = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val runs = LinkedHashMap<Int, MutableList<Long>>()
        val peaks = HashMap<Int, Long>()
        val failed = HashSet<Int>()
        var shape = 0
        fun run(size: Int): Long? {
            if (canceled()) throw CancellationException("AI work canceled while timing tiles")
            val out = TilePlan.outputSize(size, geometry)
            // A new size: the last one's buffers freed first, so the GPU holds one size's at a time and each peak is its own.
            if (size != shape) {
                if (shape != 0) model.trim()
                shape = size
            }
            model.takePeak()
            val t0 = clock()
            val ran = model.run(src, Box(0, 0, size, size), dst, Box(0, 0, out, out), 0, 0) != null
            val ms = clock() - t0
            peaks[size] = max(peaks[size] ?: 0L, model.takePeak())
            return if (ran) ms else null
        }
        try {
            for (size in asc) {
                // The least time per pixel so far, at this size: one slow sample of a size below does not end the climb.
                val pace = runs.minOfOrNull { (s, ms) -> ms.min().toDouble() / s / s }
                if (pace != null && pace * size * size > TILE_MS * SKIP_PAST) break
                trying(size)
                // Once untimed, then timed.
                val ms = run(size)?.let { run(size) }
                if (ms == null) {
                    failed += size
                    break
                }
                runs[size] = mutableListOf(ms)
                if (ms > TILE_MS * SPIKE) break
            }
            repeat(RUNS - 1) {
                for ((size, ms) in runs) {
                    if (size in failed || !mayPass(ms)) continue
                    // Once untimed after the shape changed, as a page's tiles follow one of their own shape.
                    val t = run(size)?.let { run(size) }
                    if (t == null) failed += size else ms += t
                }
            }
        } finally {
            dst.recycle()
            src.recycle()
            model.trim()
        }
        val timed = runs.map { (size, ms) -> Timing(size, ms, size in failed, peaks[size] ?: 0L) }
        return timed + failed.filter { it !in runs }.map { Timing(it, emptyList(), true, peaks[it] ?: 0L) }
    }

    /** True while a size with timed runs [ms] may still pass [rank]'s bounds after all [RUNS]: none past [SPIKE], few enough past [TILE_MS] for the median. */
    private fun mayPass(ms: List<Long>): Boolean = ms.none { it > TILE_MS * SPIKE } && ms.count { it > TILE_MS } <= (RUNS - 1) / 2

    /**
     * The size of [timings] to blame for a failed run, as too large for this device: the
     * smallest that failed past [maxTile]; none when a size up to [maxTile] failed too (the GPU
     * failed, not the size).
     */
    fun blamed(timings: List<Timing>, maxTile: Int): Int? {
        val failed = timings.filter { it.failed }.map { it.size }
        return if (failed.any { it <= maxTile }) null else failed.minOrNull()
    }

    /**
     * Sizes of [timings] to try at [geometry], best first: those that ran within [TILE_MS] (the
     * median; every run within [SPIKE] times it) and [budgetBytes], by time per pixel of core;
     * of costs within [TIE] of the least, the smallest size first. Empty when none qualifies.
     */
    fun rank(timings: List<Timing>, geometry: Geometry, budgetBytes: Long = Long.MAX_VALUE): List<Int> {
        val cost = timings.filter {
            !it.failed && it.ms.isNotEmpty() && it.median <= TILE_MS && it.worst <= TILE_MS * SPIKE && it.peakBytes <= budgetBytes &&
                TilePlan.fits(it.size, geometry)
        }
            .associate { t -> TilePlan.core(t.size, geometry).toDouble().let { core -> t.size to t.median / (core * core) } }
        if (cost.isEmpty()) return emptyList()
        val least = cost.values.min()
        val best = cost.filterValues { it <= least * (1 + TIE) }.keys.min()
        return listOf(best) + cost.entries.sortedBy { it.value }.map { it.key }.filter { it != best }
    }

    /**
     * True when a page run in [size] tiles ([stats]) says tiles of [next] would end within
     * [TILE_MS]: its tiles past the first, at least [PACED_TILES], at their median, grown by the
     * area (an upper bound: part of a tile's time is the same at any size).
     */
    fun roomFor(stats: RunStats, size: Int, next: Int): Boolean {
        val later = stats.tileMs.take(stats.tiles).drop(1).sorted()
        if (later.size < PACED_TILES) return false
        return later[later.size / 2] * (next.toDouble() / size) * (next.toDouble() / size) <= TILE_MS
    }
}
