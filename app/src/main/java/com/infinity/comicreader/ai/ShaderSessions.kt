package com.infinity.comicreader.ai

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Opens shader chains ([ShaderModel]) as [Session]s for the tile engine, as [Sessions] opens
 * ncnn models: the files parsed ([ShaderHooks]) and planned ([ShaderChain]), the passes
 * compiled ([ShaderRuntime.open]), the chain measured on the GPU ([ModelProbe.probe]: a chain
 * computes each pixel alike wherever it sits in a tile, so no CPU reference is needed), then
 * checked for seams at the tile size chosen ([ModelProbe.verify]), a margin wider by
 * [TilePlan.ALIGN] up to [MAX_WIDENINGS] times when they show.
 *
 * Tiles, not whole pages: the passes keep many full-size float textures alive at once (an
 * Anime4K UL chain about twenty at 8 bytes a pixel, plus the enlarged image), so a whole page
 * of the 8 MP a chain may read would need well over a gigabyte of GPU memory; tiles bound it
 * to tens of megabytes ([ShaderPlan.bytes]) and give the engine its chances to stop, wait and
 * pace between them. Tile sizes run to [SIZES]' largest: a shader pass costs little per draw,
 * so larger tiles waste less on margins.
 */
internal object ShaderSessions {
    private const val TAG = "AiShaders"

    /** Tile sizes tried, largest first. */
    val SIZES = intArrayOf(1024, 768, 512, 384, 256, 192, 128, 96, 64)

    /**
     * The largest tile by default: a quarter of a 1000 x 1500 page between chances to stop, its
     * textures (an Anime4K UL chain's) about 60 MB, margins of 20 pixels keeping 85 % of it.
     */
    const val DEFAULT_MAX = 512

    /** Times the margin is widened for seams before the chain is refused. */
    const val MAX_WIDENINGS = 3

    /**
     * The chain of [texts] (shader files in order, read when opening) enlarging [scale] times,
     * opened through [runtime]; tiles at most [maxTile] unless the margins call for more, their
     * textures within a [TileSizing.GPU_SHARE] share of [memoryBytes]. Refused when the GPU
     * cannot run chains, the files do not parse or compile, the chain does not measure as a
     * [scale] times enlargement or reaches too far, or no tile fits. Compiled passes are kept
     * in [cache] for the next open ([ShaderRuntime.open]). Throws
     * [java.util.concurrent.CancellationException] once [canceled], checked between runs.
     */
    fun attempt(
        runtime: ShaderRuntime,
        texts: () -> List<String>,
        scale: Int,
        memoryBytes: Long = Long.MAX_VALUE,
        maxTile: Int = DEFAULT_MAX,
        cache: File? = null,
        canceled: () -> Boolean = { false }
    ): Opening = TileEngine.exclusive(canceled) { openAlone(runtime, texts, scale, memoryBytes, maxTile, cache, canceled) }

    private fun openAlone(
        runtime: ShaderRuntime, texts: () -> List<String>, scale: Int, memoryBytes: Long, maxTile: Int, cache: File?, canceled: () -> Boolean
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
                ModelProbe.probe(model, canceled, memoryBytes)
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
                val check = ModelProbe.verify(model, model, g, size, size, canceled)
                Log.i(TAG, "${chain.programs.size} passes, $size px tiles, $g: seams ${check.seams} (${SystemClock.elapsedRealtime() - t0} ms in all)")
                if (check.seams.same) {
                    model.trim()
                    return Opening(Session(model, g, size, fp16 = runtime.floatBytes == 8), null).also { kept = true }
                }
                g = g.copy(context = g.margin + TilePlan.ALIGN)
            }
            return Opening.refused(ModelRefusal.REACH)
        } finally {
            if (!kept) model.close()
        }
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
