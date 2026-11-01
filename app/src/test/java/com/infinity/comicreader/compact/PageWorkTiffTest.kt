package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.Tiff
import com.infinity.comicreader.testing.FakeImages
import com.infinity.comicreader.testing.FakeJxl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Make compatible on TIFF pages: lossless WebP, else PNG, checked pixel for pixel. */
class PageWorkTiffTest {
    private val images = FakeImages()
    private val work = PageWork(FakeJxl(), images)

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/tiff/$name")!!.use { it.readBytes() }

    /** The page as the reader shows it: RGBA of 8 bits. */
    private fun shown(tiff: ByteArray): Pixels {
        val input = Tiff.ByteInput(tiff)
        val page = Tiff.page(input)
        val out = ByteArray(page.width * page.height * 4)
        Tiff.decode(input, page, 1, { _, _ -> error("no JPEG expected") }) { x, y, row, count ->
            for (i in 0 until count) {
                val c = row[i]
                val o = (y * page.width + x + i) * 4
                out[o] = (c shr 16).toByte()
                out[o + 1] = (c shr 8).toByte()
                out[o + 2] = c.toByte()
                out[o + 3] = (c ushr 24).toByte()
            }
        }
        return Pixels(page.width, page.height, 4, 8, out)
    }

    /** A profile whose header names color space [space]. */
    private fun profile(space: String) = ByteArray(200) { (it * 7).toByte() }.also { space.toByteArray().copyInto(it, 16) }

    /**
     * A little-endian TIFF of [width] x [height] samples [data] (one strip, uncompressed):
     * [photometric], [samples] per pixel of [bits], [extra] samples' meaning, an ICC [icc], a [palette].
     */
    private fun tiff(
        width: Int, height: Int, photometric: Int, samples: Int, bits: Int, data: ByteArray, extra: Int? = null, icc: ByteArray? = null,
        palette: LongArray? = null
    ): ByteArray {
        val tags = sortedMapOf<Int, Any>(
            256 to longArrayOf(width.toLong()), 257 to longArrayOf(height.toLong()),
            258 to LongArray(samples) { bits.toLong() }, 259 to longArrayOf(1), 262 to longArrayOf(photometric.toLong()),
            273 to longArrayOf(8), 277 to longArrayOf(samples.toLong()), 278 to longArrayOf(height.toLong()),
            279 to longArrayOf(data.size.toLong())
        )
        if (extra != null) tags[338] = longArrayOf(extra.toLong())
        if (icc != null) tags[34675] = icc
        if (palette != null) tags[320] = palette
        val ifdAt = 8 + data.size
        var extraAt = ifdAt + 2 + tags.size * 12 + 4
        val out = ByteBuffer.allocate(extraAt + tags.values.sumOf { if (it is ByteArray) it.size else 4 * (it as LongArray).size }).order(ByteOrder.LITTLE_ENDIAN)
        out.put("II".toByteArray()).putShort(42).putInt(ifdAt).put(data).putShort(tags.size.toShort())
        for ((tag, value) in tags) {
            if (value is ByteArray) {
                out.putShort(tag.toShort()).putShort(7).putInt(value.size).putInt(extraAt)
                value.copyInto(out.array(), extraAt)
                extraAt += value.size
                continue
            }
            val values = value as LongArray
            out.putShort(tag.toShort()).putShort(4).putInt(values.size)
            if (values.size == 1) {
                out.putInt(values[0].toInt())
            } else {
                out.putInt(extraAt)
                for ((i, v) in values.withIndex()) out.putInt(extraAt + 4 * i, v.toInt())
                extraAt += 4 * values.size
            }
        }
        out.putInt(0)
        return out.array()
    }

    private fun pngChunk(png: ByteArray, type: String): Boolean = String(png, Charsets.ISO_8859_1).contains(type)

    @Test
    fun aTiffBecomesLosslessWebpCheckedPixelForPixel() {
        for (name in listOf("rgb-none-strips.tif", "rgb-lzw-predictor-msb.tif", "rgba-deflate.tif", "palette-lzw.tif", "two-pages.tif")) {
            val page = fixture(name)
            val made = work.compatible(page)!!
            assertEquals(name, "webp", made.ext)
            assertFalse(name, made.reencoded)
            assertTrue(name, shown(page).same(images.decode(made.bytes)))
        }
    }

