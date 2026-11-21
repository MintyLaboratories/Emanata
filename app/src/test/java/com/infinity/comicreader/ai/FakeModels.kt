package com.infinity.comicreader.ai

import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A model as the JVM can have one: each output pixel is the mean, per channel, of the input
 * pixel it maps to and the four each of [taps] away from it up, down, left and right, so
 * content exactly that far away changes it; [reach] is the furthest. [padded] models keep the input's size times [scale] and
 * see black past the tile's edge, as zero padding does; others drop the [reach] pixels on each
 * side whose taps would leave the tile (their trim). Reads [src] past its edge as the native
 * code does: the nearest edge pixel. Like a GPU can, it may round by a pixel's place in its
 * tile ([phaseNoise], [edgeNoise]), go wrong on tiles wider than [breaksAbove], fail on those
 * wider than [failsAbove], or compute
 * nonsense ([wrong]). Each run holds [bytesPerPixel] of working memory per input pixel
 * ([takePeak]), and its weights [weights] ([weightBytes]).
 */
internal class FakeModel(
    val scale: Int = 2,
    val reach: Int = 3,
    val padded: Boolean = true,
    val taps: IntArray = intArrayOf(reach),
    override val onGpu: Boolean = true,
    /** Fails the run with this index (0-based) when set. */
    private val failAt: Int = -1,
    /** Called at the start of every run, with the run's index. */
    private val onRun: (Int) -> Unit = {},
    /** Adds 3 codes to output from every fourth input column of a tile, as fp16 rounding by place can. */
    private val phaseNoise: Boolean = false,
    /** Adds 3 codes to output from every fifth input column counted from a tile's right edge, so by the tile's size. */
    private val edgeNoise: Boolean = false,
    /** Adds 3 codes to output from input this close to a tile's edge, as fp16 rounding of faint far context can. */
    private val borderNoise: Int = 0,
    /** Inverts the output of tiles wider than this, as a feature map past a GPU buffer can. */
    private val breaksAbove: Int = Int.MAX_VALUE,
    /** Fails runs on tiles wider than this, as an allocation past the GPU's memory can. */
    private val failsAbove: Int = Int.MAX_VALUE,
    /** Writes mid-gray whatever the input. */
    private val wrong: Boolean = false,
    /** Adds this many codes to the red of every output pixel, as a GPU fault can color a gray page. */
    private val tint: Int = 0,
    /** Working bytes a run holds per input pixel; 0: not counted. */
    private val bytesPerPixel: Long = 0,
    /** Bytes its weights took as it opened; 0: not counted. */
    private val weights: Long = 0,
    /** Called as it closes. */
    private val onClose: () -> Unit = {}
) : TileModel {
    val trim: Int get() = if (padded) 0 else reach * scale
    val runs = AtomicInteger()
    val trims = AtomicInteger()
    val inside = AtomicInteger()
    var mostInside = 0
    var closed = false
    private var peak = 0L
    val inputs = ArrayList<Box>()

    override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
        if (closed) return null
        val now = inside.incrementAndGet()
        synchronized(this) { mostInside = maxOf(mostInside, now) }
        try {
            val i = runs.getAndIncrement()
            onRun(i)
            synchronized(inputs) { inputs += input }
            synchronized(this) { peak = maxOf(peak, input.width.toLong() * input.height * bytesPerPixel) }
            if (i == failAt || input.width > failsAbove) return null
            val n = input.width
            val m = input.height
            val outW = n * scale - 2 * trim
            val outH = m * scale - 2 * trim
            if (outW <= 0 || outH <= 0) return null
            if (dst == null) return Dims(outW, outH)
            val c = crop!!
            val tile = read(src, input)
            val row = IntArray(c.width)
            for (y in 0 until c.height) {
                for (x in 0 until c.width) {
                    val px = (c.x + x + trim) / scale
                    var v = pixel(tile, n, m, px, (c.y + y + trim) / scale)
                    if (phaseNoise && px % 4 == 0) v = brighter(v, 3)
                    if (edgeNoise && (n - 1 - px) % 5 == 0) v = brighter(v, 3)
                    val py = (c.y + y + trim) / scale
                    if (minOf(px, py, n - 1 - px, m - 1 - py) < borderNoise) v = brighter(v, 3)
                    if (n > breaksAbove) v = v xor 0xFFFFFF
                    if (wrong) v = 0xFF808080.toInt()
                    if (tint != 0) v = redder(v, tint)
                    row[x] = v
                }
                dst.setPixels(row, 0, c.width, dx, dy + y, c.width, 1)
            }
            return Dims(outW, outH)
        } finally {
            inside.decrementAndGet()
        }
    }

    /** The output pixel for tile input pixel ([px], [py]): the mean of it and its four taps, black past the tile. */
    private fun pixel(tile: IntArray, n: Int, m: Int, px: Int, py: Int): Int =
        mean(taps) { x, y -> if (x + px in 0 until n && y + py in 0 until m) tile[(y + py) * n + x + px] else 0 }

    private fun redder(v: Int, by: Int): Int = (v and 0xFF00FFFF.toInt()) or (minOf(255, ((v shr 16) and 0xFF) + by) shl 16)

    private fun brighter(v: Int, by: Int): Int {
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) out = out or (minOf(255, ((v shr shift) and 0xFF) + by) shl shift)
        return out
    }

    override fun trim() {
        trims.incrementAndGet()
    }

    @Synchronized
    override fun takePeak(): Long = peak.also { peak = 0 }

    override fun weightBytes(): Long = weights

    override fun close() {
        closed = true
        onClose()
    }

    companion object {
        /** [input] of [src], pixels past its edge repeating the nearest edge pixel. */
        fun read(src: Bitmap, input: Box): IntArray {
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            return IntArray(input.width * input.height) { k ->
                val x = (input.x + k % input.width).coerceIn(0, src.width - 1)
                val y = (input.y + k / input.width).coerceIn(0, src.height - 1)
                px[y * src.width + x]
            }
        }

        /** What [FakeModel] computes for a whole page in one piece, edges repeated: what tiling must match. */
        fun reference(src: Bitmap, scale: Int, reach: Int, taps: IntArray = intArrayOf(reach)): IntArray {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val out = IntArray(w * scale * h * scale)
            for (oy in 0 until h * scale) for (ox in 0 until w * scale) {
                val cx = ox / scale
                val cy = oy / scale
                out[oy * w * scale + ox] = mean(taps) { x, y -> px[(cy + y).coerceIn(0, h - 1) * w + (cx + x).coerceIn(0, w - 1)] }
            }
            return out
        }

        /** The per-channel mean of the pixels [at] the center and each of [taps] away up, down, left and right. */
        private inline fun mean(taps: IntArray, at: (Int, Int) -> Int): Int {
            val values = IntArray(1 + 4 * taps.size)
            values[0] = at(0, 0)
            for ((i, d) in taps.withIndex()) {
                values[1 + 4 * i] = at(-d, 0)
                values[2 + 4 * i] = at(d, 0)
                values[3 + 4 * i] = at(0, -d)
                values[4 + 4 * i] = at(0, d)
            }
            var out = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) out = out or ((values.sumOf { (it shr shift) and 0xFF } / values.size) shl shift)
            return out
        }
    }
}

