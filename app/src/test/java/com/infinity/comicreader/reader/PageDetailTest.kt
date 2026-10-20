package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageDetailTest {

    private val w = 60
    private val h = 40

    /** Opaque synthetic pattern: gradients plus a hard edge, so resampling has work to do. */
    private val pixels = IntArray(w * h) {
        val x = it % w
        val y = it / w
        val edge = if (x in 20..29) 255 else 0
        (0xFF shl 24) or ((x * 4) shl 16) or ((y * 6) shl 8) or edge
    }

    private fun detail() = BitmapDetail(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888))

    private fun pixelsOf(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun regionPlansScaleIntoTheSpan() {
        val plan = Region(0.25, 0.5, 0.5, 0.25).plan(30, 20, 10.0, 20.0, 200.0, 400.0)
        assertEquals(Plan(30, 20, 60.0, 220.0, 100.0, 100.0), plan)
    }

    @Test
    fun regionRendersAsTheResamplerDoes() {
        val region = Region(0.1, 0.2, 0.45, 0.5)
        val out = detail().render(region, 50, 37, false)
        val expected = Lanczos.resample(pixels, w, h, Lanczos.Window(0.1 * w, 0.2 * h, 0.45 * w, 0.5 * h), 50, 37)
        assertEquals(50, out.width)
        assertEquals(37, out.height)
        assertArrayEquals(expected, pixelsOf(out))
    }

    @Test
    fun wholePixelRegionAtScaleOneIsACopy() {
        val out = detail().render(Region(10.0 / w, 5.0 / h, 20.0 / w, 30.0 / h), 20, 30, false)
        val got = pixelsOf(out)
        for (y in 0 until 30) for (x in 0 until 20) assertEquals(pixels[(y + 5) * w + x + 10], got[y * 20 + x])
    }

    @Test
    fun regionPastTheEdgeRepeatsIt() {
        // Half a pixel beyond the left edge at scale 1 resamples, from repeated edge pixels.
        val out = detail().render(Region(-0.5 / w, 0.0, 1.0, 1.0), w, h, false)
        val expected = Lanczos.resample(pixels, w, h, Lanczos.Window(-0.5, 0.0, w.toDouble(), h.toDouble()), w, h)
        assertArrayEquals(expected, pixelsOf(out))
    }
}
