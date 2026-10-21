package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class LanczosTest {

    // ------------------------------------------------------------------ reference

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun lanczos3(x: Double) = if (abs(x) < 3) sinc(x) * sinc(x / 3) else 0.0

    /** Normalized taps of output [i] as (clamped source index, weight), straight from the definition. */
    private fun taps(srcLen: Int, from: Double, span: Double, dstLen: Int, i: Int): List<Pair<Int, Double>> {
        val scale = dstLen / span
        val k = min(1.0, scale)
        val center = from + (i + 0.5) / scale
        val support = 3 / k
        val raw = (floor(center - support - 1).toInt()..ceil(center + support + 1).toInt())
            .map { j -> j.coerceIn(0, srcLen - 1) to lanczos3((j + 0.5 - center) * k) }
            .filter { it.second != 0.0 }
        val sum = raw.sumOf { it.second }
        return raw.map { it.first to it.second / sum }
    }

    private fun toLinear(c: Int) = Lanczos.srgbToLinear(c / 255.0)

    /** Direct 2-D Lanczos3 in double precision: weights are the products of both axes' taps. */
    private fun reference(
        src: IntArray, srcW: Int, srcH: Int, win: Lanczos.Window, dstW: Int, dstH: Int, linear: Boolean
    ): IntArray = IntArray(dstW * dstH) { o ->
        val x = o % dstW
        val y = o / dstW
        val tx = taps(srcW, win.x, win.width, dstW, x)
        val ty = taps(srcH, win.y, win.height, dstH, y)
        val acc = DoubleArray(4)
        for ((sy, wy) in ty) for ((sx, wx) in tx) {
            val p = src[sy * srcW + sx]
            val a = (p ushr 24) / 255.0
            val w = wx * wy
            for (c in 0..2) {
                val code = (p shr (16 - 8 * c)) and 0xFF
                acc[c] += w * a * (if (linear) toLinear(code) else code / 255.0)
            }
            acc[3] += w * a
        }
        val a = acc[3].coerceAtMost(1.0)
        // A pixel too faint to keep any alpha keeps no color either.
        if ((a * 255).roundToInt() <= 0) return@IntArray 0
        var pixel = (a * 255).roundToInt() shl 24
        for (c in 0..2) {
            val v = (acc[c] / a).coerceIn(0.0, 1.0)
            val code = if (linear) Lanczos.linearToSrgb(v) * 255 else v * 255
            pixel = pixel or (code.roundToInt().coerceIn(0, 255) shl (16 - 8 * c))
        }
        pixel
    }

    private fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        var worst = 0
        for (i in a.indices) for (shift in 0..24 step 8) {
            worst = max(worst, abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
        }
        return worst
    }

    private fun noise(w: Int, h: Int, seed: Int, opaque: Boolean): IntArray {
        val r = Random(seed)
        return IntArray(w * h) {
            val alpha = if (opaque) 255 else r.nextInt(256)
            (alpha shl 24) or (r.nextInt(1 shl 24))
        }
    }

    private fun checkAgainstReference(srcW: Int, srcH: Int, win: Lanczos.Window, dstW: Int, dstH: Int, opaque: Boolean, linear: Boolean) {
        val src = noise(srcW, srcH, srcW * 31 + dstW, opaque)
        val ours = Lanczos.resample(src, srcW, srcH, win, dstW, dstH, linear)
        val ref = reference(src, srcW, srcH, win, dstW, dstH, linear)
        val diff = maxChannelDiff(ours, ref)
        assertTrue("max channel difference $diff for $srcW x $srcH -> $dstW x $dstH", diff <= 1)
    }

    // ------------------------------------------------------------------ against the reference

    @Test
    fun downscaleMatchesReference() {
        checkAgainstReference(61, 47, Lanczos.Window.of(61, 47), 17, 13, opaque = true, linear = false)
        checkAgainstReference(61, 47, Lanczos.Window.of(61, 47), 17, 13, opaque = false, linear = false)
    }

    @Test
    fun upscaleMatchesReference() {
        checkAgainstReference(13, 11, Lanczos.Window.of(13, 11), 40, 29, opaque = true, linear = false)
        checkAgainstReference(13, 11, Lanczos.Window.of(13, 11), 40, 29, opaque = false, linear = false)
    }

    @Test
    fun mixedAxesMatchReference() {
        // Shrinks one axis while enlarging the other.
        checkAgainstReference(40, 12, Lanczos.Window.of(40, 12), 15, 31, opaque = false, linear = false)
    }

    @Test
    fun fractionalWindowMatchesReference() {
        // A centered cover crop: part of the source, at sub-pixel offsets.
        checkAgainstReference(50, 70, Lanczos.Window(0.0, 11.25, 50.0, 47.5), 20, 19, opaque = true, linear = false)
    }

    @Test
    fun largeShrinkMatchesReference() {
        // About 13:1 and 7:1, where each output reads 78 and 42 source pixels per axis.
        checkAgainstReference(300, 160, Lanczos.Window.of(300, 160), 23, 23, opaque = false, linear = false)
        checkAgainstReference(300, 160, Lanczos.Window.of(300, 160), 23, 23, opaque = true, linear = true)
    }

    @Test
    fun linearLightMatchesReference() {
        checkAgainstReference(61, 47, Lanczos.Window.of(61, 47), 17, 13, opaque = true, linear = true)
        checkAgainstReference(13, 11, Lanczos.Window.of(13, 11), 40, 29, opaque = false, linear = true)
    }

    // ------------------------------------------------------------------ properties

    @Test
    fun constantImageStaysConstant() {
        for (color in intArrayOf(0xFF3C82C8.toInt(), 0x80402010.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            val src = IntArray(23 * 17) { color }
            for ((w, h) in listOf(7 to 5, 23 to 17, 60 to 41)) {
                for (linear in listOf(false, true)) {
                    val out = Lanczos.resample(src, 23, 17, Lanczos.Window.of(23, 17), w, h, linear)
                    assertTrue(out.all { maxChannelDiff(intArrayOf(it), intArrayOf(color)) <= if (linear) 1 else 0 })
                }
            }
        }
    }

    @Test
    fun scaleOneIsIdentity() {
        val src = noise(33, 21, 7, opaque = false)
        val out = Lanczos.resample(src, 33, 21, Lanczos.Window.of(33, 21), 33, 21)
        // Premultiplied, a transparent pixel keeps no color, and a translucent one may move by a code.
        val visible = IntArray(src.size) { if (src[it] ushr 24 == 0) 0 else src[it] }
        assertTrue(maxChannelDiff(visible, out) <= 1)
        val opaque = noise(33, 21, 8, opaque = true)
        assertArrayEquals(opaque, Lanczos.resample(opaque, 33, 21, Lanczos.Window.of(33, 21), 33, 21))
    }

    @Test
    fun integerOffsetAtScaleOneIsACrop() {
        val src = noise(30, 20, 9, opaque = true)
        val out = Lanczos.resample(src, 30, 20, Lanczos.Window(4.0, 3.0, 10.0, 12.0), 10, 12)
        val expected = IntArray(10 * 12) { src[(3 + it / 10) * 30 + 4 + it % 10] }
        assertArrayEquals(expected, out)
    }

    /** A vertical edge from gray [low] to gray [high] at x = [w] / 2. */
    private fun edge(w: Int, h: Int, low: Int, high: Int) = IntArray(w * h) {
        val v = if (it % w < w / 2) low else high
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun ringingIsBounded() {
        val src = edge(16, 4, 64, 192)
        for ((w, h) in listOf(64 to 4, 9 to 4, 5 to 2)) {
            val out = Lanczos.resample(src, 16, 4, Lanczos.Window.of(16, 4), w, h).map { it and 0xFF }
            // Lanczos3 overshoots a step by under 13% of its height on either side.
            assertTrue("$w x $h: ${out.max()}", out.max() <= 192 + 17)
            assertTrue("$w x $h: ${out.min()}", out.min() >= 64 - 17)
        }
        // Enlarging does ring a little: this is Lanczos, not a smoothing filter.
        val up = Lanczos.resample(src, 16, 4, Lanczos.Window.of(16, 4), 64, 4).map { it and 0xFF }
        assertTrue(up.max() > 192 && up.min() < 64)
    }

    @Test
    fun bandsMatchOneThread() {
        val src = noise(97, 89, 3, opaque = false)
        val one = Lanczos.resample(src, 97, 89, Lanczos.Window.of(97, 89), 41, 53, threads = 1)
        val many = Lanczos.resample(src, 97, 89, Lanczos.Window.of(97, 89), 41, 53, threads = 8)
        assertArrayEquals(one, many)
    }

    @Test
    fun reusedBuffersGiveTheSameResult() {
        val small = noise(31, 29, 4, opaque = false)
        val large = noise(140, 90, 5, opaque = true)
        val first = Lanczos.resample(small, 31, 29, Lanczos.Window.of(31, 29), 12, 40)
        // Grows every pooled buffer, then fills it with other rows.
        Lanczos.resample(large, 140, 90, Lanczos.Window.of(140, 90), 300, 10, linear = true)
        Lanczos.resample(large, 140, 90, Lanczos.Window.of(140, 90), 9, 70)
        assertArrayEquals(first, Lanczos.resample(small, 31, 29, Lanczos.Window.of(31, 29), 12, 40))
    }

    @Test
    fun cancelingStopsEveryThread() {
        val src = noise(200, 300, 6, opaque = true)
        for (threads in listOf(1, 4)) {
            val polls = AtomicInteger()
            val rows = AtomicInteger()
            assertThrows(CancellationException::class.java) {
                Lanczos.resample(
                    200, 300, { y, row -> System.arraycopy(src, y * 200, row, 0, 200) }, Lanczos.Window.of(200, 300),
                    100, 150, false, { _, n, _ -> rows.addAndGet(n) }, threads
                ) { polls.incrementAndGet() > 10 }
            }
            // Only rows started before the mark was set are written.
            assertTrue("$threads threads: ${rows.get()} rows", rows.get() <= 10)
        }
    }

    @Test
    fun cancellationMarksOnlyTheThreadDuringTheCall() {
        assertTrue(!Cancellation.current()())
        Cancellation.during({ true }) {
            assertTrue(Cancellation.current()())
            Cancellation.during({ false }) { assertTrue(!Cancellation.current()()) }
            assertTrue(Cancellation.current()())
            var other = true
            Thread { other = Cancellation.current()() }.apply { start(); join() }
            assertTrue(!other)
        }
        assertTrue(!Cancellation.current()())
    }

    @Test
    fun shrinkingWidensTheKernel() {
        val enlarge = Lanczos.axis(100, 0.0, 100.0, 300)
        val shrink = Lanczos.axis(1000, 0.0, 1000.0, 100)
        assertEquals(6, enlarge.count[150])
        // 10:1 needs 3 * 10 source pixels each side of the center.
        assertEquals(60, shrink.count[50])
        for (axis in listOf(enlarge, shrink)) {
            for (i in axis.start.indices) {
                val sum = (0 until axis.count[i]).sumOf { axis.weights[i * axis.stride + it].toDouble() }
                assertEquals(1.0, sum, 1e-5)
            }
        }
    }
}