    @Test
    fun whereWebpCannotHoldItItIsAPngOfTheFewestChannels() {
        val page = fixture("gray8-deflate-predictor.tif")
        images.badWebp = true
        val made = work.compatible(page)!!
        assertEquals("png", made.ext)
        val png = Png.decode(made.bytes)!!
        assertEquals(1, png.channels)
        assertTrue(PageWork.reduced(shown(page)).same(png))
        images.badWebp = false
        images.losslessWebp = false
        assertEquals("png", work.compatible(fixture("rgb-none-strips.tif"))!!.ext)
    }

    @Test
    fun sixteenBitSamplesBecomeASixteenBitPng() {
        for ((name, channels) in listOf("gray16-lzw-msb.tif" to 1, "rgb16-none-tiles-msb.tif" to 3)) {
            val page = fixture(name)
            val made = work.compatible(page)!!
            assertEquals(name, "png", made.ext)
            assertFalse(made.reencoded)
            val png = Png.decode(made.bytes)!!
            assertEquals(16, png.bits)
            assertEquals(channels, png.channels)
            // Each sample, rounded to 8 bits, is what the reader shows.
            val view = PageWork.reduced(shown(page), allowGray = channels == 1).data
            for (i in 0 until png.width * png.height * channels) {
                val v = (png.data[2 * i].toInt() and 0xFF) or ((png.data[2 * i + 1].toInt() and 0xFF) shl 8)
                assertEquals("$name sample $i", view[i].toInt() and 0xFF, (v * 255 + 32767) / 65535)
            }
        }
    }

