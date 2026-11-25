package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rectangles of a JPEG stored turned or mirrored come out as its EXIF orientation shows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrientedRegionsTest {

    private val w = 7
    private val h = 11

    /** Every stored pixel a color of its own. */
    private val stored = IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) shl 16) or ((i / w) shl 8) }

    /** Crops of the stored image. */
    private val raw = object : RegionReader {
        val image: Bitmap = Bitmap.createBitmap(stored, w, h, Bitmap.Config.ARGB_8888)
        override val width = w
        override val height = h
        override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap =
            Bitmap.createBitmap(image, left, top, right - left, bottom - top)

        override fun close() = Unit
    }

    /** The stored pixel shown at ([x], [y]) in [orientation], as EXIF defines it. */
    private fun storedAt(orientation: Int, x: Int, y: Int): Int {
        val (sx, sy) = when (orientation) {
            2 -> (w - 1 - x) to y
            3 -> (w - 1 - x) to (h - 1 - y)
            4 -> x to (h - 1 - y)
            5 -> y to x
            6 -> y to (h - 1 - x)
            7 -> (w - 1 - y) to (h - 1 - x)
            8 -> (w - 1 - y) to x
            else -> x to y
        }
        return stored[sy * w + sx]
    }

    @Test
    fun everyOrientationReadsAsShown() {
        for (orientation in 1..8) {
            val reader = OrientedRegions(raw, orientation)
            val swapped = orientation >= 5
            assertEquals(if (swapped) h else w, reader.width)
            assertEquals(if (swapped) w else h, reader.height)
            // A rectangle off center, so a wrong turn shows.
            val l = 1
            val t = 2
            val r = reader.width - 2
            val b = reader.height - 1
            val got = reader.read(l, t, r, b, 1)
            assertEquals("$orientation", r - l, got.width)
            assertEquals("$orientation", b - t, got.height)
            val pixels = IntArray(got.width * got.height).also { got.getPixels(it, 0, got.width, 0, 0, got.width, got.height) }
            val expected = IntArray(got.width * got.height) { i -> storedAt(orientation, l + i % got.width, t + i / got.width) }
            assertArrayEquals("orientation $orientation", expected, pixels)
        }
    }

    @Test
    fun theStoredRectangleIsTheOneUnderIt() {
        // A 100 x 30 image stored turned a quarter (6): shown 30 x 100.
        assertArrayEquals(intArrayOf(10, 25, 40, 30), OrientedRegions.stored(6, 100, 30, 0, 10, 5, 40))
        assertArrayEquals(intArrayOf(0, 10, 5, 40), OrientedRegions.stored(1, 100, 30, 0, 10, 5, 40))
    }
}
