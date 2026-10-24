package com.infinity.comicreader.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.SoftReference
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Separable resampling of 8-bit ARGB pixels through a [ScaleFilter], free of Android types.
 *
 * - Output pixel `i` covers source coordinate `from + (i + 0.5) * span / dst` on each
 *   axis, source pixel `j` having its center at `j + 0.5`.
 * - Shrinking widens the kernel to `radius / scale` source pixels, so every source pixel
 *   contributes; enlarging keeps it at the filter's radius.
 * - Taps past the image edge repeat the edge pixel; each output's weights sum to 1.
 * - Filtering runs on premultiplied alpha in float. The horizontal pass keeps its
 *   negative lobes for the vertical one; only the final value is clamped to 0..1.
 * - Anti-ringing ([Scaling]) follows each pass, per axis, only on an axis whose strength
 *   is above 0.
 * - With `linear`, colors are filtered in linear light (sRGB decoded, then encoded).
 *
 * Output rows are split into bands, which one worker per thread on [Dispatchers.Default]
 * takes in turn. A worker filters its source rows horizontally once, into a ring buffer
 * as tall as the vertical kernel and as wide as the output, so memory stays a few output
 * rows per worker whatever the source size; its buffers are pooled between resamples.
 */
object Resampler {
    /**
     * Taps of one axis: output `i` reads source pixels `start[i]` until `start[i] + count[i]`
     * with weights from `weights[i * stride]`. [first] and [end] bound every tap. With a
     * [ring] strength above 0, output `i`'s anti-ringing range is source pixels
     * `ringLo[i]..ringHi[i]`, within its taps.
     */
    class Axis(
        val start: IntArray,
        val count: IntArray,
        val weights: FloatArray,
        val stride: Int,
        val ring: Float = 0f,
        val ringLo: IntArray = IntArray(0),
        val ringHi: IntArray = IntArray(0)
    ) {
        val first: Int = start.min()
        val end: Int = start.indices.maxOf { start[it] + count[it] }
    }

