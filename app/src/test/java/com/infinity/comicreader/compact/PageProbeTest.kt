package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.Tiff
import com.infinity.comicreader.testing.FakeImages
import com.infinity.comicreader.testing.FakeJxl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Pages are told apart by their bytes: what may be stored anew losslessly, and what is left alone. */
class PageProbeTest {

    private fun tiff(name: String): ByteArray = javaClass.getResourceAsStream("/tiff/$name")!!.use { it.readBytes() }

    private fun pixels(channels: Int, bits: Int) = Pixels(3, 2, channels, bits, ByteArray(3 * 2 * channels * bits / 8) { (it * 37).toByte() })

    /** [png] with an `acTL` chunk before its image data: an animation. */
    private fun animated(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(png, 0, 33)
        val data = ByteArray(8)
        val type = "acTL".toByteArray()
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(type)
        out.write(data)
        out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(type); update(data) }.value.toInt()).array())
        out.write(png, 33, png.size - 33)
        return out.toByteArray()
    }

    @Test
    fun stillPngsOfEightBitsAreLosslessPagesOthersAreLeftAlone() {
        val png = Png.encode(pixels(3, 8))
        assertEquals(PageForm.LOSSLESS, PageProbe.formOf(png))
        assertEquals(PageForm.OTHER, PageProbe.formOf(Png.encode(pixels(4, 16))))
        assertEquals(PageForm.OTHER, PageProbe.formOf(animated(png)))
        assertTrue(PageProbe.pngPlain(Png.encode(pixels(1, 8))))
    }

    @Test
    fun onlyStillLosslessWebpIsALosslessPage() {
        val rgba = pixels(4, 8)
        assertEquals(PageForm.LOSSLESS, PageProbe.formOf(FakeImages().webp(rgba, lossless = true, quality = 100)!!))
        assertEquals(PageForm.OTHER, PageProbe.formOf(FakeImages().webp(rgba, lossless = false, quality = 90)!!))
        // An extended header marking an animation.
        val animation = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X\u000a\u0000\u0000\u0000\u0002\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000ANIM".toByteArray(Charsets.ISO_8859_1)
        assertFalse(PageProbe.webpLossless(animation))
    }

    @Test
    fun tiffsAreLosslessPagesOnlyWhenTheyDecodeExactly() {
        assertTrue(Tiff.exact(Tiff.ByteInput(tiff("rgb-none-strips.tif"))))
        assertEquals(PageForm.LOSSLESS, PageProbe.formOf(tiff("rgb-lzw-predictor-msb.tif")))
        // Reduced to 8 bits, JPEG-compressed, or more than one page.
        assertEquals(PageForm.OTHER, PageProbe.formOf(tiff("gray16-lzw-msb.tif")))
        assertEquals(PageForm.OTHER, PageProbe.formOf(tiff("jpeg-rgb.tif")))
        assertEquals(PageForm.OTHER, PageProbe.formOf(tiff("two-pages.tif")))
    }

    @Test
    fun pageSizesComeFromTheirHeaders() {
        assertArrayEquals(intArrayOf(3, 2), PageProbe.sizeOf(Png.encode(pixels(3, 8))))
        assertArrayEquals(intArrayOf(3, 2), PageProbe.sizeOf(FakeImages().webp(pixels(4, 8), lossless = true, quality = 100)!!))
        assertArrayEquals(intArrayOf(3, 2), PageProbe.sizeOf(FakeImages().webp(pixels(4, 8), lossless = false, quality = 90)!!))
        val bmp = ByteArray(54).also {
            it[0] = 'B'.code.toByte()
            it[1] = 'M'.code.toByte()
            ByteBuffer.wrap(it, 18, 8).order(ByteOrder.LITTLE_ENDIAN).putInt(1200).putInt(-1700)
        }
        assertArrayEquals(intArrayOf(1200, 1700), PageProbe.sizeOf(bmp))
        assertNull(PageProbe.sizeOf("GIF89a....".toByteArray()))
    }

    @Test
    fun aJpegXlHoldingItsJpegIsToldByItsBox() {
        val jxl = FakeJxl()
        val held = jxl.transcode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3), 7)
        assertEquals(PageForm.JXL, PageProbe.formOf(held))
        assertTrue(PageProbe.jxlHoldsJpeg(held))
        assertFalse(PageProbe.jxlHoldsJpeg(jxl.encode(ByteArray(6), 2, 1, 3, 8, null, 7)))
        assertEquals(PageForm.JPEG, PageProbe.formOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(PageForm.OTHER, PageProbe.formOf("GIF89a....".toByteArray()))
    }
}
