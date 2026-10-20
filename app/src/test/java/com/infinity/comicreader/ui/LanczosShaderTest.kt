package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import com.infinity.comicreader.reader.Lanczos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/**
 * [LanczosShader]'s AGSL against the CPU resampler ([Lanczos]) plus the 2 x 2 clamp,
 * run by the host's software Skia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanczosShaderTest {

    /** The host's Skia predates `shader.eval()` and takes the older `sample(shader, p)`. */
    private fun shader() = LanczosShader(LanczosShader.SOURCE.replace("image.eval(", "sample(image, "))

    /** [src] ([w] x [h]) drawn through the shader scaled by [scale] into an [outW] x [outH] bitmap. */
    private fun shaded(shader: LanczosShader, src: IntArray, w: Int, h: Int, scale: Float, outW: Int, outH: Int): IntArray {
        val bitmap = Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // The software canvas refuses runtime shaders unless told otherwise.
        Canvas::class.java.getMethod("setHwFeaturesInSwModeEnabled", Boolean::class.javaPrimitiveType).invoke(canvas, true)
        canvas.scale(scale, scale)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shader.paint(bitmap, null, 255))
        return IntArray(outW * outH).also { out.getPixels(it, 0, outW, 0, 0, outW, outH) }
    }

    /** The CPU resampler's enlargement, each channel clamped to the 2 x 2 source pixels nearest the output's center. */
    private fun expected(src: IntArray, w: Int, h: Int, scale: Float, outW: Int, outH: Int): IntArray {
        val cpu = Lanczos.resample(src, w, h, Lanczos.Window.of(w, h), (w * scale).toInt(), (h * scale).toInt(), threads = 1)
        val cpuW = (w * scale).toInt()
        return IntArray(outW * outH) { o ->
            val x = o % outW
            val y = o / outW
            val bx = floor((x + 0.5) / scale - 0.5).toInt()
            val by = floor((y + 0.5) / scale - 0.5).toInt()
            val near = listOf(bx to by, bx + 1 to by, bx to by + 1, bx + 1 to by + 1)
                .map { (sx, sy) -> src[sy.coerceIn(0, h - 1) * w + sx.coerceIn(0, w - 1)] }
            var pixel = 0xFF shl 24
            for (shift in 0..16 step 8) {
                val c = (cpu[y * cpuW + x] shr shift) and 0xFF
                val lo = near.minOf { (it shr shift) and 0xFF }
                val hi = near.maxOf { (it shr shift) and 0xFF }
                pixel = pixel or (c.coerceIn(lo, hi) shl shift)
            }
            pixel
        }
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
    fun enlargingMatchesTheCpuKernelWithTheClamp() {
        val w = 12
        val h = 8
        val src = noise(w, h, 1)
        for (scale in listOf(2.5f, 3f, 1.75f)) {
            val outW = (w * scale).toInt()
            val outH = (h * scale).toInt()
            val got = shaded(shader(), src, w, h, scale, outW, outH)
            val diff = maxDiff(expected(src, w, h, scale, outW, outH), got)
            assertTrue("scale $scale differs by $diff", diff <= 2)
        }
    }

    @Test
    fun hardEdgeDoesNotRing() {
        val w = 8
        val h = 4
        val src = IntArray(w * h) { if (it % w < 4) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        val got = shaded(shader(), src, w, h, 4f, 32, 16)
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
}
