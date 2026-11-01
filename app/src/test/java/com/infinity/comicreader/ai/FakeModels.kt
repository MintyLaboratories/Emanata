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
 * code does: the nearest edge pixel.
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
    private val onRun: (Int) -> Unit = {}
) : TileModel {
    val trim: Int get() = if (padded) 0 else reach * scale
    val runs = AtomicInteger()
    val trims = AtomicInteger()
    val inside = AtomicInteger()
    var mostInside = 0
    var closed = false
    val inputs = ArrayList<Box>()

    override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
        if (closed) return null
        val now = inside.incrementAndGet()
        synchronized(this) { mostInside = maxOf(mostInside, now) }
        try {
            val i = runs.getAndIncrement()
            onRun(i)
            synchronized(inputs) { inputs += input }
            if (i == failAt) return null
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
                    row[x] = pixel(tile, n, m, (c.x + x + trim) / scale, (c.y + y + trim) / scale)
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

    override fun trim() {
        trims.incrementAndGet()
    }

    override fun close() {
        closed = true
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
    val gpu: GpuInfo? = GpuInfo("Test GPU", 2048, true),
    private val gpuOpens: Boolean = true,
    override val available: Boolean = true,
    private val make: (onGpu: Boolean) -> TileModel? = { FakeModel(onGpu = it) }
) : InferenceRuntime {
    val opened = ArrayList<TileModel>()
    val asked = ArrayList<Boolean>()

    override fun gpu(): GpuInfo? = gpu

    override fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? {
        asked += gpu
        if (gpu && (this.gpu == null || !gpuOpens)) return null
        return make(gpu)?.also { opened += it }
    }
}

/** Model files whose `.param` names [channels]-wide convolutions, under [dir]. */
internal fun fakeFiles(dir: File, channels: Int = 32): ModelFiles {
    val param = File(dir, "model.param").apply {
        writeText("7767517\n3 3\nInput in0 0 1 in0\nConvolution conv_1 1 1 in0 a 0=$channels 1=3 4=1 5=1 6=864\nPixelShuffle ps 1 1 a out0 0=2\n")
    }
    val bin = File(dir, "model.bin").apply { writeBytes(ByteArray(4)) }
    return ModelFiles(param, bin)
}
