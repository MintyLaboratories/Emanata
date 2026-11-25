package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

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
        val expected = Resampler.resample(pixels, w, h, Resampler.Window(0.1 * w, 0.2 * h, 0.45 * w, 0.5 * h), 50, 37)
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
        val expected = Resampler.resample(pixels, w, h, Resampler.Window(-0.5, 0.0, w.toDouble(), h.toDouble()), w, h)
        assertArrayEquals(expected, pixelsOf(out))
    }

    @get:Rule
    val temp = TemporaryFolder()

    /** A page with white borders around sharp-edged content: auto-crop takes them off. */
    private fun borderedPage(): File {
        val pw = 240
        val ph = 320
        val page = IntArray(pw * ph) {
            val x = it % pw
            val y = it / pw
            if (x < 20 || y < 30 || x >= pw - 16 || y >= ph - 24) {
                Color.WHITE
            } else {
                val check = if ((x / 3 + y / 3) % 2 == 0) 0 else 120
                Color.rgb(check, (x * 150) / pw, (y * 150) / ph)
            }
        }
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(page, pw, ph, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return temp.newFile("page.png").apply { writeBytes(out.toByteArray()) }
    }

    // File decodes run on API 27, where the host decodes them (through BitmapFactory).
    @Test
    @Config(sdk = [27])
    fun autoCroppedDetailLinesUpWithThePageShown() {
        val source = ImageSource(borderedPage())
        val target = Target(100, 150, autoCrop = true)
        val shown = source.loadPage(0, target)
        val whole = source.loadDetail(0, target, Long.MAX_VALUE).render(Region(0.0, 0.0, 1.0, 1.0), shown.width, shown.height, false)
        assertEquals(shown.width, whole.width)
        assertEquals(shown.height, whole.height)
        // Only within the kernel's reach of an edge, where the shown page read the border, may they differ.
        val a = pixelsOf(shown)
        val b = pixelsOf(whole)
        val margin = 4
        for (y in margin until shown.height - margin) for (x in margin until shown.width - margin) {
            assertEquals("($x, $y)", a[y * shown.width + x], b[y * shown.width + x])
        }
    }
}
