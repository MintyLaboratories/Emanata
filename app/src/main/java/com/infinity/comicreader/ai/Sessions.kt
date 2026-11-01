package com.infinity.comicreader.ai

import android.util.Log

/**
 * Tile sizes: the largest whose working memory fits the budget, no larger than a cap. A
 * tile's memory is estimated from the model's widest layer, as if every feature map were at
 * output resolution in fp32, three at a time: an upper bound for upscalers, whose last layers
 * work at output resolution.
 */
object TileSizing {
    /** Sizes tried, largest first. */
    val SIZES = intArrayOf(512, 384, 256, 192, 128, 96, 64)

    /**
     * The cap by default: on the reference tablet's GPU (Adreno 730), a 2x compact model ran a
     * 1000 x 1500 page fastest in 192-pixel tiles (5.9 s; 128: 8.7 s, 256: 6.5 s, 384: 6.1 s),
     * with the least GPU memory of the fast sizes and 76 ms between chances to stop.
     */
    const val DEFAULT_MAX = 192

    /** Share of the GPU's memory budget one tile may take. */
    const val GPU_SHARE = 4

    /** Host memory one tile may take on the CPU. */
    const val CPU_BUDGET_BYTES = 256L shl 20

    /** Channels assumed when the model's layers cannot be read. */
    const val DEFAULT_WIDEST = 64

    private val CONVOLUTIONS = setOf("Convolution", "ConvolutionDepthWise", "Deconvolution", "DeconvolutionDepthWise")

    /** The most output channels of any convolution in an ncnn `.param` text; [DEFAULT_WIDEST] when there is none. */
    fun widest(param: String): Int {
        var most = 0
        for (line in param.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0] !in CONVOLUTIONS) continue
            val channels = parts.firstOrNull { it.startsWith("0=") }?.substring(2)?.toIntOrNull() ?: continue
            most = maxOf(most, channels)
        }
        return if (most > 0) most else DEFAULT_WIDEST
    }

    /** Estimated working bytes per input pixel of a tile. */
    fun bytesPerPixel(widest: Int, scale: Int): Long = widest.toLong() * 4 * 3 * scale * scale

    /** The largest size up to [max] that fits [budgetBytes] and leaves [geometry] a core; null when none does. */
    fun pick(budgetBytes: Long, widest: Int, geometry: Geometry, max: Int = DEFAULT_MAX): Int? {
        val per = bytesPerPixel(widest, geometry.scale)
        return SIZES.firstOrNull { it <= max && it.toLong() * it * per <= budgetBytes && TilePlan.fits(it, geometry) }
    }
}

/**
 * Opens models as [Session]s: on the GPU where one is usable and wanted, else on the CPU; a
 * model that fails to open or to measure ([ModelProbe]) on the GPU is tried again on the CPU.
 */
internal object Sessions {
    private const val TAG = "AiSessions"

    /**
     * [files] opened through [runtime]: on the GPU when [gpu] and one is usable, else on
     * [threads] CPU threads; tiles at most [maxTile]. Null when the model loads or measures
     * nowhere, or no tile fits.
     */
    fun open(
        runtime: InferenceRuntime,
        files: ModelFiles,
        threads: Int,
        gpu: Boolean = true,
        maxTile: Int = TileSizing.DEFAULT_MAX,
        fp16: Boolean = true
    ): Session? {
        if (!runtime.available) return null
        val info = if (gpu) runtime.gpu() else null
        val widest = runCatching { TileSizing.widest(files.param.readText()) }.getOrDefault(TileSizing.DEFAULT_WIDEST)
        if (info != null) {
            val budget = info.budgetMb.toLong() * (1L shl 20) / TileSizing.GPU_SHARE
            ready(runtime.open(files, true, threads, fp16), budget, widest, maxTile)?.let { return it }
            Log.w(TAG, "${files.param.name}: not usable on the GPU, trying the CPU")
        }
        return ready(runtime.open(files, false, threads, fp16), TileSizing.CPU_BUDGET_BYTES, widest, maxTile)
    }

    /** [model] measured and given its tile size, or closed and null when either fails. */
    private fun ready(model: TileModel?, budget: Long, widest: Int, maxTile: Int): Session? {
        model ?: return null
        var kept = false
        try {
            val geometry = ModelProbe.measure(model)
            val size = TileSizing.pick(budget, widest, geometry, maxTile)
            if (size == null) {
                Log.w(TAG, "no tile fits $geometry in ${budget shr 20} MB")
                return null
            }
            model.trim()
            return Session(model, geometry, size).also { kept = true }
        } catch (e: ModelProbe.Unfit) {
            Log.w(TAG, "cannot measure: ${e.message}")
            return null
        } finally {
            if (!kept) model.close()
        }
    }
}
