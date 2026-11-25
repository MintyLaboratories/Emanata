package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bands read from a source that decodes whole for any part share a few reads of many rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeptRowsTest {

    private val w = 50
    private val h = 4000
    private val pixels = IntArray(w * h) { i -> (0xFF shl 24) or ((i / w) shl 8) or (i % w) }

    /** Crops of the image; counts its reads. */
    private class Counting(private val image: Bitmap) : RegionReader {
        var reads = 0
        var closed = false
        override val width = image.width
        override val height = image.height
        override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap {
            reads++
            return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        }

        override fun close() {
            closed = true
        }
    }

    private fun check(got: Bitmap, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(right - left, got.width)
        assertEquals(bottom - top, got.height)
        val actual = IntArray(got.width * got.height).also { got.getPixels(it, 0, got.width, 0, 0, got.width, got.height) }
        val expected = IntArray(got.width * got.height) { i -> pixels[(top + i / got.width) * w + left + i % got.width] }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun bandsDownAndBackUpShareTheirReads() {
        val inner = Counting(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888))
        // 1000 rows of 50 kept at a time.
        val rows = KeptRows(inner, 50_000)
        for (top in 0 until h step 100) check(rows.read(0, top, w, top + 100, 1).also { }, 0, top, w, top + 100)
        assertEquals(4, inner.reads)
        // Going back up, each read reaches up from the band asked.
        for (top in h - 100 downTo 0 step 100) check(rows.read(0, top, w, top + 100, 1), 0, top, w, top + 100)
        assertTrue("${inner.reads} reads", inner.reads <= 9)
        rows.close()
        assertTrue(inner.closed)
    }

    @Test
    fun aReadOfAllTheKeptRowsIsACopy() {
        val inner = Counting(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888))
        val rows = KeptRows(inner, w.toLong() * h)
        val whole = rows.read(0, 0, w, h, 1)
        whole.recycle()
        // What is kept outlives what was handed out.
        check(rows.read(0, 10, w, 20, 1), 0, 10, w, 20)
        assertEquals(1, inner.reads)
    }
}
