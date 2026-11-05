package com.infinity.comicreader.ai

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Why a model does not open, or is not taken in ([ModelCheck]). */
enum class ModelRefusal {
    /** The inference library does not load on this device. */
    RUNTIME,

    /** The files do not load as an ncnn model, or do not belong together. */
    NOT_LOADED,

    /** Layers mix the whole tile ([ModelParam.wholeTileLayers]): tiles would show. */
    WHOLE_TILE,

    /** It fails on an RGB tile. */
    DOES_NOT_RUN,

    /** Its output is not its input enlarged a whole number of times, less a fixed edge. */
    SHAPE,

    /** Its output depends on content too far away for tiles to share. */
    REACH,

    /** It needs more memory than the device can spare ([ModelMemory]). */
    MEMORY,

    /** Its output does not resemble its input: scaled differently, or files that do not belong together. */
    UNLIKE,

    /** It would take longer than a page may, or checking it took too long. */
    TOO_SLOW
}

/**
 * A model opened ([session]), or why not ([refusal]; for memory, the [bytes] it would need,
 * weights included, and the [limit] it would pass; 0 when unknown).
 */
internal class Opening(val session: Session?, val refusal: ModelRefusal?, val bytes: Long = 0, val limit: Long = 0) {
    companion object {
        fun refused(why: ModelRefusal, bytes: Long = 0, limit: Long = 0) = Opening(null, why, bytes, limit)
    }
}

/**
 * The memory a model may take: its weights and the working memory of its tiles, on the GPU
 * and the CPU alike (a phone's GPU shares its RAM). A model needing more is refused: past
 * this, 4x ESRGAN-class models opened to 2 GB and more and the system ended the app.
 */
object ModelMemory {
    /** The share of the device's memory a model may take. */
    const val SHARE = 8

    /** The share of the memory available when a model opens that it may take. */
    const val AVAILABLE_SHARE = 2

    /** The budget for [total] bytes of memory, [available] of them free now. */
    fun budget(total: Long, available: Long): Long = min(total / SHARE, available / AVAILABLE_SHARE)

    /** The budget on this device now. */
    fun of(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return budget(info.totalMem, info.availMem)
    }
}

/** What a model's ncnn `.param` text says about tiling it. */
object ModelParam {
    /** Channels assumed when the model's layers cannot be read. */
    const val DEFAULT_WIDEST = 64

    private val CONVOLUTIONS = setOf("Convolution", "ConvolutionDepthWise", "Deconvolution", "DeconvolutionDepthWise")

    /** Layers that always mix the whole tile: reductions, fully connected layers, per-image normalizations. */
    private val WHOLE_TILE = setOf("Reduction", "InnerProduct", "InstanceNorm", "GroupNorm")

    /** A layer line's type, name and `key=value` parameters; null for the header lines. */
    private class Layer(val type: String, val name: String, val params: Map<String, String>)

    private fun layers(param: String): Sequence<Layer> = param.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 4 || parts[0].first().isDigit()) return@mapNotNull null
        val params = parts.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        Layer(parts[0], parts[1], params)
    }

    /** The most output channels of any convolution; [DEFAULT_WIDEST] when there is none. */
    fun widest(param: String): Int {
        val most = layers(param).filter { it.type in CONVOLUTIONS }.mapNotNull { it.params["0"]?.toIntOrNull() }.maxOrNull() ?: 0
        return if (most > 0) most else DEFAULT_WIDEST
    }

    /**
     * Names of the layers whose output depends on the whole tile rather than on nearby pixels:
     * global or adaptive pooling (squeeze-excitation blocks, as in Real-CUGAN), reductions,
     * fully connected layers and normalizations over the whole image. Tiles of such a model
     * differ by their content however much they overlap.
     */
    fun wholeTileLayers(param: String): List<String> = layers(param).filter { l ->
        when (l.type) {
            "Pooling" -> l.params["4"] == "1" || l.params["7"] == "1"
            "Normalize" -> l.params["0"] == "1"
            else -> l.type in WHOLE_TILE
        }
    }.map { it.name }.toList()
}

/**
 * Tile sizes: the largest whose working memory fits the budget and whose largest feature map
 * fits one GPU buffer, no larger than a cap unless the margins would leave too little core.
 * A tile's memory is estimated from the model's widest layer, as if every feature map were at
 * output resolution, three at a time: an upper bound for upscalers, whose last layers work at
 * output resolution.
 */
