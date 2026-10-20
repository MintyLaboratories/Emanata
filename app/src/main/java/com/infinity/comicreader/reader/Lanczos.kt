package com.infinity.comicreader.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Separable Lanczos3 resampling of 8-bit ARGB pixels, free of Android types.
 *
 * - Output pixel `i` covers source coordinate `from + (i + 0.5) * span / dst` on each
 *   axis, source pixel `j` having its center at `j + 0.5`.
 * - Shrinking widens the kernel to `3 / scale` source pixels, so every source pixel
 *   contributes; enlarging keeps it at 3.
 * - Taps past the image edge repeat the edge pixel; each output's weights sum to 1.
 * - Filtering runs on premultiplied alpha in float. The horizontal pass keeps its
 *   negative lobes for the vertical one; only the final value is clamped.
 * - With `linear`, colors are filtered in linear light (sRGB decoded, then encoded).
 *
 * Output rows are split into bands filtered in parallel on [Dispatchers.Default].
 * Each band filters its source rows horizontally once, into a ring buffer as tall
 * as the vertical kernel and as wide as the output, so memory stays a few output
 * rows per band whatever the source size.
 */
object Lanczos {
    const val RADIUS = 3

    /** Lanczos3 kernel: `sinc(x) * sinc(x / 3)` on `|x| < 3`. */
    fun kernel(x: Double): Double {
        val ax = abs(x)
        if (ax >= RADIUS) return 0.0
        if (ax < 1e-12) return 1.0
        val px = PI * ax
        return RADIUS * sin(px) * sin(px / RADIUS) / (px * px)
    }

    /**
     * Taps of one axis: output `i` reads source pixels `start[i]` until `start[i] + count[i]`
     * with weights from `weights[i * stride]`. [first] and [end] bound every tap.
     */
    class Axis(val start: IntArray, val count: IntArray, val weights: FloatArray, val stride: Int) {
        val first: Int = start.min()
        val end: Int = start.indices.maxOf { start[it] + count[it] }
    }

    /** Taps that map source span [from] until [from] + [span] of a [srcLen]-pixel axis onto [dstLen] pixels. */
    fun axis(srcLen: Int, from: Double, span: Double, dstLen: Int): Axis {
        require(srcLen > 0 && dstLen > 0 && span > 0) { "Empty axis" }
        val scale = dstLen / span
        val k = min(1.0, scale)
        val support = RADIUS / k
        val stride = ceil(2 * support).toInt() + 2
        val start = IntArray(dstLen)
        val count = IntArray(dstLen)
        val weights = FloatArray(dstLen * stride)
        val acc = DoubleArray(stride)
        for (i in 0 until dstLen) {
            val center = from + (i + 0.5) / scale
            val lo = ceil(center - 0.5 - support).toInt()
            val hi = floor(center - 0.5 + support).toInt()
            val cLo = lo.coerceIn(0, srcLen - 1)
            val n = hi.coerceIn(0, srcLen - 1) - cLo + 1
            acc.fill(0.0, 0, n)
            var sum = 0.0
            for (j in lo..hi) {
                val w = kernel((j + 0.5 - center) * k)
                if (w == 0.0) continue
                acc[j.coerceIn(0, srcLen - 1) - cLo] += w
                sum += w
            }
            start[i] = cLo
            count[i] = n
            for (t in 0 until n) weights[i * stride + t] = (acc[t] / sum).toFloat()
        }
        return Axis(start, count, weights, stride)
    }

    /** Reads source row [y] in full as non-premultiplied ARGB into [out]. Called from several threads. */
    fun interface RowReader {
        fun read(y: Int, out: IntArray)
    }

    /** Takes output rows [y] until [y] + [rows], non-premultiplied ARGB, [pixels] row-major. Called from several threads. */
    fun interface RowWriter {
        fun write(y: Int, rows: Int, pixels: IntArray)
    }

    /** The source rectangle mapped onto the whole output, in source pixels; may be fractional. */
    data class Window(val x: Double, val y: Double, val width: Double, val height: Double) {
        companion object {
            fun of(width: Int, height: Int) = Window(0.0, 0.0, width.toDouble(), height.toDouble())
        }
    }

    /**
     * Resamples [window] of a [srcW] x [srcH] image read through [reader] to [dstW] x [dstH],
     * handing finished rows to [writer] in bands, on up to [threads] threads.
     */
    fun resample(
        srcW: Int,
        srcH: Int,
        reader: RowReader,
        window: Window,
        dstW: Int,
        dstH: Int,
        linear: Boolean,
        writer: RowWriter,
        threads: Int = Runtime.getRuntime().availableProcessors()
    ) {
        val ax = axis(srcW, window.x, window.width, dstW)
        val ay = axis(srcH, window.y, window.height, dstH)
        // A few bands per thread even out uneven progress; each band refilters only
        // the kernel's few rows it shares with the next.
        val bands = min(dstH, threads.coerceAtLeast(1) * BANDS_PER_THREAD)
        if (bands <= 1 || threads <= 1) {
            Band(srcW, ax, ay, linear, reader).run(0, dstH, writer)
            return
        }
        runBlocking(Dispatchers.Default) {
            for (b in 0 until bands) {
                val y0 = (dstH.toLong() * b / bands).toInt()
                val y1 = (dstH.toLong() * (b + 1) / bands).toInt()
                if (y1 > y0) launch { Band(srcW, ax, ay, linear, reader).run(y0, y1, writer) }
            }
        }
    }

