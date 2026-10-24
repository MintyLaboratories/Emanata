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

class ResamplerTest {

    // ------------------------------------------------------------------ reference

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** The Mitchell-Netravali family of cubics, from its own definition: Hermite is (0, 0), Catmull-Rom (0, 1/2). */
    private fun cubic(b: Double, c: Double, x: Double): Double {
        val a = abs(x)
        return when {
            a < 1 -> ((12 - 9 * b - 6 * c) * a * a * a + (-18 + 12 * b + 6 * c) * a * a + (6 - 2 * b)) / 6
            a < 2 -> ((-b - 6 * c) * a * a * a + (6 * b + 30 * c) * a * a + (-12 * b - 48 * c) * a + (8 * b + 24 * c)) / 6
            else -> 0.0
        }
    }

    /** Each filter's kernel in double precision, written independently of [ScaleFilter.weight]. */
    private fun kernel(filter: ScaleFilter, x: Double): Double = when (filter) {
        ScaleFilter.HERMITE -> if (abs(x) < 1) cubic(0.0, 0.0, x) else 0.0
        ScaleFilter.CATMULL_ROM -> cubic(0.0, 0.5, x)
        ScaleFilter.LANCZOS3 -> if (abs(x) < 3) sinc(x) * sinc(x / 3) else 0.0
        ScaleFilter.LANCZOS6 -> if (abs(x) < 6) sinc(x) * sinc(x / 6) else 0.0
    }

    /** Normalized taps of output [i] as (clamped source index, weight), straight from the definition. */
    private fun taps(srcLen: Int, from: Double, span: Double, dstLen: Int, i: Int, filter: ScaleFilter): List<Pair<Int, Double>> {
        val scale = dstLen / span
        val k = min(1.0, scale)
        val center = from + (i + 0.5) / scale
        val support = filter.radius / k
        val raw = (floor(center - support - 1).toInt()..ceil(center + support + 1).toInt())
            .map { j -> j.coerceIn(0, srcLen - 1) to kernel(filter, (j + 0.5 - center) * k) }
            .filter { it.second != 0.0 }
        val sum = raw.sumOf { it.second }
        return raw.map { it.first to it.second / sum }
    }

    /**
     * The source pixels output [i] clamps to: the two either side of its center enlarging,
     * those whose centers its footprint covers shrinking (the nearest when none).
     */
    private fun nearest(srcLen: Int, from: Double, span: Double, dstLen: Int, i: Int): List<Int> {
        val scale = dstLen / span
        val center = from + (i + 0.5) / scale
        val found = if (scale >= 1) {
            val below = floor(center - 0.5).toInt()
            listOf(below, below + 1)
        } else {
            (floor(center - 1 / scale).toInt()..ceil(center + 1 / scale).toInt()).filter { j ->
                j + 0.5 >= center - 0.5 / scale && j + 0.5 <= center + 0.5 / scale
            }.ifEmpty { listOf(floor(center).toInt()) }
        }
        return found.map { it.coerceIn(0, srcLen - 1) }
    }

    private fun toLinear(c: Int) = Resampler.srgbToLinear(c / 255.0)

    /** [v] blended by [strength] toward itself clamped to the range of [around]. */
    private fun antiRing(v: Double, around: List<Double>, strength: Double): Double =
        v + strength * (v.coerceIn(around.min(), around.max()) - v)