object TileSizing {
    /** Sizes tried, largest first. */
    val SIZES = intArrayOf(512, 384, 256, 192, 128, 96, 64)

    /**
     * The cap by default: on the reference tablet's GPU (Adreno 730), a 2x compact model ran a
     * 1000 x 1500 page fastest in 192-pixel tiles (5.4 s in fp16; 128: 11.8 s, 256: 5.8 s, 384: 6.0 s),
     * with the least GPU memory of the fast sizes and 77 ms between chances to stop.
     */
    const val DEFAULT_MAX = 192

    /** Share of the GPU's memory budget one tile may take. */
    const val GPU_SHARE = 4

    /** Host memory one tile may take on the CPU. */
    const val CPU_BUDGET_BYTES = 256L shl 20

    /** The least share of a tile's input area its core may keep before a size past the cap is taken. */
    const val MIN_KEPT = 0.5

    /** Estimated working bytes per input pixel of a tile, in fp32. */
    fun bytesPerPixel(widest: Int, scale: Int): Long = widest.toLong() * 4 * 3 * scale * scale

    /** Working bytes per input pixel to plan by: the estimate, or [measured] when that is more. */
    fun bytesPerPixel(widest: Int, scale: Int, measured: Long): Long = max(bytesPerPixel(widest, scale), measured)

    /** Bytes of a [size] tile's largest feature map: [widest] channels at output resolution, [elementBytes] each. */
    fun largestMap(size: Int, widest: Int, scale: Int, elementBytes: Int): Long {
        val side = size.toLong() * scale
        return widest.toLong() * side * side * elementBytes
    }

    /** The share of a [size] tile's input area its core keeps for [geometry]. */
    fun kept(size: Int, geometry: Geometry): Double {
        val core = TilePlan.core(size, geometry).coerceAtLeast(0).toDouble()
        return core * core / (size.toDouble() * size)
    }

    /**
     * Sizes to use, best first, then each smaller one that also fits: all fit [budgetBytes]
     * (at the estimate's bytes per pixel, or the [measured] ones when more), keep each feature
     * map within [maxBufferBytes] (at [elementBytes] per value) and leave [geometry] a core.
     * The best is the largest up to [max], or, when its core keeps less than [MIN_KEPT] of it,
     * the smallest larger one that keeps that much (else the largest).
     */
    fun candidates(
        budgetBytes: Long,
        widest: Int,
        geometry: Geometry,
        max: Int = DEFAULT_MAX,
        maxBufferBytes: Long = Long.MAX_VALUE,
        elementBytes: Int = 4,
        measured: Long = 0
    ): List<Int> {
        val per = bytesPerPixel(widest, geometry.scale, measured)
        val fit = SIZES.filter {
            it.toLong() * it * per <= budgetBytes && largestMap(it, widest, geometry.scale, elementBytes) <= maxBufferBytes &&
                TilePlan.fits(it, geometry)
        }
        if (fit.isEmpty()) return emptyList()
        val capped = fit.firstOrNull { it <= max }
        val best = if (capped != null && kept(capped, geometry) >= MIN_KEPT) capped else fit.lastOrNull { kept(it, geometry) >= MIN_KEPT } ?: fit.first()
        return fit.filter { it <= best }
    }

    /** The best of [candidates]; null when none fits. */
    fun pick(
        budgetBytes: Long,
        widest: Int,
        geometry: Geometry,
        max: Int = DEFAULT_MAX,
        maxBufferBytes: Long = Long.MAX_VALUE,
        elementBytes: Int = 4,
        measured: Long = 0
    ): Int? = candidates(budgetBytes, widest, geometry, max, maxBufferBytes, elementBytes, measured).firstOrNull()
}

/**
 * Opens models as [Session]s. Every model is first opened on the CPU, in fp32, where its
 * [Geometry] is measured ([ModelProbe.measure]): its true reach, free of fp16 rounding. On the
 * GPU it is then opened and checked at its tile size ([ModelProbe.verify]), against tiles
 * meeting elsewhere and against the CPU's output:
 * - seams of rounding noise in fp16 (content past the measured reach, too faint to count in
 *   fp32, rounds differently in each tile): a margin wider by [TilePlan.ALIGN], up to
 *   [MAX_WIDENINGS] times while that keeps fp16 the cheaper per pixel ([FP16_SPEEDUP]), else
 *   fp32;
 * - any other seams, or output unlike the CPU's (a feature map past what the GPU addresses,
 *   broken arithmetic): the next smaller size, then fp32.
 * A model no GPU setup passes runs on the CPU. Opening runs alone, as a page does ([TileEngine]).
 *
 * Memory: the model's weights and its tiles' working memory stay within the memory it is
 * given ([ModelMemory]). Measuring counts what each run holds and stops before a run that
 * would need more ([ModelProbe.probe]); tiles are sized by the larger of the estimate and the
 * measured bytes per pixel; on the GPU, a size whose check held more is passed over.
 */