    /** [resample] for pixels held in an array, row-major. */
    fun resample(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        window: Window,
        dstW: Int,
        dstH: Int,
        linear: Boolean = false,
        threads: Int = Runtime.getRuntime().availableProcessors()
    ): IntArray {
        val out = IntArray(dstW * dstH)
        resample(
            srcW, srcH, { y, row -> System.arraycopy(src, y * srcW, row, 0, srcW) }, window, dstW, dstH, linear,
            { y, rows, pixels -> System.arraycopy(pixels, 0, out, y * dstW, rows * dstW) }, threads
        )
        return out
    }

    /**
     * One band's buffers: a ring of horizontally filtered source rows, as wide as the
     * output, and the vertical sum for the current output row.
     */
    private class Band(
        srcW: Int,
        private val ax: Axis,
        private val ay: Axis,
        private val linear: Boolean,
        private val reader: RowReader
    ) {
        private val dstW = ax.start.size
        private val x0 = ax.first
        private val raw = IntArray(srcW)
        private val converted = FloatArray((ax.end - ax.first) * 4)
        private val ring = Array(ay.stride) { FloatArray(dstW * 4) }
        private val ringRow = IntArray(ay.stride) { -1 }
        private val sum = FloatArray(dstW * 4)

        fun run(y0: Int, y1: Int, writer: RowWriter) {
            val out = IntArray((y1 - y0) * dstW)
            for (y in y0 until y1) {
                vertical(y)
                val offset = (y - y0) * dstW
                var o = 0
                for (x in 0 until dstW) {
                    out[offset + x] = pack(sum[o], sum[o + 1], sum[o + 2], sum[o + 3], linear)
                    o += 4
                }
            }
            writer.write(y0, y1 - y0, out)
        }

        private fun vertical(y: Int) {
            val n = ay.count[y]
            val s = ay.start[y]
            val wBase = y * ay.stride
            val first = row(s)
            val w0 = ay.weights[wBase]
            for (j in sum.indices) sum[j] = w0 * first[j]
            for (t in 1 until n) {
                val src = row(s + t)
                val w = ay.weights[wBase + t]
                for (j in sum.indices) sum[j] += w * src[j]
            }
        }

        /** Source row [sy] filtered horizontally, premultiplied 0..1 channels; from the ring or made now. */
        private fun row(sy: Int): FloatArray {
            val slot = sy % ring.size
            val buf = ring[slot]
            if (ringRow[slot] == sy) return buf
            reader.read(sy, raw)
            convert()
            for (x in 0 until dstW) {
                val n = ax.count[x]
                var o = (ax.start[x] - x0) * 4
                var wi = x * ax.stride
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (t in 0 until n) {
                    val w = ax.weights[wi++]
                    r += w * converted[o]
                    g += w * converted[o + 1]
                    b += w * converted[o + 2]
                    a += w * converted[o + 3]
                    o += 4
                }
                val d = x * 4
                buf[d] = r
                buf[d + 1] = g
                buf[d + 2] = b
                buf[d + 3] = a
            }
            ringRow[slot] = sy
            return buf
        }

        /** The columns the horizontal taps read, from the row in [raw], as premultiplied 0..1 floats. */
        private fun convert() {
            val decode = if (linear) TO_LINEAR else TO_UNIT
            var o = 0
            for (x in x0 until ax.end) {
                val p = raw[x]
                val a = p ushr 24
                if (a == 255) {
                    converted[o] = decode[(p shr 16) and 0xFF]
                    converted[o + 1] = decode[(p shr 8) and 0xFF]
                    converted[o + 2] = decode[p and 0xFF]
                    converted[o + 3] = 1f
                } else {
                    val af = TO_UNIT[a]
                    converted[o] = decode[(p shr 16) and 0xFF] * af
                    converted[o + 1] = decode[(p shr 8) and 0xFF] * af
                    converted[o + 2] = decode[p and 0xFF] * af
                    converted[o + 3] = af
                }
                o += 4
            }
        }
    }

    /** Premultiplied 0..1 channels back to one non-premultiplied ARGB pixel. */
    private fun pack(r: Float, g: Float, b: Float, a: Float, linear: Boolean): Int {
        val alpha = if (a >= 1f) 1f else a
        val ai = (alpha * 255f + 0.5f).toInt()
        if (ai <= 0) return 0
        val inv = 1f / alpha
        return if (linear) {
            (ai shl 24) or (encode(r * inv) shl 16) or (encode(g * inv) shl 8) or encode(b * inv)
        } else {
            (ai shl 24) or (unit(r * inv) shl 16) or (unit(g * inv) shl 8) or unit(b * inv)
        }
    }

    private fun unit(v: Float): Int = if (v <= 0f) 0 else if (v >= 1f) 255 else (v * 255f + 0.5f).toInt()

    private fun encode(v: Float): Int =
        if (v <= 0f) 0 else if (v >= 1f) 255 else FROM_LINEAR[(v * (FROM_LINEAR.size - 1) + 0.5f).toInt()].toInt() and 0xFF

    private const val BANDS_PER_THREAD = 3

    private val TO_UNIT = FloatArray(256) { it / 255f }

    /** sRGB code value to linear light, 0..1. */
    private val TO_LINEAR = FloatArray(256) { srgbToLinear(it / 255.0).toFloat() }

    /** Linear light to sRGB code values, sampled finely enough that the darkest codes round correctly. */
    private val FROM_LINEAR = ByteArray(1 shl 16) {
        (linearToSrgb(it / ((1 shl 16) - 1.0)) * 255.0).roundToInt().coerceIn(0, 255).toByte()
    }

    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
}
