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
 * cleared after, so a size that ends the app (out of memory) is not tried again.
 */
internal data class TileChoice(val size: Int = 0, val ceiling: Int = Int.MAX_VALUE, val trying: Int = 0) {
    /** As an open finds it: a size left [trying] ended the app, so the ceiling falls below it and a choice at or past it is dropped. */
    fun settled(): TileChoice = if (trying <= 0) this else TileChoice(if (size >= trying) 0 else size, minOf(ceiling, trying - 1), 0)

    /** After [failed] did not run: the ceiling below it, a choice at or past it dropped. */
    fun below(failed: Int): TileChoice = TileChoice(if (size >= failed) 0 else size, minOf(ceiling, failed - 1), 0)
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
        TileChoice(v[0], v[1], v[2]).takeIf { v.size == 3 && it.size >= 0 && it.ceiling > 0 && it.trying >= 0 }
    }.getOrNull() ?: TileChoice()

    /** Keeps [choice] under [key]; a failed write is logged and forgotten. */
    @Synchronized
    fun put(key: String, choice: TileChoice) {
        val file = fileOf(key)
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(String.format(Locale.ROOT, "%d %d %d\n", choice.size, choice.ceiling, choice.trying))
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
}

/**
 * Times a model's tile sizes on this device ([time]) and ranks them ([rank]): first the size
 * whose tiles cost the least per pixel kept, among those whose tiles all end within [TILE_MS]
 * (tiles are where a run stops for a cancel, a page turn or the reader's frames) and hold no
 * more working memory than the budget. Sizes are timed in turn, round after round, so a device
 * whose speed drifts over minutes (heat, clocks) treats them alike.
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

    /** A size's timed runs ([ms]), whether a run of it [failed], and the most working memory one held ([peakBytes]; 0 where not counted). */
    class Timing(val size: Int, val ms: List<Long>, val failed: Boolean = false, val peakBytes: Long = 0) {
        val worst: Long get() = ms.maxOrNull() ?: Long.MAX_VALUE
        val median: Long get() = ms.sorted().let { if (it.isEmpty()) Long.MAX_VALUE else it[it.size / 2] }

        override fun toString() = if (failed) "$size px failed" else "$size px ${ms.joinToString("/")} ms, ${peakBytes shr 20} MB"
    }

    /**
     * [model]'s tiles of each of [sizes] at [geometry], on noise, read back whole as a page's
     * tiles are: smallest first, each run once untimed and once timed, stopping at a size that
     * fails, takes past [TILE_MS], or would take past [SKIP_PAST] times that at the pace of the
     * size below; then [RUNS] - 1 more rounds over those within [TILE_MS], each size again run
     * once untimed before it is timed (a page's tiles all have one shape). [trying] hears each
     * size before its first run. A size that fails ends the timing of it and every larger one.
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
        var failedAt = Int.MAX_VALUE
        fun run(size: Int): Long? {
            if (canceled()) throw CancellationException("AI work canceled while timing tiles")
            val out = TilePlan.outputSize(size, geometry)
            model.takePeak()
            val t0 = clock()
            val ran = model.run(src, Box(0, 0, size, size), dst, Box(0, 0, out, out), 0, 0) != null
            val ms = clock() - t0
            peaks[size] = max(peaks[size] ?: 0L, model.takePeak())
            return if (ran) ms else null
        }
        try {
            var below: Pair<Int, Long>? = null
            for (size in asc) {
                val b = below
                if (b != null && b.second * (size.toDouble() / b.first) * (size.toDouble() / b.first) > TILE_MS * SKIP_PAST) break
                trying(size)
                // Once untimed, then timed.
                val ms = run(size)?.let { run(size) }
                if (ms == null) {
                    failedAt = size
                    break
                }
                runs[size] = mutableListOf(ms)
                below = size to ms
                if (ms > TILE_MS) break
            }
            repeat(RUNS - 1) {
                for ((size, ms) in runs) {
                    if (size >= failedAt || ms.max() > TILE_MS) continue
                    // Once untimed after the shape changed, as a page's tiles follow one of their own shape.
                    val t = run(size)?.let { run(size) }
                    if (t == null) failedAt = size else ms += t
                }
            }
        } finally {
            dst.recycle()
            src.recycle()
            model.trim()
        }
        val timed = runs.map { (size, ms) -> Timing(size, ms, size >= failedAt, peaks[size] ?: 0L) }
        return if (failedAt in asc && failedAt !in runs) timed + Timing(failedAt, emptyList(), true, peaks[failedAt] ?: 0L) else timed
    }

    /**
     * Sizes of [timings] to try at [geometry], best first: those that ran, every run within
     * [TILE_MS] and [budgetBytes], by time per pixel of core; of costs within [TIE] of the least,
     * the smallest size first. Empty when none qualifies.
     */
    fun rank(timings: List<Timing>, geometry: Geometry, budgetBytes: Long = Long.MAX_VALUE): List<Int> {
        val cost = timings.filter { !it.failed && it.ms.isNotEmpty() && it.worst <= TILE_MS && it.peakBytes <= budgetBytes && TilePlan.fits(it.size, geometry) }
            .associate { t -> TilePlan.core(t.size, geometry).toDouble().let { core -> t.size to t.median / (core * core) } }
        if (cost.isEmpty()) return emptyList()
        val least = cost.values.min()
        val best = cost.filterValues { it <= least * (1 + TIE) }.keys.min()
        return listOf(best) + cost.entries.sortedBy { it.value }.map { it.key }.filter { it != best }
    }
}
