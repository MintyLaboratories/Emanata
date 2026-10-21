package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewsTest {

    @Test
    fun fullSizeOnceEitherSideReachesTheBox() {
        assertTrue(Previews.isFullSize(256, 384))
        assertTrue(Previews.isFullSize(300, 100))
        assertFalse(Previews.isFullSize(100, 150))
        assertFalse(Previews.isFullSize(255, 255))
    }

    @Test
    fun sizeFitsTheBoxWithoutEnlarging() {
        assertArrayEquals(intArrayOf(171, 256), Previews.sizeOf(2000, 3000))
        assertArrayEquals(intArrayOf(256, 128), Previews.sizeOf(4000, 2000))
        assertArrayEquals(intArrayOf(100, 150), Previews.sizeOf(100, 150))
    }

    @Test
    fun halvingsStopAtTwiceThePreview() {
        // 2000x3000 -> 1000x1500 -> 500x750 -> 250x375 would fall under 2 x 171x256.
        assertEquals(2, Previews.halvings(2000, 3000, 171, 256))
        assertEquals(0, Previews.halvings(600, 900, 171, 256))
        assertEquals(0, Previews.halvings(100, 150, 100, 150))
    }

    @Test
    fun sampleKeepsThePreviewCovered() {
        assertEquals(8, Previews.sampleFor(2000, 3000, 171, 256))
        // 1/8 of 1200x1800 is 150x225, short of 171x256.
        assertEquals(4, Previews.sampleFor(1200, 1800, 171, 256))
        assertEquals(1, Previews.sampleFor(171, 256, 171, 256))
        // The decoder rounds up: 1/2 of 341x511 is 171x256.
        assertEquals(2, Previews.sampleFor(341, 511, 171, 256))
        assertEquals(Previews.MAX_SAMPLE, Previews.sampleFor(40_000, 60_000, 171, 256))
    }

    @Test
    fun makeShrinksWholePageAndLeavesSourceAlone() {
        val page = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.rgb(200, 40, 90))
        val preview = Previews.make(page)
        assertEquals(171, preview.width)
        assertEquals(256, preview.height)
        assertFalse(page.isRecycled)
        assertNotSame(page, preview)
        val c = preview.getPixel(80, 120)
        assertTrue(abs(Color.red(c) - 200) <= 2 && abs(Color.green(c) - 40) <= 2 && abs(Color.blue(c) - 90) <= 2)
    }

    @Test
    fun makeAveragesFineDetail() {
        // One-pixel stripes average to mid gray instead of aliasing to black or white.
        val page = Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888)
        val row = IntArray(1024) { if (it % 2 == 0) Color.BLACK else Color.WHITE }
        for (y in 0 until 1536) page.setPixels(row, 0, 1024, 0, y, 1024, 1)
        val preview = Previews.make(page)
        val gray = Color.red(preview.getPixel(preview.width / 2, preview.height / 2))
        assertTrue("gray $gray", gray in 110..145)
    }

    @Test
    fun smallPageIsCopiedAsIs() {
        val page = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        val preview = Previews.make(page)
        assertEquals(120, preview.width)
        assertEquals(90, preview.height)
        assertNotSame(page, preview)
    }
}