    /**
     * Separable resampling in double precision, as the definition reads: each source row
     * filtered horizontally, then each column of those vertically, each pass followed by
     * its anti-ringing when [scaling] asks for it, on premultiplied channels.
     */
    private fun reference(
        src: IntArray, srcW: Int, srcH: Int, win: Resampler.Window, dstW: Int, dstH: Int, linear: Boolean,
        scaling: Scaling = Scaling.PLAIN
    ): IntArray {
        val premul = Array(srcH) { y ->
            Array(srcW) { x ->
                val p = src[y * srcW + x]
                val a = (p ushr 24) / 255.0
                DoubleArray(4) { c ->
                    if (c == 3) a else ((p shr (16 - 8 * c)) and 0xFF).let { code -> a * (if (linear) toLinear(code) else code / 255.0) }
                }
            }
        }
        val ringX = scaling.ring(dstW >= win.width).toDouble()
        val ringY = scaling.ring(dstH >= win.height).toDouble()
        val rows = Array(srcH) { sy ->
            Array(dstW) { x ->
                val near = nearest(srcW, win.x, win.width, dstW, x)
                DoubleArray(4) { c ->
                    val v = taps(srcW, win.x, win.width, dstW, x, scaling.filter).sumOf { (sx, w) -> w * premul[sy][sx][c] }
                    if (ringX > 0) antiRing(v, near.map { premul[sy][it][c] }, ringX) else v
                }
            }
        }
        return IntArray(dstW * dstH) { o ->
            val x = o % dstW
            val y = o / dstW
            val near = nearest(srcH, win.y, win.height, dstH, y)
            val acc = DoubleArray(4) { c ->
                val v = taps(srcH, win.y, win.height, dstH, y, scaling.filter).sumOf { (sy, w) -> w * rows[sy][x][c] }
                if (ringY > 0) antiRing(v, near.map { rows[it][x][c] }, ringY) else v
            }
            val a = acc[3].coerceIn(0.0, 1.0)
            // A pixel too faint to keep any alpha keeps no color either.
            if ((a * 255).roundToInt() <= 0) return@IntArray 0
            var pixel = (a * 255).roundToInt() shl 24
            for (c in 0..2) {
                val v = (acc[c] / a).coerceIn(0.0, 1.0)
                val code = if (linear) Resampler.linearToSrgb(v) * 255 else v * 255
                pixel = pixel or (code.roundToInt().coerceIn(0, 255) shl (16 - 8 * c))
            }
            pixel
        }
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

    private fun checkAgainstReference(
        srcW: Int, srcH: Int, win: Resampler.Window, dstW: Int, dstH: Int, opaque: Boolean, linear: Boolean,
        scaling: Scaling = Scaling.PLAIN
    ) {
        val src = noise(srcW, srcH, srcW * 31 + dstW, opaque)
        val ours = Resampler.resample(src, srcW, srcH, win, dstW, dstH, linear, scaling)
        val ref = reference(src, srcW, srcH, win, dstW, dstH, linear, scaling)
        val diff = maxChannelDiff(ours, ref)
        assertTrue("max channel difference $diff for $srcW x $srcH -> $dstW x $dstH, $scaling", diff <= 1)
    }

    // ------------------------------------------------------------------ against the reference

    @Test
    fun downscaleMatchesReference() {
        checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 17, 13, opaque = true, linear = false)
        checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 17, 13, opaque = false, linear = false)
    }

    @Test
    fun upscaleMatchesReference() {
        checkAgainstReference(13, 11, Resampler.Window.of(13, 11), 40, 29, opaque = true, linear = false)
        checkAgainstReference(13, 11, Resampler.Window.of(13, 11), 40, 29, opaque = false, linear = false)
    }

    @Test
    fun mixedAxesMatchReference() {
        // Shrinks one axis while enlarging the other.
        checkAgainstReference(40, 12, Resampler.Window.of(40, 12), 15, 31, opaque = false, linear = false)
    }

    @Test
    fun fractionalWindowMatchesReference() {
        // A centered cover crop: part of the source, at sub-pixel offsets.
        checkAgainstReference(50, 70, Resampler.Window(0.0, 11.25, 50.0, 47.5), 20, 19, opaque = true, linear = false)
    }

    @Test
    fun largeShrinkMatchesReference() {
        // About 13:1 and 7:1, where each output reads 78 and 42 source pixels per axis.
        checkAgainstReference(300, 160, Resampler.Window.of(300, 160), 23, 23, opaque = false, linear = false)
        checkAgainstReference(300, 160, Resampler.Window.of(300, 160), 23, 23, opaque = true, linear = true)
    }

    @Test
    fun linearLightMatchesReference() {
        checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 17, 13, opaque = true, linear = true)
        checkAgainstReference(13, 11, Resampler.Window.of(13, 11), 40, 29, opaque = false, linear = true)
    }

    // ------------------------------------------------------------------ properties

    @Test
    fun constantImageStaysConstant() {
        for (color in intArrayOf(0xFF3C82C8.toInt(), 0x80402010.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            val src = IntArray(23 * 17) { color }
            for ((w, h) in listOf(7 to 5, 23 to 17, 60 to 41)) {
                for (linear in listOf(false, true)) {
                    val out = Resampler.resample(src, 23, 17, Resampler.Window.of(23, 17), w, h, linear)
                    assertTrue(out.all { maxChannelDiff(intArrayOf(it), intArrayOf(color)) <= if (linear) 1 else 0 })
                }
            }
        }
    }

    @Test
    fun scaleOneIsIdentity() {
        val src = noise(33, 21, 7, opaque = false)
        val out = Resampler.resample(src, 33, 21, Resampler.Window.of(33, 21), 33, 21)
        // Premultiplied, a transparent pixel keeps no color, and a translucent one may move by a code.
        val visible = IntArray(src.size) { if (src[it] ushr 24 == 0) 0 else src[it] }
        assertTrue(maxChannelDiff(visible, out) <= 1)
        val opaque = noise(33, 21, 8, opaque = true)
        assertArrayEquals(opaque, Resampler.resample(opaque, 33, 21, Resampler.Window.of(33, 21), 33, 21))
    }

    @Test
    fun integerOffsetAtScaleOneIsACrop() {
        val src = noise(30, 20, 9, opaque = true)
        val out = Resampler.resample(src, 30, 20, Resampler.Window(4.0, 3.0, 10.0, 12.0), 10, 12)
        val expected = IntArray(10 * 12) { src[(3 + it / 10) * 30 + 4 + it % 10] }
        assertArrayEquals(expected, out)
    }

    @Test
    fun everyFilterMatchesReference() {
        for (filter in ScaleFilter.entries) {
            val plain = Scaling(filter, 0, 0)
            checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 17, 13, opaque = false, linear = false, scaling = plain)
            checkAgainstReference(13, 11, Resampler.Window.of(13, 11), 40, 29, opaque = false, linear = false, scaling = plain)
            checkAgainstReference(50, 70, Resampler.Window(0.0, 11.25, 50.0, 47.5), 20, 19, opaque = true, linear = true, scaling = plain)
        }
    }

    @Test
    fun antiRingingMatchesReference() {
        for (filter in ScaleFilter.entries) {
            for ((up, down) in listOf(100 to 100, 30 to 50, 0 to 75)) {
                val scaling = Scaling(filter, up, down)
                checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 17, 13, opaque = false, linear = false, scaling = scaling)
                checkAgainstReference(13, 11, Resampler.Window.of(13, 11), 40, 29, opaque = true, linear = false, scaling = scaling)
                // One axis shrinks, the other enlarges: each takes its own strength.
                checkAgainstReference(40, 12, Resampler.Window.of(40, 12), 15, 31, opaque = false, linear = true, scaling = scaling)
                checkAgainstReference(300, 160, Resampler.Window.of(300, 160), 23, 23, opaque = true, linear = false, scaling = scaling)
                // Shrunk mildly, one source pixel or two lie under each output pixel.
                checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 47, 37, opaque = true, linear = false, scaling = scaling)
                checkAgainstReference(61, 47, Resampler.Window.of(61, 47), 47, 37, opaque = false, linear = false, scaling = scaling)
            }
        }
    }

    @Test
    fun antiRingingMatchesReferenceWherePartOfTheRowsAreOpaque() {
        // Rows of noise, every third one translucent in places: opaque rows skip their alpha.
        val r = Random(5)
        val src = IntArray(61 * 47) { i ->
            val alpha = if ((i / 61) % 3 == 0 && r.nextInt(4) == 0) r.nextInt(256) else 255
            (alpha shl 24) or r.nextInt(1 shl 24)
        }
        for (filter in ScaleFilter.entries) {
            for ((w, h) in listOf(17 to 13, 47 to 37, 90 to 70)) {
                val scaling = Scaling(filter, 60, 60)
                val ours = Resampler.resample(src, 61, 47, Resampler.Window.of(61, 47), w, h, false, scaling)
                val diff = maxChannelDiff(ours, reference(src, 61, 47, Resampler.Window.of(61, 47), w, h, false, scaling))
                assertTrue("max channel difference $diff for $w x $h, $scaling", diff <= 1)
            }
        }
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
            val out = Resampler.resample(src, 16, 4, Resampler.Window.of(16, 4), w, h).map { it and 0xFF }
            // Lanczos3 overshoots a step by under 13% of its height on either side.
            assertTrue("$w x $h: ${out.max()}", out.max() <= 192 + 17)
            assertTrue("$w x $h: ${out.min()}", out.min() >= 64 - 17)
        }
        // Enlarging does ring a little: this is Lanczos, not a smoothing filter.
        val up = Resampler.resample(src, 16, 4, Resampler.Window.of(16, 4), 64, 4).map { it and 0xFF }
        assertTrue(up.max() > 192 && up.min() < 64)
    }

    @Test
    fun fullAntiRingingKeepsEdgesWithinTheirLevels() {
        val src = edge(16, 4, 64, 192)
        for (filter in ScaleFilter.entries) {
            val full = Scaling(filter, 100, 100)
            for ((w, h) in listOf(64 to 4, 9 to 4, 5 to 2)) {
                val out = Resampler.resample(src, 16, 4, Resampler.Window.of(16, 4), w, h, scaling = full).map { it and 0xFF }
                assertTrue("$filter $w x $h: ${out.min()}..${out.max()}", out.max() <= 192 && out.min() >= 64)
            }
        }
        // Half strength halves the overshoot.
        val raw = Resampler.resample(src, 16, 4, Resampler.Window.of(16, 4), 64, 4, scaling = Scaling(ScaleFilter.LANCZOS6, 0, 0)).map { it and 0xFF }
        val half = Resampler.resample(src, 16, 4, Resampler.Window.of(16, 4), 64, 4, scaling = Scaling(ScaleFilter.LANCZOS6, 50, 0)).map { it and 0xFF }
        assertEquals((raw.max() - 192) / 2.0, (half.max() - 192).toDouble(), 1.0)
    }

    @Test
    fun noAntiRingingLeavesTheAxisWithoutClampRanges() {
        assertEquals(0, Resampler.axis(100, 0.0, 100.0, 300, Scaling.DEFAULT).ringLo.size)
        val shrink = Resampler.axis(1000, 0.0, 1000.0, 100, Scaling.DEFAULT)
        assertEquals(0.5f, shrink.ring)
        // 10:1: the ten source pixels under each output.
        assertEquals(500, shrink.ringLo[50])
        assertEquals(509, shrink.ringHi[50])
        val up = Resampler.axis(100, 0.0, 100.0, 300, Scaling(upRing = 100))
        assertEquals(49, up.ringLo[150])
        assertEquals(50, up.ringHi[150])
    }

    @Test
    fun hermiteEnlargingNeverOvershoots() {
        val src = edge(16, 4, 64, 192)
        val out = Resampler.resample(src, 16, 4, Resampler.Window.of(16, 4), 64, 4, scaling = Scaling(ScaleFilter.HERMITE, 0, 0)).map { it and 0xFF }
        assertTrue(out.max() <= 192 && out.min() >= 64)
    }

    @Test
    fun bandsMatchOneThread() {
        val src = noise(97, 89, 3, opaque = false)
        val one = Resampler.resample(src, 97, 89, Resampler.Window.of(97, 89), 41, 53, threads = 1)
        val many = Resampler.resample(src, 97, 89, Resampler.Window.of(97, 89), 41, 53, threads = 8)
        assertArrayEquals(one, many)
    }

    @Test
    fun reusedBuffersGiveTheSameResult() {
        val small = noise(31, 29, 4, opaque = false)
        val large = noise(140, 90, 5, opaque = true)
        val first = Resampler.resample(small, 31, 29, Resampler.Window.of(31, 29), 12, 40)
        // Grows every pooled buffer, then fills it with other rows.
        Resampler.resample(large, 140, 90, Resampler.Window.of(140, 90), 300, 10, linear = true)
        Resampler.resample(large, 140, 90, Resampler.Window.of(140, 90), 9, 70)
        assertArrayEquals(first, Resampler.resample(small, 31, 29, Resampler.Window.of(31, 29), 12, 40))
    }

    @Test
    fun cancelingStopsEveryThread() {
        val src = noise(200, 300, 6, opaque = true)
        for (threads in listOf(1, 4)) {
            val polls = AtomicInteger()
            val rows = AtomicInteger()
            assertThrows(CancellationException::class.java) {
                Resampler.resample(
                    200, 300, { y, row -> System.arraycopy(src, y * 200, row, 0, 200) }, Resampler.Window.of(200, 300),
                    100, 150, false, { _, n, _ -> rows.addAndGet(n) }, threads = threads
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
    fun antiRingingBandsMatchOneThread() {
        val src = noise(97, 89, 13, opaque = false)
        val scaling = Scaling(ScaleFilter.LANCZOS6, 40, 60)
        val one = Resampler.resample(src, 97, 89, Resampler.Window.of(97, 89), 41, 153, scaling = scaling, threads = 1)
        val many = Resampler.resample(src, 97, 89, Resampler.Window.of(97, 89), 41, 153, scaling = scaling, threads = 8)
        assertArrayEquals(one, many)
    }

    @Test
    fun shrinkingWidensTheKernel() {
        val enlarge = Resampler.axis(100, 0.0, 100.0, 300)
        val shrink = Resampler.axis(1000, 0.0, 1000.0, 100)
        assertEquals(6, enlarge.count[150])
        // 10:1 needs 3 * 10 source pixels each side of the center.
        assertEquals(60, shrink.count[50])
        assertEquals(120, Resampler.axis(1000, 0.0, 1000.0, 100, Scaling(ScaleFilter.LANCZOS6, 0, 0)).count[50])
        assertEquals(20, Resampler.axis(1000, 0.0, 1000.0, 100, Scaling(ScaleFilter.HERMITE, 0, 0)).count[50])
        for (axis in listOf(enlarge, shrink)) {
            for (i in axis.start.indices) {
                val sum = (0 until axis.count[i]).sumOf { axis.weights[i * axis.stride + it].toDouble() }
                assertEquals(1.0, sum, 1e-5)
            }
        }
    }
}