internal object Sessions {
    private const val TAG = "AiSessions"

    /**
     * fp16's speed over fp32 per GPU tile, as measured on the reference tablet (1.95 times): a
     * wider fp16 margin pays while its tiles keep more than 1 / this of what fp32's keep.
     */
    const val FP16_SPEEDUP = 2.0

    /** Times an fp16 margin is widened for seams before fp32 is taken instead. */
    const val MAX_WIDENINGS = 3

    /**
     * [files] opened through [runtime] ([attempt]); null when it does not open.
     */
    fun open(
        runtime: InferenceRuntime,
        files: ModelFiles,
        threads: Int,
        gpu: Boolean = true,
        maxTile: Int = TileSizing.DEFAULT_MAX,
        fp16: Boolean = true,
        memoryBytes: Long = Long.MAX_VALUE,
        canceled: () -> Boolean = { false }
    ): Session? = attempt(runtime, files, threads, gpu, maxTile, fp16, memoryBytes, canceled).session

    /**
     * [files] opened through [runtime]: on the GPU when [gpu] and one is usable, in fp16 when
     * [fp16] and the GPU has it and the model passes in it, else on [threads] CPU threads; tiles
     * at most [maxTile] unless the margins call for more; weights and working memory within
     * [memoryBytes]. Refused when the model loads or measures nowhere, mixes the whole tile
     * ([ModelParam.wholeTileLayers]), or no tile fits its memory. Throws
     * [java.util.concurrent.CancellationException] once [canceled], checked between runs.
     */
    fun attempt(
        runtime: InferenceRuntime,
        files: ModelFiles,
        threads: Int,
        gpu: Boolean = true,
        maxTile: Int = TileSizing.DEFAULT_MAX,
        fp16: Boolean = true,
        memoryBytes: Long = Long.MAX_VALUE,
        canceled: () -> Boolean = { false }
    ): Opening = TileEngine.exclusive(canceled) {
        openAlone(runtime, files, threads, gpu, maxTile, fp16, memoryBytes, canceled)
    }

    private fun openAlone(
        runtime: InferenceRuntime, files: ModelFiles, threads: Int, gpu: Boolean, maxTile: Int, fp16: Boolean,
        memoryBytes: Long, canceled: () -> Boolean
    ): Opening {
        if (!runtime.available) return Opening.refused(ModelRefusal.RUNTIME)
        val name = files.param.name
        val param = runCatching { files.param.readText() }.getOrNull()
        val whole = param?.let(ModelParam::wholeTileLayers).orEmpty()
        if (whole.isNotEmpty()) {
            Log.w(TAG, "$name: output depends on the whole tile (${whole.joinToString()}), so tiles would show; not opened")
            return Opening.refused(ModelRefusal.WHOLE_TILE)
        }
        val widest = param?.let(ModelParam::widest) ?: ModelParam.DEFAULT_WIDEST
        // Weights in fp32, as the CPU holds them; what is left is for tiles.
        val weights = files.bin.length()
        if (weights >= memoryBytes) {
            Log.w(TAG, "$name: weights alone (${weights shr 20} MB) take the ${memoryBytes shr 20} MB a model may")
            return Opening.refused(ModelRefusal.MEMORY, weights, memoryBytes)
        }
        val work = memoryBytes - weights
        val cpu = runtime.open(files, false, threads, false) ?: return Opening.refused(ModelRefusal.NOT_LOADED)
        var keepCpu = false
        try {
            val t0 = SystemClock.elapsedRealtime()
            val probed = try {
                ModelProbe.probe(cpu, canceled, work)
            } catch (e: ModelProbe.Unfit) {
                Log.w(TAG, "$name: cannot measure: ${e.message}")
                return Opening.refused(e.reason, if (e.bytes > 0) weights + e.bytes else 0, memoryBytes)
            }
            val geometry = probed.geometry
            val measured = probed.bytesPerPixel
            Log.i(TAG, "$name: $geometry, ${measured / 1024} KB per input pixel, measured on the CPU (${SystemClock.elapsedRealtime() - t0} ms)")
            val size = TileSizing.pick(min(TileSizing.CPU_BUDGET_BYTES, work), widest, geometry, maxTile, measured = measured)
            val info = if (gpu) runtime.gpu() else null
            // The CPU's output to check the GPU's against, in tiles the CPU's memory holds when any does.
            val reference = size ?: TileSizing.SIZES.lastOrNull {
                TilePlan.fits(it, geometry) && it.toLong() * it * TileSizing.bytesPerPixel(widest, geometry.scale, measured) <= work
            }
            if (info != null && reference != null) {
                onGpu(runtime, files, threads, info, geometry, widest, maxTile, fp16 && info.fp16, cpu, reference, work, measured, canceled)
                    ?.let { return Opening(it, null) }
                Log.w(TAG, "$name: not usable on the GPU, running on the CPU")
            }
            if (size == null) {
                val smallest = TileSizing.SIZES.lastOrNull { TilePlan.fits(it, geometry) }
                val need = smallest?.let { weights + it.toLong() * it * TileSizing.bytesPerPixel(widest, geometry.scale, measured) } ?: 0
                Log.w(TAG, "$name: no tile fits $geometry in ${min(TileSizing.CPU_BUDGET_BYTES, work) shr 20} MB")
                return Opening.refused(ModelRefusal.MEMORY, need, weights + min(TileSizing.CPU_BUDGET_BYTES, work))
            }
            cpu.trim()
            return Opening(Session(cpu, geometry, size), null).also { keepCpu = true }
        } finally {
            if (!keepCpu) cpu.close()
        }
    }

