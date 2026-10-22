package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Page edges for seams: a thin strip decoded where the format allows, a whole decode
 * otherwise. Robolectric has no region decoder, so these run the whole decode; the strip
 * decode is checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodersEdgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val width = 600

    private fun encode(pixels: IntArray, w: Int, format: Bitmap.CompressFormat, quality: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, w, pixels.size / w, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    /** Slices of one generated strip, as PNG and JPEG pages, and their edges from the pixels themselves. */
    private val slices = SyntheticStrips.cut(SyntheticStrips.strip(width, 3000, seed = 21), width, listOf(1300, 1700))

    private fun exact(slice: IntArray, bottom: Boolean) = Seams.profile(slice, width, slice.size / width, bottom)!!

    @Test
    fun aStripEdgeMatchesThePixelsThere() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            for ((i, slice) in slices.withIndex()) {
                val bytes = encode(slice, width, format, quality = 95)
                for (bottom in listOf(true, false)) {
                    val edge = Decoders.edge(bytes, bottom)
                    assertNotNull("$format $i $bottom", edge)
                    val m = Seams.compare(edge!!, exact(slice, bottom))
                    assertTrue("$format $i $bottom: $m", m.distance < 4 && m.correlation > 0.95)
                }
            }
        }
    }

    @Test
    fun encodedSlicesRunOnAcrossTheirSeam() {
        val (upper, lower) = slices.map { encode(it, width, Bitmap.CompressFormat.JPEG, quality = 85) }
        assertEquals(true, Seams.runsOn(Decoders.edge(upper, bottom = true)!!, Decoders.edge(lower, bottom = false)!!))
        // Not the other way round: the top of the first slice is far from the bottom of the second.
        assertEquals(false, Seams.runsOn(Decoders.edge(lower, bottom = true)!!, Decoders.edge(upper, bottom = false)!!))
    }

    @Test
    fun otherFormatsDecodeWhole() {
        // BMP has no region decoder; a flat page has a flat edge.
        val bytes = TestImages.bmp(width, 1000, 0x336699)
        assertEquals(ImageKind.BMP, ImageKind.sniff(bytes))
        val edge = Decoders.edge(bytes, bottom = true)
        assertNotNull(edge)
        assertFalse(Seams.informative(edge!!))
    }

    @Test
    fun anArchivePageGivesItsEdge() {
        val pages = slices.mapIndexed { i, s -> Fixtures.entry("p%02d.png".format(i), encode(s, width, Bitmap.CompressFormat.PNG)) }
        val book = Fixtures.zip(File(tmp.root, "strip.cbz"), *pages.toTypedArray())
        PageSources.open(book, "UTF-8", tmp.newFolder("cache")).use { src ->
            assertEquals(true, Seams.runsOn(src.edge(0, bottom = true)!!, src.edge(1, bottom = false)!!))
            assertNull(src.edge(5, bottom = true))
        }
    }

    /** The halves of one generated picture, as pages [width] / 2 wide. */
    private val spread = SyntheticArt.art(width, 900, seed = 4).let { art ->
        SyntheticArt.columns(art, width, 0, width / 2) to SyntheticArt.columns(art, width, width / 2, width)
    }

    @Test
    fun theSidesOfASpreadInTwoFilesMatch() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            val (left, right) = spread.toList().map { Decoders.sides(encode(it, width / 2, format, quality = 85))!! }
            assertTrue("$format", SpreadSeams.verdict(left, right, rtl = false).match)
            assertFalse("$format", SpreadSeams.verdict(right, left, rtl = false).match)
            assertTrue("$format", SpreadSeams.verdict(right, left, rtl = true).match)
        }
    }

    @Test
    fun anArchivePageGivesItsSides() {
        val pages = spread.toList().mapIndexed { i, s -> Fixtures.entry("p%02d.jpg".format(i), encode(s, width / 2, Bitmap.CompressFormat.JPEG, 90)) }
        val book = Fixtures.zip(File(tmp.root, "spread.cbz"), *pages.toTypedArray())
        PageSources.open(book, "UTF-8", tmp.newFolder("cache")).use { src ->
            assertTrue(SpreadSeams.verdict(src.sides(0)!!, src.sides(1)!!, rtl = false).match)
            assertNull(src.sides(5))
        }
    }

    @Test
    fun brokenBytesHaveNoSides() {
        assertNull(Decoders.sides(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun brokenBytesHaveNoEdge() {
        assertNull(Decoders.edge(byteArrayOf(1, 2, 3), bottom = true))
    }

    @Test
    fun stripsAreSampledTowardTheProfileLength() {
        assertEquals(1, Decoders.edgeSample(100))
        assertEquals(1, Decoders.edgeSample(Seams.LENGTH * 2 - 1))
        assertEquals(2, Decoders.edgeSample(Seams.LENGTH * 2))
        assertEquals(4, Decoders.edgeSample(800))
    }
}
