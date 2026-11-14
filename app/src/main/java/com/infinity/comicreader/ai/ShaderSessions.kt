package com.infinity.comicreader.ai

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Opens shader chains ([ShaderModel]) as [Session]s for the tile engine, as [Sessions] opens
 * ncnn models: the files parsed ([ShaderHooks]) and planned ([ShaderChain]), the passes
 * compiled ([ShaderRuntime.open]), the chain measured on the GPU ([ModelProbe.probeExact]: a
 * chain computes each pixel alike wherever it sits in a tile, so its whole reach is found
 * exactly and no CPU reference is needed), then checked for seams at the tile size chosen
 * ([ModelProbe.verify]: at most [SEAM_MAX] codes), a margin wider by [TilePlan.ALIGN] up to
 * [MAX_WIDENINGS] times when they show.
 *
 * Tiles, not whole pages: the passes keep many full-size float textures alive at once (an
 * Anime4K UL chain about twenty at 8 bytes a pixel, plus the enlarged image), so a whole page
 * of the 8 MP a chain may read would need well over a gigabyte of GPU memory; tiles bound it
 * to tens of megabytes ([ShaderPlan.bytes]) and give the engine its chances to stop, wait and
 * pace between them. The session's tile size is the most a tile may be; each page then takes
 * the size up to it that leaves the least work ([pageTile]): a chain computes a pixel alike
 * at any place in a tile of any size, so one size checked for seams vouches for the others.
 */
internal object ShaderSessions {
    private const val TAG = "AiShaders"

    /** Tile sizes tried, largest first. */
    val SIZES = intArrayOf(1024, 768, 512, 384, 256, 192, 128, 96, 64)

    /**
     * The largest tile by default: its textures (an Anime4K UL chain's) about 60 MB, margins of
     * 20 pixels keeping 85 % of it, a 1000 x 1500 page in about a dozen tiles.
     */
    const val DEFAULT_MAX = 512

    /**
     * What a tile costs beyond its pixels, as pixels: its upload, read-back and draw calls
     * ([pageTile] weighs fewer, larger tiles against less overlap).
     */
    const val TILE_COST_PIXELS = 128 * 128

    /** Times the margin is widened for seams before the chain is refused. */
    const val MAX_WIDENINGS = 3

    /**
     * The longest a tile should take, in ms: the GPU's turn between the reader's frames and the
     * wait before a page turn or a cancel takes effect. Tiles that took longer while the seams
     * were checked make the session's largest tile smaller ([capped]).
     */
    const val TILE_MS = 150L

    /**
     * The most two tilings of a page may differ by, in codes: with the whole reach in the margin
     * only the GPU's rounding of sampling positions, which differ by a pixel's place in its
     * tile, is left.
     */
    const val SEAM_MAX = 1

    /**
     * The chain of [texts] (shader files in order, read when opening) enlarging [scale] times,
     * opened through [runtime]; tiles at most [maxTile] unless the margins call for more, their
     * textures within a [TileSizing.GPU_SHARE] share of [memoryBytes]. Refused when the GPU
     * cannot run chains, the files do not parse or compile, the chain does not measure as a
     * [scale] times enlargement or reaches too far, or no tile fits. Compiled passes are kept
     * in [cache] for the next open ([ShaderRuntime.open]). Tiles are timed by [clock] (ms) while
     * the seams are checked. Throws [java.util.concurrent.CancellationException] once
     * [canceled], checked between runs.
     */
    fun attempt(
        runtime: ShaderRuntime,
        texts: () -> List<String>,
        scale: Int,
        memoryBytes: Long = Long.MAX_VALUE,
        maxTile: Int = DEFAULT_MAX,
        cache: File? = null,
        canceled: () -> Boolean = { false },
        clock: () -> Long = SystemClock::elapsedRealtime
    ): Opening = TileEngine.exclusive(canceled) { openAlone(runtime, texts, scale, memoryBytes, maxTile, cache, canceled, clock) }