    /**
     * The model on the GPU at the first precision and size that passes [ModelProbe.verify]
     * within [work] bytes; null when none does. [measured]: the CPU's working bytes per input
     * pixel in fp32, a floor for the estimate.
     */
    private fun onGpu(
        runtime: InferenceRuntime, files: ModelFiles, threads: Int, info: GpuInfo, geometry: Geometry, widest: Int,
        maxTile: Int, fp16: Boolean, cpu: TileModel, cpuSize: Int, work: Long, measured: Long, canceled: () -> Boolean
    ): Session? {
        val name = files.param.name
        val budget = min(info.budgetMb.toLong() * (1L shl 20) / TileSizing.GPU_SHARE, work)
        for (half in if (fp16) listOf(true, false) else listOf(false)) {
            val precision = if (half) "fp16" else "fp32"
            val model = runtime.open(files, true, threads, half) ?: return null
            var kept = false
            try {
                var g = geometry
                var widenings = 0
                while (true) {
                    val element = if (half) 2 else 4
                    val sizes = TileSizing.candidates(budget, widest, g, maxTile, info.maxBufferBytes, element, measured * element / 4)
                    if (sizes.isEmpty()) Log.w(TAG, "$name: no $precision tile fits $g in ${budget shr 20} MB")
                    var noisy = false
                    for (size in sizes) {
                        val t0 = SystemClock.elapsedRealtime()
                        val check = ModelProbe.verify(model, cpu, g, size, cpuSize, canceled)
                        Log.i(TAG, "$name: $precision, $size px tiles, margin ${g.margin}: $check (${SystemClock.elapsedRealtime() - t0} ms)")
                        if (check.passes && check.peakBytes > budget) {
                            Log.w(TAG, "$name: $precision, $size px tiles held ${check.peakBytes shr 20} MB, over ${budget shr 20} MB")
                            continue
                        }
                        if (check.passes) {
                            model.trim()
                            return Session(model, g, size, half).also { kept = true }
                        }
                        // fp16 rounding past the margin shows at every size.
                        if (half && check.seams.noisy) {
                            noisy = true
                            break
                        }
                    }
                    val size = sizes.firstOrNull() ?: break
                    val wider = g.copy(context = g.margin + TilePlan.ALIGN)
                    if (!noisy || widenings == MAX_WIDENINGS || !TilePlan.fits(size, wider) ||
                        TileSizing.kept(size, wider) * FP16_SPEEDUP < TileSizing.kept(size, geometry)
                    ) break
                    g = wider
                    widenings++
                }
            } catch (e: ModelProbe.Unfit) {
                Log.w(TAG, "$name: $precision fails: ${e.message}")
            } finally {
                if (!kept) model.close()
            }
        }
        return null
    }
}
