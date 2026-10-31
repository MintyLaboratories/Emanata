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
import kotlin.math.max
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
 * - With `premultiplied`, rows come and go as a bitmap holds them in memory, color
 *   channels already multiplied by alpha: what [NativeResampler]'s native code reads and
 *   writes, which this then models on the JVM.
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
        val ring = scaling.ring(enlarged = scale >= 1.0)
        if (ring <= 0f) return Axis(start, count, weights, stride)
        val ringLo = IntArray(dstLen)
        val ringHi = IntArray(dstLen)
        for (i in 0 until dstLen) {
            val center = from + (i + 0.5) / scale
            val (lo, hi) = ringRange(center, scale)
            ringLo[i] = lo.coerceIn(0, srcLen - 1)
            ringHi[i] = hi.coerceIn(0, srcLen - 1)
        }
        return Axis(start, count, weights, stride, ring, ringLo, ringHi)
    }

    /**
     * The source pixels anti-ringing clamps to around [center] at [scale]: the two either
     * side (as the GPU's [com.infinity.comicreader.ui.PageShader] does), widened to those
     * whose centers lie under the output pixel where they reach further, which happens
     * only when shrinking below 0.5×.
     */
    fun ringRange(center: Double, scale: Double): Pair<Int, Int> {
        val base = floor(center - 0.5).toInt()
        val half = 0.5 / scale
        val lo = ceil(center - half - 0.5).toInt()
        val hi = floor(center + half - 0.5).toInt()
        return min(lo, base) to max(hi, base + 1)
    }

    /** Reads source row [y] in full as ARGB into [out], premultiplied when the resample says so. Called from several threads. */
    fun interface RowReader {
        fun read(y: Int, out: IntArray)
    }

    /** Takes output rows [y] until [y] + [rows], ARGB premultiplied when the resample says so, [pixels] row-major. Called from several threads. */
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
        premultiplied: Boolean = false,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        canceled: () -> Boolean = { false }
    ) {
        val ax = axis(srcW, window.x, window.width, dstW, scaling)
        val ay = axis(srcH, window.y, window.height, dstH, scaling)
        val stopped = AtomicBoolean()
        if (workersFor(dstH, threads) <= 1) {
            withBand(srcW, ax, ay, linear, premultiplied, reader) { if (!it.run(0, dstH, writer, canceled)) stopped.set(true) }
        } else {
            // Each worker keeps its band's buffers while it takes band after band.
            val bands = arrayOfNulls<Band>(threads)
            val held = ArrayList<Buffers>()
            try {
                inBands(dstH, threads, { false }) { worker, y0, y1 ->
                    val band = bands[worker] ?: synchronized(held) {
                        Band(srcW, ax, ay, linear, premultiplied, reader, take().also { held += it })
                    }.also { bands[worker] = it }
                    band.run(y0, y1, writer, canceled).also { if (!it) stopped.set(true) }
                }
            } catch (_: CancellationException) {
                stopped.set(true)
            } finally {
                held.forEach { give(it) }
            }
        }
        if (stopped.get()) throw CancellationException("Resampling canceled")
    }

    /**
     * Runs [work] over output rows 0 until [dstH] in bands, on up to [threads] workers at
     * once (on [Dispatchers.Default] when more than one), each passing its own index below
     * [threads]; [work] returns false to stop every worker. Polls [canceled] before each
     * band and throws [CancellationException] once it returns true or [work] stopped.
     */
    fun inBands(dstH: Int, threads: Int, canceled: () -> Boolean, work: (worker: Int, y0: Int, y1: Int) -> Boolean) {
        val bands = bandsFor(dstH, threads)
        val stopped = AtomicBoolean()
        val next = AtomicInteger()
        val loop = { worker: Int ->
            while (!stopped.get()) {
                val b = next.getAndIncrement()
                if (b >= bands) break
                if (canceled() || !work(worker, bandStart(dstH, bands, b), bandStart(dstH, bands, b + 1))) stopped.set(true)
            }
        }
        val workers = workersFor(dstH, threads)
        if (workers <= 1) {
            loop(0)
        } else {
            runBlocking(Dispatchers.Default) { repeat(workers) { launch { loop(it) } } }
        }
        if (stopped.get()) throw CancellationException("Resampling canceled")
    }

    // A few bands per thread even out uneven progress; each band refilters only the
    // kernel's few rows it shares with the next.
    private fun bandsFor(dstH: Int, threads: Int) = min(dstH, threads.coerceAtLeast(1) * BANDS_PER_THREAD)

    private fun workersFor(dstH: Int, threads: Int) = min(threads, bandsFor(dstH, threads))

    private fun bandStart(dstH: Int, bands: Int, b: Int) = (dstH.toLong() * b / bands).toInt()

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
        premultiplied: Boolean = false,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        canceled: () -> Boolean = { false }
    ): IntArray {
        val out = IntArray(dstW * dstH)
        resample(
            srcW, srcH, { y, row -> System.arraycopy(src, y * srcW, row, 0, srcW) }, window, dstW, dstH, linear,
            { y, rows, pixels -> System.arraycopy(pixels, 0, out, y * dstW, rows * dstW) }, scaling, premultiplied, threads, canceled
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
    private inline fun withBand(
        srcW: Int, ax: Axis, ay: Axis, linear: Boolean, premultiplied: Boolean, reader: RowReader, block: (Band) -> Unit
    ) {
        val buffers = take()
        try {
            block(Band(srcW, ax, ay, linear, premultiplied, reader, buffers))
        } finally {
            give(buffers)
        }
    }

    /** Buffers from the pool, or new ones. */
    private fun take(): Buffers = synchronized(pool) {
        var found: Buffers? = null
        while (found == null && pool.isNotEmpty()) found = pool.removeLast().get()
        found
    } ?: Buffers()

    /** Returns [buffers] to the pool. */
    private fun give(buffers: Buffers) {
        synchronized(pool) { if (pool.size < maxPooled) pool.addLast(SoftReference(buffers)) }
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
        private val premultiplied: Boolean,
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

        /** True when the row [convert] last read is opaque. */
        private var opaque = true
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
                    out[x] = if (premultiplied) {
                        packPremultiplied(sum[o], sum[o + 1], sum[o + 2], sum[o + 3], linear)
                    } else {
                        pack(sum[o], sum[o + 1], sum[o + 2], sum[o + 3], linear)
                    }
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
         * `ay.ringLo[y]..ay.ringHi[y]`, which [vertical] just read into the ring: one or two
         * rows, the usual cases, without an inner loop.
         */
        private fun antiRingVertical(y: Int) {
            val s = ay.ring
            val rows = ay.ringHi[y] - ay.ringLo[y] + 1
            for (t in 0 until rows) ringRows[t] = row(ay.ringLo[y] + t)
            val a = ringRows[0]
            if (rows == 1) {
                for (j in 0 until rowLen) sum[j] += s * (ring[a + j] - sum[j])
                return
            }
            if (rows == 2) {
                val b = ringRows[1]
                for (j in 0 until rowLen) {
                    val p = ring[a + j]
                    val q = ring[b + j]
                    val v = sum[j]
                    sum[j] = v + s * (min(max(v, min(p, q)), max(p, q)) - v)
                }
                return
            }
            for (j in 0 until rowLen) {
                var mn = ring[a + j]
                var mx = mn
                for (t in 1 until rows) {
                    val v = ring[ringRows[t] + j]
                    mn = min(mn, v)
                    mx = max(mx, v)
                }
                val v = sum[j]
                sum[j] = v + s * (min(max(v, mn), mx) - v)
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
            filter(base)
            ringRow[slot] = sy
            return base
        }

        /**
         * Filters the row [convert] made into [ring] at [base]. An opaque row's alpha is 1
         * throughout, its weights summing to 1: only color is filtered.
         */
        private fun filter(base: Int) {
            val ringed = ax.ring > 0f
            when {
                opaque && ringed -> taps(base, alpha = false, ringed = true)
                opaque -> taps(base, alpha = false, ringed = false)
                ringed -> taps(base, alpha = true, ringed = true)
                else -> taps(base, alpha = true, ringed = false)
            }
        }

        /**
         * [filter] for a row with [alpha] or without, made once for each, so no test runs per
         * tap. When [ringed], each output pixel is blended toward itself clamped to the range
         * of its nearest source pixels (`ax.ringLo..ax.ringHi`, within its taps), which the
         * tap loop gathers as it reads them.
         */
        @Suppress("NOTHING_TO_INLINE")
        private inline fun taps(base: Int, alpha: Boolean, ringed: Boolean) {
            val s = ax.ring
            for (x in 0 until dstW) {
                val n = ax.count[x]
                val start = ax.start[x]
                // Taps ra..rb are the nearest pixels; without anti-ringing, none are.
                val ra = if (ringed) (ax.ringLo[x] - start).coerceIn(0, n - 1) else n
                val rb = if (ringed) (ax.ringHi[x] - start).coerceIn(ra, n - 1) else n - 1
                var o = (start - x0) * 4
                var wi = x * ax.stride
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (t in 0 until ra) {
                    val w = ax.weights[wi++]
                    r += w * converted[o]
                    g += w * converted[o + 1]
                    b += w * converted[o + 2]
                    if (alpha) a += w * converted[o + 3]
                    o += 4
                }
                val d = base + x * 4
                if (ringed) {
                    var rLo = converted[o]
                    var gLo = converted[o + 1]
                    var bLo = converted[o + 2]
                    var aLo = converted[o + 3]
                    var rHi = rLo
                    var gHi = gLo
                    var bHi = bLo
                    var aHi = aLo
                    for (t in ra..rb) {
                        val w = ax.weights[wi++]
                        val cr = converted[o]
                        val cg = converted[o + 1]
                        val cb = converted[o + 2]
                        r += w * cr
                        g += w * cg
                        b += w * cb
                        rLo = min(rLo, cr)
                        rHi = max(rHi, cr)
                        gLo = min(gLo, cg)
                        gHi = max(gHi, cg)
                        bLo = min(bLo, cb)
                        bHi = max(bHi, cb)
                        if (alpha) {
                            val ca = converted[o + 3]
                            a += w * ca
                            aLo = min(aLo, ca)
                            aHi = max(aHi, ca)
                        }
                        o += 4
                    }
                    for (t in rb + 1 until n) {
                        val w = ax.weights[wi++]
                        r += w * converted[o]
                        g += w * converted[o + 1]
                        b += w * converted[o + 2]
                        if (alpha) a += w * converted[o + 3]
                        o += 4
                    }
                    r += s * (min(max(r, rLo), rHi) - r)
                    g += s * (min(max(g, gLo), gHi) - g)
                    b += s * (min(max(b, bLo), bHi) - b)
                    if (alpha) a += s * (min(max(a, aLo), aHi) - a)
                }
                ring[d] = r
                ring[d + 1] = g
                ring[d + 2] = b
                ring[d + 3] = if (alpha) a else 1f
            }
        }

        /** The columns the horizontal taps read, from the row in [raw], as premultiplied 0..1 floats. */
        private fun convert() {
            if (premultiplied) return convertPremultiplied()
            val decode = if (linear) TO_LINEAR else TO_UNIT
            var o = 0
            var all = 255
            for (x in x0 until ax.end) {
                val p = raw[x]
                val a = p ushr 24
                all = all and a
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
            opaque = all == 255
        }

        /**
         * [convert] for premultiplied pixels: kept as they are or, in linear light, a
         * translucent pixel's colors divided by its alpha (rounded to codes), decoded and
         * multiplied again.
         */
        private fun convertPremultiplied() {
            var o = 0
            var all = 255
            for (x in x0 until ax.end) {
                val p = raw[x]
                val a = p ushr 24
                all = all and a
                if (!linear) {
                    converted[o] = TO_UNIT[(p shr 16) and 0xFF]
                    converted[o + 1] = TO_UNIT[(p shr 8) and 0xFF]
                    converted[o + 2] = TO_UNIT[p and 0xFF]
                } else if (a == 255) {
                    converted[o] = TO_LINEAR[(p shr 16) and 0xFF]
                    converted[o + 1] = TO_LINEAR[(p shr 8) and 0xFF]
                    converted[o + 2] = TO_LINEAR[p and 0xFF]
                } else if (a == 0) {
                    converted[o] = 0f
                    converted[o + 1] = 0f
                    converted[o + 2] = 0f
                } else {
                    val af = TO_UNIT[a]
                    converted[o] = TO_LINEAR[unpremultiply((p shr 16) and 0xFF, a)] * af
                    converted[o + 1] = TO_LINEAR[unpremultiply((p shr 8) and 0xFF, a)] * af
                    converted[o + 2] = TO_LINEAR[unpremultiply(p and 0xFF, a)] * af
                }
                converted[o + 3] = TO_UNIT[a]
                o += 4
            }
            opaque = all == 255
        }
    }

    /** Premultiplied code [c] of alpha [a] (1..254) as the color's own code, rounded. */
    private fun unpremultiply(c: Int, a: Int): Int = min(255, (c * 255 + a / 2) / a)

    /**
     * Premultiplied 0..1 channels to one premultiplied ARGB pixel: alpha clamped to 0..1 and
     * colors to 0..alpha, so no color code exceeds its alpha's. In linear light, colors are
     * divided by alpha, encoded, and multiplied by it again.
     */
    private fun packPremultiplied(r: Float, g: Float, b: Float, a: Float, linear: Boolean): Int {
        val alpha = min(max(a, 0f), 1f)
        val ai = code(alpha)
        if (ai <= 0) return 0
        if (!linear) {
            return (ai shl 24) or (code(min(max(r, 0f), alpha)) shl 16) or (code(min(max(g, 0f), alpha)) shl 8) or
                code(min(max(b, 0f), alpha))
        }
        val inv = 1f / alpha
        return (ai shl 24) or (premultiply(encode(r * inv), ai) shl 16) or (premultiply(encode(g * inv), ai) shl 8) or
            premultiply(encode(b * inv), ai)
    }

    /** The code of [v], 0..1. */
    private fun code(v: Float): Int = (v * 255f + 0.5f).toInt()

    /** Code [c] multiplied by alpha [a], rounded; [c] itself when opaque. */
    private fun premultiply(c: Int, a: Int): Int = if (a == 255) c else (c * a + 127) / 255

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
