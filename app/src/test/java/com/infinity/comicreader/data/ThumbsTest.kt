package com.infinity.comicreader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Covers come at exactly the requested size and are cached without loss. */
@RunWith(RobolectricTestRunner::class)
// File-backed ImageDecoder sources do not open on the host, so files decode as on API 27.
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clear() = Thumbs.clear(context)

    /** A [w] x [h] cover: red top third, green middle, blue bottom, so the crop shows. */
    private fun cover(w: Int = 300, h: Int = 450, name: String = "cover.png"): BookEntry {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            bitmap.setPixel(x, y, if (y < h / 3) Color.RED else if (y < 2 * h / 3) Color.GREEN else Color.BLUE)
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun coverFillsTheExactSizeCroppedEvenly() {
        val thumb = Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL, CropAnchor.CENTER))
        assertNotNull(thumb)
        assertEquals(120, thumb!!.width)
        assertEquals(60, thumb.height)
        // A 2:1 box keeps only the middle of the 2:3 cover: green, but for the rows next
        // to the cut, whose kernel reaches past it.
        assertEquals(Color.GREEN, thumb.getPixel(60, 30))
        assertEquals(Color.GREEN, thumb.getPixel(0, 30))
        assertTrue(Color.red(thumb.getPixel(60, 0)) > 0)
    }

    @Test
    fun fillFromTheTopKeepsTheTop() {
        val thumb = Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL, CropAnchor.TOP))!!
        assertEquals(Color.RED, thumb.getPixel(60, 2))
        assertEquals(Color.RED, thumb.getPixel(60, 30))
    }

    @Test
    fun fitShowsTheWholeCoverOverItsBlurredCopy() {
        // A 2:3 cover in a 2:1 box: the whole cover, full height, in the middle.
        val thumb = Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FIT))!!
        assertEquals(120, thumb.width)
        assertEquals(Color.RED, thumb.getPixel(60, 3))
        assertEquals(Color.BLUE, thumb.getPixel(60, 56))
        // Beside it, an opaque, darker blur of the same colors rather than empty space.
        val side = thumb.getPixel(5, 30)
        assertEquals(255, Color.alpha(side))
        assertTrue(Color.green(side) in 1..230)
    }

    @Test
    fun autoFillsCoversOfTheBoxShapeAndFitsOthers() {
        val same = Thumbs.load(context, cover(), "UTF-8", 200, 300)!!
        assertEquals(Color.RED, same.getPixel(100, 1))
        assertEquals(Color.RED, same.getPixel(1, 1))
        // A landscape image is fitted: its top edge sits well below the box's.
        val wide = Thumbs.load(context, cover(600, 300, "wide.png"), "UTF-8", 200, 300)!!
        assertTrue(Color.red(wide.getPixel(100, 1)) < 230)
        assertEquals(Color.GREEN, wide.getPixel(100, 150))
    }

    @Test
    fun eachStyleIsItsOwnCover() {
        val entry = cover()
        Thumbs.load(context, entry, "UTF-8", 50, 75, CoverStyle(ThumbFit.FILL))
        Thumbs.load(context, entry, "UTF-8", 50, 75, CoverStyle(ThumbFit.FIT))
        assertEquals(2, File(context.cacheDir, "thumbnails").listFiles()!!.size)
    }

    @Test
    fun diskCacheIsLossless() {
        val entry = cover()
        val made = Thumbs.load(context, entry, "UTF-8", 97, 131)!!
        val files = File(context.cacheDir, "thumbnails").listFiles()!!
        assertEquals(1, files.size)
        val stored = BitmapFactory.decodeFile(files[0].path)
        assertEquals(97, stored.width)
        assertArrayEquals(pixels(made), pixels(stored))
    }

    @Test
    fun eachSizeIsItsOwnCover() {
        val entry = cover()
        val small = Thumbs.load(context, entry, "UTF-8", 50, 75)!!
        val large = Thumbs.load(context, entry, "UTF-8", 200, 300)!!
        assertEquals(50, small.width)
        assertEquals(200, large.width)
        assertTrue(File(context.cacheDir, "thumbnails").listFiles()!!.size == 2)
    }
}