/** A runtime handing out [FakeModel]s: [gpu] when set, opening on it only when [gpuOpens]. */
internal class FakeRuntime(
    val gpu: GpuInfo? = GpuInfo("Test GPU", 2048, Fp16Modes.ALL, Long.MAX_VALUE),
    private val gpuOpens: Boolean = true,
    override val available: Boolean = true,
    private val make: (onGpu: Boolean, fp16: Boolean) -> TileModel? = { onGpu, _ -> FakeModel(onGpu = onGpu) }
) : InferenceRuntime {
    val opened = ArrayList<TileModel>()

    /** Each open's engine (true: the GPU) and precision (true: fp16). */
    val asked = ArrayList<Boolean>()
    val askedFp16 = ArrayList<Boolean>()

    override fun gpu(): GpuInfo? = gpu

    override fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? {
        asked += gpu
        askedFp16 += fp16
        if (gpu && (this.gpu == null || !gpuOpens)) return null
        return make(gpu, fp16)?.also { opened += it }
    }
}

/** Model files whose `.param` names [channels]-wide convolutions and the layer lines [extra], under [dir]. */
internal fun fakeFiles(dir: File, channels: Int = 32, extra: String = ""): ModelFiles {
    val param = File(dir, "model.param").apply {
        writeText("7767517\n3 3\nInput in0 0 1 in0\nConvolution conv_1 1 1 in0 a 0=$channels 1=3 4=1 5=1 6=864\n${extra}PixelShuffle ps 1 1 a out0 0=2\n")
    }
    val bin = File(dir, "model.bin").apply { writeBytes(ByteArray(4)) }
    return ModelFiles(param, bin)
}
