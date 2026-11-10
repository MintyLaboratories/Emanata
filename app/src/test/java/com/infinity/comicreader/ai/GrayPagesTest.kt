package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** Gray pages found ([GrayPages.isGray]) and model output collapsed to gray ([GrayPages.toGray]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrayPagesTest {

    private fun bitmapOf(px: IntArray, w: Int, h: Int, opaque: Boolean = true) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(px, 0, w, 0, 0, w, h)
        setHasAlpha(!opaque)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun gray(v: Int, a: Int = 0xFF) = (a shl 24) or (v shl 16) or (v shl 8) or v

    /** A [w] x [h] page of random grays, as a screentone scan is. */
    private fun grayPage(w: Int, h: Int): IntArray {
        val r = Random(w * 31 + h)
        return IntArray(w * h) { gray(r.nextInt(256)) }
    }

    @Test
    fun aPageOfEqualChannelsIsGray() {
        assertTrue(GrayPages.isGray(bitmapOf(grayPage(40, 50), 40, 50)))
        assertTrue(GrayPages.isGray(bitmapOf(IntArray(9) { gray(0) }, 3, 3)))
        assertTrue(GrayPages.isGray(bitmapOf(IntArray(9) { gray(255) }, 3, 3)))
    }

    @Test
    fun oneColoredPixelMakesAPageColor() {
        val px = grayPage(40, 50)
        // The last pixel read, a code apart in blue only.
        px[px.size - 1] = px[px.size - 1] xor 1
        val page = bitmapOf(px, 40, 50)
        assertFalse(GrayPages.isGray(page))
        assertEquals(1, GrayPages.spread(page))
    }

    @Test
    fun aPixelIsGrayWhenItsThreeChannelsAreEqual() {
        for (v in 0..255) assertTrue(GrayPages.isGray(gray(v, a = v)))
        for (c in intArrayOf(0xFF010000.toInt(), 0xFF000100.toInt(), 0xFF000001.toInt(), 0xFF7F7F80.toInt(), 0xFF807F80.toInt())) {
            assertFalse(Integer.toHexString(c), GrayPages.isGray(c))
        }
    }

    @Test
    fun spreadIsTheLargestChannelDifference() {
        val px = grayPage(20, 20)
        px[5] = 0xFF102030.toInt()
        px[300] = 0xFF80807A.toInt()
        assertEquals(0x20, GrayPages.spread(bitmapOf(px, 20, 20)))
        assertEquals(0, GrayPages.spread(bitmapOf(grayPage(20, 20), 20, 20)))
        assertEquals(0x20, GrayPages.spreadOf(0xFF102030.toInt()))
        assertEquals(0, GrayPages.spreadOf(gray(77)))
    }

    @Test
    fun spreadStopsAtTheFirstPixelOverItsLimit() {
        val px = grayPage(20, 20)
        px[0] = 0xFF000005.toInt()
        px[399] = 0xFF0000FF.toInt()
        assertEquals("stopped at the first pixel over 0", 5, GrayPages.spread(bitmapOf(px, 20, 20), stopAbove = 0))
        assertEquals(0xFF, GrayPages.spread(bitmapOf(px, 20, 20)))
    }

    @Test
    fun translucentPixelsCountByTheirColorAlone() {
        val px = IntArray(16) { gray(it * 16, a = 0x80) }
        assertTrue(GrayPages.isGray(bitmapOf(px, 4, 4, opaque = false)))
        px[7] = 0x80FF0000.toInt()
        assertFalse(GrayPages.isGray(bitmapOf(px, 4, 4, opaque = false)))
    }

    @Test
    fun lumaKeepsGrayAsItIsAndAlpha() {
        for (v in 0..255) assertEquals(gray(v), GrayPages.luma(gray(v)))
        assertEquals(gray(0x40, a = 0x80), GrayPages.luma(gray(0x40, a = 0x80)))
    }

    @Test
    fun lumaWeighsGreenMostAndBlueLeast() {
        assertEquals(gray(77), GrayPages.luma(0xFFFF0000.toInt()))
        assertEquals(gray(149), GrayPages.luma(0xFF00FF00.toInt()))
        assertEquals(gray(29), GrayPages.luma(0xFF0000FF.toInt()))
        // A faint tint on mid-gray, as a model may add, comes back near the gray.
        assertEquals(gray(131), GrayPages.luma(0xFF8A8080.toInt()))
    }

    @Test
    fun toGrayCollapsesOnlyItsArea() {
        val w = 12
        val h = 10
        val r = Random(7)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        val b = bitmapOf(px, w, h)
        GrayPages.toGray(b, Box(3, 2, 5, 4))
        val out = pixels(b)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val inside = x in 3 until 8 && y in 2 until 6
            assertEquals("($x, $y)", if (inside) GrayPages.luma(px[i]) else px[i], out[i])
        }
    }

    @Test
    fun toGrayOfAWholeBitmapMakesItGray() {
        val r = Random(3)
        // Taller than the rows read at a time.
        val px = IntArray(9 * 40) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        val b = bitmapOf(px, 9, 40)
        GrayPages.toGray(b)
        assertTrue(GrayPages.isGray(b))
        assertArrayEquals(px.map(GrayPages::luma).toIntArray(), pixels(b))
    }
}