    @Test
    fun sixteenBitSamplesAreKeptExactly() {
        val samples = shortArrayOf(0, 1, 257, 0x1234, -1, 0x7FFF)
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val made = work.compatible(tiff(3, 2, 1, 1, 16, data))!!
        assertArrayEquals(data, Png.decode(made.bytes)!!.data)
        // White is zero: inverted.
        val inverted = Png.decode(work.compatible(tiff(3, 2, 0, 1, 16, data))!!.bytes)!!.data
        val back = ByteBuffer.wrap(inverted).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) assertEquals(0xFFFF - (s.toInt() and 0xFFFF), back.short.toInt() and 0xFFFF)
    }

    /** 16-bit samples of [png], little-endian as [Png.decode] gives them. */
    private fun samples16(png: Pixels): IntArray =
        IntArray(png.data.size / 2) { (png.data[2 * it].toInt() and 0xFF) or ((png.data[2 * it + 1].toInt() and 0xFF) shl 8) }

    /** The `sBIT` chunk's data in [png]; null when none. */
    private fun sbit(png: ByteArray): ByteArray? {
        val at = String(png, Charsets.ISO_8859_1).indexOf("sBIT").takeIf { it >= 4 } ?: return null
        val length = ByteBuffer.wrap(png, at - 4, 4).int
        return png.copyOfRange(at + 4, at + 4 + length)
    }

    @Test
    fun paletteColorsNotOf8BitsBecomeA16BitRgbPngExactly() {
        // Colors written as v << 8, as many tools do: not multiples of 257.
        val reds = longArrayOf(0x0000, 0x1200, 0xFF00, 0x8000)
        val greens = longArrayOf(0x0100, 0x3400, 0x0000, 0x8000)
        val blues = longArrayOf(0xFF00, 0x5600, 0x0000, 0x8001)
        // 2 x 2 pixels of 2-bit indexes 0, 1 / 2, 3.
        val page = tiff(2, 2, 3, 1, 2, byteArrayOf(0x10, 0xB0.toByte()), palette = reds + greens + blues)
        val made = work.compatible(page)!!
        assertEquals("png", made.ext)
        assertFalse(made.reencoded)
        val png = Png.decode(made.bytes)!!
        assertEquals(16, png.bits)
        assertEquals(3, png.channels)
        val want = (0 until 4).flatMap { i -> listOf(reds[i], greens[i], blues[i]).map { it.toInt() } }
        assertEquals(want, samples16(png).toList())
        assertNull(sbit(made.bytes))
    }

    @Test
    fun grayOf9To15BitsBecomesA16BitPngExactlyItsBitsNoted() {
        for (bits in listOf(10, 12)) {
            val max = (1 shl bits) - 1
            val values = intArrayOf(0, max, 1, max / 3)
            // 4 x 1 pixels packed most significant bit first.
            var acc = java.math.BigInteger.ZERO
            for (v in values) acc = acc.shiftLeft(bits).or(java.math.BigInteger.valueOf(v.toLong()))
            val rowBytes = (4 * bits + 7) / 8
            acc = acc.shiftLeft(rowBytes * 8 - 4 * bits)
            val data = acc.toByteArray().takeLast(rowBytes).toByteArray().let { ByteArray(rowBytes - it.size) + it }
            val made = work.compatible(tiff(4, 1, 1, 1, bits, data))!!
            assertEquals("png", made.ext)
            assertFalse(made.reencoded)
            val png = Png.decode(made.bytes)!!
            assertEquals(1, png.channels)
            val got = samples16(png)
            // The stored bits on top, 0 and the largest at the ends of 16 bits.
            for ((i, v) in values.withIndex()) assertEquals("$bits bits, pixel $i", v, got[i] ushr (16 - bits))
            assertEquals(0, got[0])
            assertEquals(0xFFFF, got[1])
            assertArrayEquals(byteArrayOf(bits.toByte()), sbit(made.bytes))
        }
    }

    @Test
    fun anRgbProfileIsKeptInTheWebpAndAGrayOneInAGrayPng() {
        val rgb = work.compatible(tiff(2, 2, 2, 3, 8, ByteArray(12) { (it * 20).toByte() }, icc = profile("RGB ")))!!
        assertEquals("webp", rgb.ext)
        assertEquals("VP8X", String(rgb.bytes, 12, 4))
        assertTrue(String(rgb.bytes, Charsets.ISO_8859_1).contains("ICCP"))
        val gray = work.compatible(tiff(2, 2, 1, 1, 8, byteArrayOf(0, 60, 120, -1), icc = profile("GRAY")))!!
        assertEquals("png", gray.ext)
        assertTrue(pngChunk(gray.bytes, "iCCP"))
        assertEquals(1, Png.decode(gray.bytes)!!.channels)
        // An RGB profile keeps a gray-looking RGB page in color channels.
        images.losslessWebp = false
        val grayLooking = work.compatible(tiff(2, 1, 2, 3, 8, byteArrayOf(9, 9, 9, 50, 50, 50), icc = profile("RGB ")))!!
        assertEquals(3, Png.decode(grayLooking.bytes)!!.channels)
        // A profile that doesn't suit the samples is dropped.
        val mismatched = work.compatible(tiff(2, 1, 1, 1, 8, byteArrayOf(9, 50), icc = profile("CMYK")))!!
        assertFalse(pngChunk(mismatched.bytes, "iCCP"))
    }

    @Test
    fun colorsTheReaderConvertsAreStoredAsShownAndCountedReencoded() {
        val cmyk = tiff(2, 1, 5, 4, 8, byteArrayOf(0, 0, 0, 0, 10, -56, 30, 40))
        val made = work.compatible(cmyk)!!
        assertEquals("webp", made.ext)
        assertTrue(made.reencoded)
        assertTrue(shown(cmyk).same(images.decode(made.bytes)))
        val premultiplied = tiff(1, 1, 2, 4, 8, byteArrayOf(50, 40, 30, 100), extra = 1)
        assertTrue(work.compatible(premultiplied)!!.reencoded)
    }

    @Test
    fun aJpegCompressedOrUnreadableTiffStaysAndSaysSo() {
        assertThrows(PageWork.Unconverted::class.java) { work.compatible(fixture("jpeg-rgb.tif")) }
        assertThrows(PageWork.Unconverted::class.java) { work.compatible("II".toByteArray() + byteArrayOf(42, 0, 99, 0, 0, 0)) }
    }
}