    private fun openAlone(
        runtime: ShaderRuntime, texts: () -> List<String>, scale: Int, memoryBytes: Long, maxTile: Int, cache: File?,
        canceled: () -> Boolean, clock: () -> Long
    ): Opening {
        if (!runtime.available) return Opening.refused(ModelRefusal.RUNTIME)
        val chain = try {
            ShaderChain.parse(texts(), scale).also { it.plan(ModelProbe.FIRST_PROBE, ModelProbe.FIRST_PROBE) }
        } catch (e: ShaderException) {
            Log.w(TAG, "does not plan: ${e.message}")
            return Opening.refused(ModelRefusal.NOT_LOADED)
        } catch (e: IOException) {
            Log.w(TAG, "unreadable: ${e.message}")
            return Opening.refused(ModelRefusal.NOT_LOADED)
        }
        val t0 = SystemClock.elapsedRealtime()
        val model = runtime.open(chain, cache) ?: return Opening.refused(ModelRefusal.NOT_LOADED)
        var kept = false
        try {
            val probed = try {
                ModelProbe.probeExact(model, canceled, memoryBytes)
            } catch (e: ModelProbe.Unfit) {
                Log.w(TAG, "cannot measure: ${e.message}")
                return Opening.refused(e.reason, e.bytes, memoryBytes)
            }
            var g = probed.geometry
            if (g.scale != scale || g.trim != 0) {
                Log.w(TAG, "measured $g, not $scale times")
                return Opening.refused(ModelRefusal.SHAPE)
            }
            val budget = memoryBytes / TileSizing.GPU_SHARE
            for (widenings in 0..MAX_WIDENINGS) {
                val size = sizes(chain, g, runtime.floatBytes, budget, runtime.maxSide, maxTile).firstOrNull()
                if (size == null) {
                    val smallest = SIZES.lastOrNull { TilePlan.fits(it, g) }
                    val need = smallest?.let { chain.plan(it, it).bytes(runtime.floatBytes) } ?: 0
                    Log.w(TAG, "no tile fits $g in ${budget shr 20} MB")
                    return Opening.refused(ModelRefusal.MEMORY, need, budget)
                }
                val timed = Timed(model, size, clock)
                val check = ModelProbe.verify(timed, timed, g, size, size, canceled)
                val tileMs = timed.median()
                val most = capped(size, g, tileMs)
                Log.i(TAG, "${chain.programs.size} passes, $size px tiles in $tileMs ms (at most $most), $g: seams ${check.seams} (${SystemClock.elapsedRealtime() - t0} ms in all)")
                if (check.seams.max <= SEAM_MAX) {
                    model.trim()
                    val geometry = g
                    val pick = { w: Int, h: Int -> pageTile(w, h, geometry, most) }
                    return Opening(Session(model, g, most, fp16 = runtime.floatBytes == 8, pick = pick), null).also { kept = true }
                }
                g = g.copy(context = g.margin + TilePlan.ALIGN)
            }
            return Opening.refused(ModelRefusal.REACH)
        } finally {
            if (!kept) model.close()
        }
    }

    /**
     * [size], or when its tiles took [tileMs] past [TILE_MS], the largest size whose tiles would
     * take no longer at the same time per pixel, its core a multiple of [TilePlan.ALIGN] (no
     * smaller than the smallest that fits [geometry]).
     */
    fun capped(size: Int, geometry: Geometry, tileMs: Long): Int {
        if (tileMs <= TILE_MS) return size
        val side = (size * Math.sqrt(TILE_MS.toDouble() / tileMs)).toInt()
        val m = geometry.margin
        val core = maxOf((side - 2 * m) / TilePlan.ALIGN * TilePlan.ALIGN, TilePlan.MIN_CORE)
        return minOf(core + 2 * m, size)
    }

    /** [model], timing each run on a [size] x [size] input by [clock]. */
    private class Timed(private val model: TileModel, private val size: Int, private val clock: () -> Long) : TileModel by model {
        private val times = ArrayList<Long>()

        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
            val t0 = clock()
            val out = model.run(src, input, dst, crop, dx, dy)
            if (input.width == size && input.height == size) times += clock() - t0
            return out
        }

        /** The median of the runs timed; 0 for none. */
        fun median(): Long = times.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    }

    /**
     * The tile size, at most [maxSize], that covers a [width] x [height] page at [geometry]
     * with the least work: tiles' pixels and [TILE_COST_PIXELS] each, ties to the larger tile.
     * Sizes tried are those whose core is a whole multiple of [TilePlan.ALIGN], so none of a
     * tile is wasted to rounding; [maxSize] when none fits.
     */
    fun pageTile(width: Int, height: Int, geometry: Geometry, maxSize: Int): Int {
        val m = geometry.margin
        var best = maxSize
        var bestCost = Long.MAX_VALUE
        var core = (maxSize - 2 * m) / TilePlan.ALIGN * TilePlan.ALIGN
        while (core >= TilePlan.MIN_CORE) {
            val size = core + 2 * m
            val tiles = TilePlan.starts(width, core).size.toLong() * TilePlan.starts(height, core).size
            val cost = tiles * (size.toLong() * size + TILE_COST_PIXELS)
            if (cost < bestCost) {
                best = size
                bestCost = cost
            }
            core -= TilePlan.ALIGN
        }
        return best
    }

    /**
     * Tile sizes for [chain] at [geometry], best first, then each smaller one that also fits:
     * all hold their textures ([ShaderPlan.bytes] at [floatBytes]) within [budgetBytes], their
     * output within [maxSide], and leave a core. The best is the largest up to [maxTile], or,
     * when its core keeps less than [TileSizing.MIN_KEPT] of it, the smallest larger one that
     * keeps that much (else the largest).
     */
    fun sizes(chain: ShaderChain, geometry: Geometry, floatBytes: Int, budgetBytes: Long, maxSide: Int, maxTile: Int = DEFAULT_MAX): List<Int> {
        val fit = SIZES.filter { s ->
            TilePlan.fits(s, geometry) && s * geometry.scale <= maxSide && runCatching { chain.plan(s, s).bytes(floatBytes) <= budgetBytes }.getOrDefault(false)
        }
        if (fit.isEmpty()) return emptyList()
        val capped = fit.firstOrNull { it <= maxTile }
        val best = if (capped != null && TileSizing.kept(capped, geometry) >= TileSizing.MIN_KEPT) capped
        else fit.lastOrNull { TileSizing.kept(it, geometry) >= TileSizing.MIN_KEPT } ?: fit.first()
        return fit.filter { it <= best }
    }
}
