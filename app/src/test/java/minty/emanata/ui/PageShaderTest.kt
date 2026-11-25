package minty.emanata.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import minty.emanata.reader.Adjustments
import minty.emanata.reader.Filters
import minty.emanata.reader.Look
import minty.emanata.reader.Rcas
import minty.emanata.reader.Resampler
import minty.emanata.reader.ScaleFilter
import minty.emanata.reader.Scaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * [PageShader]'s AGSL run by the host's software Skia: every filter, with and without
 * anti-ringing, against the CPU resampler ([Resampler]), and the color stage against
 * [Look.apply].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageShaderTest {

    private fun shader(
        kernel: PageShader.Kernel = PageShader.Kernel.FILTERED,
        colored: Boolean = false,
        filter: ScaleFilter = ScaleFilter.LANCZOS3,
        antiRing: Boolean = false,
        sharpen: Boolean = false
    ) = PageShader(kernel, colored, filter, antiRing, sharpen, HOST_DIALECT)

    /** [src] ([w] x [h]) drawn through [shader] with [look], scaled by [scale] into an [outW] x [outH] bitmap. */
    private fun shaded(
        shader: PageShader,
        src: IntArray,
        w: Int,
        h: Int,
        scale: Float,
        outW: Int,
        outH: Int,
        look: Look = Look.NONE,
        ring: Float = 0f,
        amount: Float = 0f
    ): IntArray {
        val bitmap = Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // The software canvas refuses runtime shaders unless told otherwise.
        Canvas::class.java.getMethod("setHwFeaturesInSwModeEnabled", Boolean::class.javaPrimitiveType).invoke(canvas, true)
        canvas.scale(scale, scale)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shader.paint(bitmap, null, 255, look, ring, amount, 1f / scale, 1f / scale))
        return IntArray(outW * outH).also { out.getPixels(it, 0, outW, 0, 0, outW, outH) }
    }

    /** The CPU resampler's enlargement by [scale] through [scaling], cut to [outW] x [outH]. */
    private fun expected(src: IntArray, w: Int, h: Int, scale: Float, outW: Int, outH: Int, scaling: Scaling = Scaling.PLAIN): IntArray {
        val cpuW = (w * scale).toInt()
        val cpu = Resampler.resample(src, w, h, Resampler.Window.of(w, h), cpuW, (h * scale).toInt(), scaling = scaling, threads = 1)
        return IntArray(outW * outH) { o -> cpu[(o / outW) * cpuW + o % outW] }
    }

    private fun maxDiff(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) for (shift in 0..24 step 8) {
            worst = max(worst, abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
        }
        return worst
    }

    private fun noise(w: Int, h: Int, seed: Int): IntArray {
        val random = Random(seed)
        return IntArray(w * h) { (0xFF shl 24) or random.nextInt(0x1000000) }
    }

    @Test
    fun enlargingMatchesTheCpuForEveryFilterAndStrength() {
        // Sides that every scale maps to whole pixels.
        val w = 16
        val h = 12
        val src = noise(w, h, 1)
        for (filter in ScaleFilter.entries) {
            for (ring in listOf(0, 50, 100)) {
                val shader = shader(filter = filter, antiRing = ring > 0)
                for (scale in listOf(2.5f, 3f, 1.75f)) {
                    val outW = (w * scale).toInt()
                    val outH = (h * scale).toInt()
                    val got = shaded(shader, src, w, h, scale, outW, outH, ring = ring / 100f)
                    val diff = maxDiff(expected(src, w, h, scale, outW, outH, Scaling(filter, ring, 0)), got)
                    assertTrue("$filter at $ring %, scale $scale differs by $diff", diff <= 2)
                }
            }
        }
    }

    /** Each pixel of [src] through [Rcas.pixel] with its neighbors in [src], edges repeating. */
    private fun sharpenedCpu(src: IntArray, w: Int, h: Int, amount: Float): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val at = { xx: Int, yy: Int -> src[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)] }
        Rcas.pixel(at(x, y - 1), at(x - 1, y), at(x, y), at(x + 1, y), at(x, y + 1), amount)
    }

    /** The largest channel difference between [a] and [b] away from their outermost pixels, [outW] wide. */
    private fun innerDiff(a: IntArray, b: IntArray, outW: Int): Int {
        val outH = a.size / outW
        var worst = 0
        for (y in 1 until outH - 1) for (x in 1 until outW - 1) {
            val i = y * outW + x
            for (shift in 0..24 step 8) worst = max(worst, abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
        }
        return worst
    }

    @Test
    fun sharpenedEnlargingMatchesTheCpuSharpeningItsResample() {
        val w = 16
        val h = 12
        val src = noise(w, h, 3)
        for (filter in ScaleFilter.entries) {
            for (ring in listOf(0, 100)) {
                val shader = shader(filter = filter, antiRing = ring > 0, sharpen = true)
                for (scale in listOf(2.5f, 3f, 1.75f)) {
                    val outW = (w * scale).toInt()
                    val outH = (h * scale).toInt()
                    for (amount in listOf(0.5f, 1f)) {
                        val got = shaded(shader, src, w, h, scale, outW, outH, ring = ring / 100f, amount = amount)
                        val cpu = expected(src, w, h, scale, outW, outH, Scaling(filter, ring, 0))
                        val diff = innerDiff(sharpenedCpu(cpu, outW, outH, amount), got, outW)
                        assertTrue("$filter at $ring %, scale $scale, amount $amount differs by $diff", diff <= 3)
                        // Sharpening shows: the result is not the plain enlargement.
                        assertTrue(innerDiff(cpu, got, outW) > 3)
                    }
                }
            }
        }
    }

    @Test
    fun sharpeningAtZeroIsThePlainEnlargement() {
        val w = 16
        val h = 12
        val src = noise(w, h, 4)
        for (ring in listOf(0, 100)) {
            val plain = shaded(shader(antiRing = ring > 0), src, w, h, 2.5f, 40, 30, ring = ring / 100f)
            val zero = shaded(shader(antiRing = ring > 0, sharpen = true), src, w, h, 2.5f, 40, 30, ring = ring / 100f, amount = 0f)
            assertTrue(maxDiff(plain, zero) <= 1)
        }
    }

    @Test
    fun withoutSharpeningTheShaderHasNoSharpeningCode() {
        val plain = PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.LANCZOS3)
        assertTrue(!plain.contains("rcas") && !plain.contains("amount"))
        // Lanczos3 sharpened reads an 8 x 8 window; only enlarging draws sharpen.
        val sharp = PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.LANCZOS3, sharpen = true)
        assertTrue(sharp.contains("rcas(") && sharp.contains("j < 8") && sharp.contains("i < 8"))
        assertTrue(!PageShader.source(PageShader.Kernel.LINEAR, false, sharpen = true).contains("rcas"))
        assertTrue(!PageShader.source(PageShader.Kernel.NEAREST, true, sharpen = true).contains("rcas"))
    }

    @Test
    fun clampRangeIsTheTwoNearestPixelsDownToHalfSize() {
        // The shader clamps to taps r - 1 and r: floor(p - 0.5) and the next, at any scale; the CPU
        // takes the same range from 0.5x up. Only the range is compared: the GPU never draws a shrink.
        val source = PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.LANCZOS3, antiRing = true)
        assertTrue(source.contains("i == 2") && source.contains("i == 3") && source.contains("base - 2.0 + 0.5"))
        for (scale in listOf(0.5, 0.6, 0.75, 0.99, 1.0, 1.75, 4.0)) {
            for (step in 0 until 40) {
                val center = 3.0 + step / 7.0
                val base = kotlin.math.floor(center - 0.5).toInt()
                assertEquals("$scale at $center", base to base + 1, Resampler.ringRange(center, scale))
            }
        }
    }

    @Test
    fun withoutAntiRingingTheShaderHasNoClamp() {
        assertTrue(!PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.LANCZOS3, antiRing = false).contains("ring"))
        assertTrue(PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.LANCZOS3, antiRing = true).contains("ring * (clamp("))
        // Lanczos6 weighs 12 x 12 pixels; Hermite 2 x 2.
        val lanczos6 = PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.LANCZOS6)
        assertTrue(lanczos6.contains("j < 12") && lanczos6.contains("i < 12"))
        val hermite = PageShader.source(PageShader.Kernel.FILTERED, false, ScaleFilter.HERMITE)
        assertTrue(hermite.contains("j < 2") && hermite.contains("i < 2"))
    }

    @Test
    fun hardEdgeDoesNotRingAtFullStrength() {
        val w = 8
        val h = 4
        val src = IntArray(w * h) { if (it % w < 4) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        val got = shaded(shader(antiRing = true), src, w, h, 4f, 32, 16, ring = 1f)
        val row = (0 until 32).map { got[8 * 32 + it] and 0xFF }
        // Raw Lanczos3 overshoots past both levels beside the edge; clamped, it stays between them.
        assertTrue(row.all { it in 0x20..0xE0 })
        assertEquals(0x20, row.first())
        assertEquals(0xE0, row.last())
        assertTrue(row.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun clearedShaderLetsGoOfTheBitmapAndDrawsTheNextOne() {
        val shader = shader()
        val first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        shader.paint(first, null, 255)
        assertTrue(shader.held === first)
        shader.clear()
        assertEquals(null, shader.held)

        val src = noise(6, 5, 2)
        val got = shaded(shader, src, 6, 5, 2f, 12, 10)
        assertTrue(maxDiff(expected(src, 6, 5, 2f, 12, 10), got) <= 2)
    }

    private val looks = listOf(
        Look.of(Filters.INVERT, Adjustments.NEUTRAL),
        Look.of(Filters.NONE, Adjustments(brightness = 35)),
        Look.of(Filters.NONE, Adjustments(brightness = -60)),
        Look.of(Filters.NONE, Adjustments(contrast = 70)),
        Look.of(Filters.NONE, Adjustments(contrast = -40)),
        Look.of(Filters.NONE, Adjustments(saturation = 100)),
        Look.of(Filters.NONE, Adjustments(saturation = -100)),
        Look.of(Filters.SEPIA, Adjustments(10, 20, 30)),
        Look.of(Filters.INVERT, Adjustments(-20, 100, 60)),
        Look.of(Filters.NONE, Adjustments(100, -100, -100))
    )

    @Test
    fun colorStageMatchesTheReferenceAtOneToOne() {
        val w = 32
        val h = 16
        val src = noise(w, h, 3)
        val shader = shader(PageShader.Kernel.NEAREST, colored = true)
        for (look in looks) {
            val got = shaded(shader, src, w, h, 1f, w, h, look)
            val diff = maxDiff(IntArray(src.size) { look.apply(src[it]) }, got)
            assertTrue("${look.adjustments} invert ${look.invert} differs by $diff", diff <= 2)
        }
    }

    @Test
    fun colorStageUnpremultipliesTranslucentPixels() {
        val w = 32
        val h = 16
        val alpha = 0x80
        // Premultiplied channels 0..128, stored exactly: at alpha 128 each round-trips through its unpremultiplied byte.
        val random = Random(5)
        val premul = IntArray(w * h * 3) { random.nextInt(alpha + 1) }
        fun unpremul(p: Int) = Math.round(p * 255f / alpha).coerceAtMost(255)
        fun premul(c: Int) = Math.round(c * alpha / 255f)
        val src = IntArray(w * h) { i ->
            (alpha shl 24) or (unpremul(premul[i * 3]) shl 16) or (unpremul(premul[i * 3 + 1]) shl 8) or unpremul(premul[i * 3 + 2])
        }
        val shader = shader(PageShader.Kernel.NEAREST, colored = true)
        for (look in looks) {
            val got = shaded(shader, src, w, h, 1f, w, h, look)
            // Compared premultiplied, as stored, so the 7-bit rounding at half alpha is not amplified.
            var worst = 0
            for (i in got.indices) {
                assertEquals(alpha, got[i] ushr 24)
                val out = look.apply(premul[i * 3] / alpha.toFloat(), premul[i * 3 + 1] / alpha.toFloat(), premul[i * 3 + 2] / alpha.toFloat())
                for (c in 0..2) {
                    val stored = premul((got[i] shr (16 - 8 * c)) and 0xFF)
                    worst = max(worst, abs(Math.round(out[c] * alpha) - stored))
                }
            }
            assertTrue("${look.adjustments} invert ${look.invert} differs by $worst", worst <= 1)
        }
    }

    @Test
    fun colorStageLeavesTransparentPixelsTransparent() {
        val random = Random(6)
        val src = IntArray(16 * 8) { random.nextInt(0x1000000) }
        val shader = shader(PageShader.Kernel.NEAREST, colored = true)
        for (look in looks) {
            assertTrue(shaded(shader, src, 16, 8, 1f, 16, 8, look).all { it == 0 })
        }
    }

    @Test
    fun colorStageFollowsTheLanczosResult() {
        val w = 12
        val h = 8
        val src = noise(w, h, 4)
        val plain = shaded(shader(), src, w, h, 2.5f, 30, 20)
        val shader = shader(PageShader.Kernel.FILTERED, colored = true)
        // Near-threshold contrast flips on any rounding; the 1:1 test covers it.
        for (look in looks.filter { it.adjustments.contrastFactor < 4f }) {
            val got = shaded(shader, src, w, h, 2.5f, 30, 20, look)
            val diff = maxDiff(IntArray(plain.size) { look.apply(plain[it]) }, got)
            // The reference starts from the plain result rounded to 8 bits, an error that raised
            // contrast or saturation amplifies, most near black where sRGB is steepest.
            val a = look.adjustments
            val limit = if (a.contrastFactor > 1f || a.saturationFactor > 1f) 12 else 3
            assertTrue("${look.adjustments} invert ${look.invert} differs by $diff", diff <= limit)
        }
    }

    @Test
    fun colorStageAfterBilinearShrink() {
        val w = 16
        val h = 16
        val flat = IntArray(w * h) { 0xFF8040C0.toInt() }
        val look = Look.of(Filters.INVERT, Adjustments(brightness = 20))
        val got = shaded(shader(PageShader.Kernel.LINEAR, colored = true), flat, w, h, 0.5f, 8, 8, look)
        assertTrue(maxDiff(IntArray(got.size) { look.apply(flat[0]) }, got) <= 2)
    }

    companion object {
        /** The host's Skia predates `shader.eval()` and takes the older `sample(shader, p)`. */
        val HOST_DIALECT: (String) -> String = { it.replace("image.eval(", "sample(image, ") }
    }
}