    /**
     * Taps that map source span [from] until [from] + [span] of a [srcLen]-pixel axis onto
     * [dstLen] pixels through [scaling]'s filter, with its anti-ringing for the direction.
     */
    fun axis(srcLen: Int, from: Double, span: Double, dstLen: Int, scaling: Scaling = Scaling.PLAIN): Axis {
        require(srcLen > 0 && dstLen > 0 && span > 0) { "Empty axis" }
        val filter = scaling.filter
        val scale = dstLen / span
        val k = min(1.0, scale)
        val support = filter.radius / k
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
                val w = filter.weight((j + 0.5 - center) * k)
                if (w == 0.0) continue
                acc[j.coerceIn(0, srcLen - 1) - cLo] += w
                sum += w
            }
            start[i] = cLo
            count[i] = n
            for (t in 0 until n) weights[i * stride + t] = (acc[t] / sum).toFloat()
        }
        val enlarged = scale >= 1.0
        val ring = scaling.ring(enlarged)
        if (ring <= 0f) return Axis(start, count, weights, stride)
        val ringLo = IntArray(dstLen)
        val ringHi = IntArray(dstLen)
        for (i in 0 until dstLen) {
            val center = from + (i + 0.5) / scale
            val (lo, hi) = ringRange(center, scale, enlarged)
            ringLo[i] = lo.coerceIn(0, srcLen - 1)
            ringHi[i] = hi.coerceIn(0, srcLen - 1)
        }
        return Axis(start, count, weights, stride, ring, ringLo, ringHi)
    }

    /**
     * The source pixels anti-ringing clamps to around [center] at [scale]: the two either
     * side when [enlarged], else those whose centers lie under the output pixel (the one
     * nearest when none does).
     */
    fun ringRange(center: Double, scale: Double, enlarged: Boolean): Pair<Int, Int> {
        if (enlarged) {
            val base = floor(center - 0.5).toInt()
            return base to base + 1
        }
        val half = 0.5 / scale
        val lo = ceil(center - half - 0.5).toInt()
        val hi = floor(center + half - 0.5).toInt()
        if (hi >= lo) return lo to hi
        val nearest = floor(center).toInt()
        return nearest to nearest
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
     * Resamples [window] of a [srcW] x [srcH] image read through [reader] to [dstW] x [dstH]
     * as [scaling] says, handing each finished row to [writer], on up to [threads] threads.
     * Blocks the calling thread until done, so it must not run on [Dispatchers.Default]
     * itself. Polls [canceled] before each row and, once it returns true, stops every
     * thread and throws [CancellationException].
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
        scaling: Scaling = Scaling.PLAIN,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        canceled: () -> Boolean = { false }
    ) {
        val ax = axis(srcW, window.x, window.width, dstW, scaling)
        val ay = axis(srcH, window.y, window.height, dstH, scaling)
        // A few bands per thread even out uneven progress; each band refilters only
        // the kernel's few rows it shares with the next.
        val bands = min(dstH, threads.coerceAtLeast(1) * BANDS_PER_THREAD)
        val workers = min(threads, bands)
        val stopped = AtomicBoolean()
        if (workers <= 1) {
            withBand(srcW, ax, ay, linear, reader) { if (!it.run(0, dstH, writer, canceled)) stopped.set(true) }
        } else {
            val next = AtomicInteger()
            runBlocking(Dispatchers.Default) {
                repeat(workers) {
                    launch {
                        withBand(srcW, ax, ay, linear, reader) { band ->
                            while (!stopped.get()) {
                                val b = next.getAndIncrement()
                                if (b >= bands) break
                                val y0 = (dstH.toLong() * b / bands).toInt()
                                val y1 = (dstH.toLong() * (b + 1) / bands).toInt()
                                if (!band.run(y0, y1, writer, canceled)) stopped.set(true)
                            }
                        }
                    }
                }
            }
        }
        if (stopped.get()) throw CancellationException("Resampling canceled")
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
        scaling: Scaling = Scaling.PLAIN,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        canceled: () -> Boolean = { false }
    ): IntArray {
        val out = IntArray(dstW * dstH)
        resample(
            srcW, srcH, { y, row -> System.arraycopy(src, y * srcW, row, 0, srcW) }, window, dstW, dstH, linear,
            { y, rows, pixels -> System.arraycopy(pixels, 0, out, y * dstW, rows * dstW) }, scaling, threads, canceled
        )
        return out
    }

    /** Working arrays of one worker, kept between resamples and grown as sizes need. */
    private class Buffers {
        var raw = IntArray(0)
        var converted = FloatArray(0)
        var ring = FloatArray(0)
        var ringRow = IntArray(0)
        var sum = FloatArray(0)
        var out = IntArray(0)
    }

    /** Buffers no worker is using; soft, so memory pressure can free them. */
    private val pool = ArrayDeque<SoftReference<Buffers>>()

    private val maxPooled = Runtime.getRuntime().availableProcessors()

    /** Runs [block] with a [Band] on pooled buffers, returned to the pool after. */
    private inline fun withBand(srcW: Int, ax: Axis, ay: Axis, linear: Boolean, reader: RowReader, block: (Band) -> Unit) {
        val buffers = synchronized(pool) {
            var found: Buffers? = null
            while (found == null && pool.isNotEmpty()) found = pool.removeLast().get()
            found
        } ?: Buffers()
        try {
            block(Band(srcW, ax, ay, linear, reader, buffers))
        } finally {
            synchronized(pool) { if (pool.size < maxPooled) pool.addLast(SoftReference(buffers)) }
        }
    }

    /**
     * One worker's view of its [Buffers]: a ring of horizontally filtered source rows,
     * as wide as the output, the vertical sum for the current output row, and that row
     * packed for the writer.
     */
    private class Band(
        srcW: Int,
        private val ax: Axis,
        private val ay: Axis,
        private val linear: Boolean,
        private val reader: RowReader,
        buffers: Buffers
    ) {
        private val dstW = ax.start.size
        private val rowLen = dstW * 4
        private val x0 = ax.first
        private val raw = buffers.raw.atLeast(srcW).also { buffers.raw = it }
        private val converted = buffers.converted.atLeast((ax.end - ax.first) * 4).also { buffers.converted = it }
        private val ring = buffers.ring.atLeast(ay.stride * rowLen).also { buffers.ring = it }
        private val ringRow = buffers.ringRow.atLeast(ay.stride).also {
            buffers.ringRow = it
            it.fill(-1, 0, ay.stride)
        }
        private val sum = buffers.sum.atLeast(rowLen).also { buffers.sum = it }
        private val out = buffers.out.atLeast(dstW).also { buffers.out = it }

        /** Offsets in [ring] of the rows [antiRingVertical] clamps to. */
        private val ringRows = IntArray(ay.stride)

        /** Filters output rows [y0] until [y1], writing each as it is done; false when [canceled] stopped it. */
        fun run(y0: Int, y1: Int, writer: RowWriter, canceled: () -> Boolean): Boolean {
            for (y in y0 until y1) {
                if (canceled()) return false
                vertical(y)
                if (ay.ring > 0f) antiRingVertical(y)
                var o = 0
                for (x in 0 until dstW) {
                    out[x] = pack(sum[o], sum[o + 1], sum[o + 2], sum[o + 3], linear)
                    o += 4
                }
                writer.write(y, 1, out)
            }
            return true
        }

        private fun vertical(y: Int) {
            val n = ay.count[y]
            val s = ay.start[y]
            val wBase = y * ay.stride
            val first = row(s)
            val w0 = ay.weights[wBase]
            for (j in 0 until rowLen) sum[j] = w0 * ring[first + j]
            for (t in 1 until n) {
                val src = row(s + t)
                val w = ay.weights[wBase + t]
                for (j in 0 until rowLen) sum[j] += w * ring[src + j]
            }
        }

        /**
         * Blends [sum] toward itself clamped to the range of the filtered rows
         * `ay.ringLo[y]..ay.ringHi[y]`, which [vertical] just read into the ring.
         */
        private fun antiRingVertical(y: Int) {
            val s = ay.ring
            val rows = ay.ringHi[y] - ay.ringLo[y] + 1
            for (t in 0 until rows) ringRows[t] = row(ay.ringLo[y] + t)
            val lo = ringRows[0]
            for (j in 0 until rowLen) {
                var mn = ring[lo + j]
                var mx = mn
                for (t in 1 until rows) {
                    val v = ring[ringRows[t] + j]
                    if (v < mn) mn = v else if (v > mx) mx = v
                }
                val v = sum[j]
                val c = if (v < mn) mn else if (v > mx) mx else v
                sum[j] = v + s * (c - v)
            }
        }

        /**
         * Offset in [ring] of source row [sy] filtered horizontally, premultiplied 0..1
         * channels, anti-ringing applied; kept from earlier or made now.
         */
        private fun row(sy: Int): Int {
            val slot = sy % ay.stride
            val base = slot * rowLen
            if (ringRow[slot] == sy) return base
            reader.read(sy, raw)
            convert()
            val antiRing = ax.ring > 0f
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
                val d = base + x * 4
                ring[d] = r
                ring[d + 1] = g
                ring[d + 2] = b
                ring[d + 3] = a
                if (antiRing) antiRingHorizontal(x, d)
            }
            ringRow[slot] = sy
            return base
        }

        /** Blends the filtered pixel at [d] in [ring] toward itself clamped to the range of output [x]'s nearest source pixels. */
        private fun antiRingHorizontal(x: Int, d: Int) {
            val s = ax.ring
            val from = (ax.ringLo[x] - x0) * 4
            val to = (ax.ringHi[x] - x0) * 4
            for (c in 0 until 4) {
                var mn = converted[from + c]
                var mx = mn
                var o = from + 4 + c
                while (o <= to + c) {
                    val v = converted[o]
                    if (v < mn) mn = v else if (v > mx) mx = v
                    o += 4
                }
                val v = ring[d + c]
                val clamped = if (v < mn) mn else if (v > mx) mx else v
                ring[d + c] = v + s * (clamped - v)
            }
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

    private fun IntArray.atLeast(n: Int): IntArray = if (size >= n) this else IntArray(n)

    private fun FloatArray.atLeast(n: Int): FloatArray = if (size >= n) this else FloatArray(n)

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
