package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Regions of a page read a rectangle at a time come out as they do from the page decoded
 * whole. Robolectric has no region decoder, so a reader cropping a whole bitmap stands in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegionDetailTest {

    /** Reads crops of [image], unsampled; counts the pixels it reads. */
    private class CroppingReader(private val image: Bitmap) : RegionReader {
        var read = 0L
        var closed = false
        override val width: Int get() = image.width
        override val height: Int get() = image.height

        override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap {
            assertEquals(1, sample)
            read += (right - left).toLong() * (bottom - top)
            return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        }

        override fun close() {
            closed = true
        }
    }

    /** A tall strip of hard-edged detail, so any tap off by a pixel shows. */
    private fun strip(w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = ((x * 7 + y * 13) xor (x * y)) and 0xFF
            (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or ((x + y) and 0xFF)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun maxDifference(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var most = 0
        for (i in pa.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                most = maxOf(most, abs(((pa[i] shr shift) and 0xFF) - ((pb[i] shr shift) and 0xFF)))
            }
        }
        return most
    }

    @Test
    fun bandsMatchTheWholePage() {
        val image = strip(200, 3000)
        val whole = BitmapDetail(image.copy(Bitmap.Config.ARGB_8888, false))
        val reader = CroppingReader(image)
        val detail = RegionDetail(reader, intArrayOf(0, 0, 200, 3000), 0)
        // Bands of a page shown 370 wide (enlarged), and one shown shrunk.
        val fh = 3000 * 370 / 200.0
        for ((y, h) in listOf(0 to 512, 1024 to 512, (fh - 300).toInt() to 300)) {
            val region = Region(0.0, y / fh, 1.0, h / fh)
            for (scaling in listOf(Scaling.DEFAULT, Scaling(ScaleFilter.LANCZOS6, 50, 50))) {
                val a = whole.render(region, 370, h, false, scaling)
                val b = detail.render(region, 370, h, false, scaling)
                assertTrue("band at $y ${scaling.filter}", maxDifference(a, b) <= 1)
            }
        }
        val shrunk = Region(0.0, 0.4, 1.0, 0.1)
        assertTrue(maxDifference(whole.render(shrunk, 60, 90, true), detail.render(shrunk, 60, 90, true)) <= 1)
        // Only the rows under each band, and a few either side for the filter, were read.
        assertTrue(reader.read < 200L * 3000 * 6 / 4)
        detail.release()
        assertTrue(reader.closed)
    }

    @Test
    fun theAreaLaysOutThePage() {
        val image = strip(200, 1000)
        // The page shows only its right half.
        val detail = RegionDetail(CroppingReader(image), intArrayOf(100, 0, 200, 1000), 0)
        val region = Region(0.0, 0.25, 1.0, 0.5)
        // Cropped as a page's decode crops: the filter still reads the pixels beside the area.
        for ((w, h) in listOf(100 to 500, 250 to 1250)) {
            val plan = region.plan(w, h, 100.0, 0.0, 100.0, 1000.0)
            val expected = if (plan.isCrop()) Bitmap.createBitmap(image, plan.x.toInt(), plan.y.toInt(), w, h) else BitmapUtils.resample(image, plan, false)
            assertTrue("$w x $h", maxDifference(expected, detail.render(region, w, h, false)) <= 1)
        }
    }

    @Test
    fun aReadTakesTheSpanWithRoomForTheFilter() {
        // A band of a page 1000 wide shown 1848 wide: Lanczos3 taps 3 pixels either way.
        val plan = Plan(1848, 1024, 0.0, 554.1, 1000.0, 554.1)
        val read = RegionRead.of(plan, 1000, 25000, 3)
        assertEquals(RegionRead(0, 550, 1000, 1113, 1), read)
        // At the image's edges the read stops there.
        assertEquals(RegionRead(0, 0, 1000, 559, 1), RegionRead.of(plan.copy(y = 0.0), 1000, 25000, 3))
    }

    @Test
    fun aReadSamplesDownOnlyWhereThatLeavesTwiceTheOutput() {
        val w = 4000
        val h = 4000
        assertEquals(1, RegionRead.of(Plan(1000, 1000, 0.0, 0.0, 3000.0, 3000.0), w, h, 3).sample)
        assertEquals(2, RegionRead.of(Plan(1000, 1000, 0.0, 0.0, 4000.0, 4000.0), w, h, 3).sample)
        // A whole page 1000 x 25000 shrunk 2.7 times: too many pixels to read unsampled.
        assertEquals(2, RegionRead.of(Plan(370, 9260, 0.0, 0.0, 1000.0, 25000.0), 1000, 25000, 3).sample)
    }

    @Test
    fun theLocalPlanFollowsTheSampledRead() {
        val read = RegionRead(100, 200, 900, 1000, 2)
        val local = read.local(Plan(100, 100, 300.0, 400.0, 400.0, 400.0), 400, 400)
        assertEquals(Plan(100, 100, 100.0, 100.0, 200.0, 200.0), local)
    }

    @Test
    fun aBandedDecodeSamplesTheStandInSource() {
        val banded = Target(1848, 0, banded = true)
        assertEquals(Target.BASE_DECODE_PIXELS, Decoders.sampledFor(banded, intArrayOf(1000, 25000)).capPixels)
        // A page that fits one bitmap, or a target that is not banded, decodes as it did.
        assertEquals(banded, Decoders.sampledFor(banded, intArrayOf(1000, 1500)))
        assertEquals(Target(1848, 0), Decoders.sampledFor(Target(1848, 0), intArrayOf(1000, 25000)))
        assertEquals(banded, Decoders.sampledFor(banded, null))
    }
}
