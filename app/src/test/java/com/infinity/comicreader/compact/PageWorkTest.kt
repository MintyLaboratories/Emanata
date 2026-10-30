package com.infinity.comicreader.compact

import com.infinity.comicreader.testing.FakeImages
import com.infinity.comicreader.testing.FakeJxl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Pages made anew: Compact keeps a page made smaller only once it checks out (the JPEG
 * rebuilt byte for byte, the pixels decoded back the same); Make compatible gives back the
 * JPEG, or WebP or PNG.
 */
class PageWorkTest {
    private val jxl = FakeJxl()
    private val images = FakeImages()
    private val work = PageWork(jxl, images)

    /** A JPEG as far as its first bytes tell, that deflates well. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(4000) { (it % 50).toByte() }

    private fun pixels(channels: Int, width: Int = 40, height: Int = 30, fill: (Int) -> Int = { it / 9 }) =
        Pixels(width, height, channels, 8, ByteArray(width * height * channels) { fill(it).toByte() })

    /** RGBA pixels, each row of one color: gray when [gray], opaque unless [alpha]. */
    private fun rgba(gray: Boolean, alpha: Boolean): Pixels {
        val p = pixels(4)
        for (i in p.data.indices step 4) {
            val row = i / (4 * p.width)
            val v = (row * 7).toByte()
            p.data[i] = v
            p.data[i + 1] = if (gray) v else (v + 9).toByte()
            p.data[i + 2] = if (gray) v else (v + 3).toByte()
            p.data[i + 3] = if (alpha) (100 + row).toByte() else (-1).toByte()
        }
        return p
    }

    @Test
    fun aJpegBecomesItsTranscodeOnceItRebuildsByteForByte() {
        val made = work.compact(jpeg)!!
        assertEquals("jxl", made.ext)
        assertFalse(made.reencoded)
        assertArrayEquals(jpeg, jxl.reconstruct(made.bytes))
        assertEquals(listOf(PageWork.EFFORT_TRANSCODE), jxl.efforts)
        jxl.badReconstruct = true
        assertNull(work.compact(jpeg))
    }

    @Test
    fun aPageIsReplacedOnlyWhenSmaller() {
        val random = Random(7)
        val noise = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(3000) { random.nextInt().toByte() }
        assertNull(work.compact(noise))
        assertEquals(1, jxl.transcodes)
    }

    @Test
    fun aLosslessPageIsStoredInTheFewestChannelsThatHoldIt() {
        for ((pixels, channels) in listOf(rgba(gray = true, alpha = false) to 1, rgba(gray = false, alpha = false) to 3, rgba(gray = true, alpha = true) to 2, rgba(gray = false, alpha = true) to 4)) {
            val made = work.compact(Png.encode(pixels))
            assertEquals(channels, jxl.channelsEncoded.last())
            // Kept only when smaller: in sRGB, decoded back the same.
            if (made == null) continue
            assertTrue(jxl.info(made.bytes)!!.srgb)
            assertArrayEquals(PageWork.reduced(pixels).data, jxl.decode(made.bytes, channels, 8))
        }
        assertNotNull(work.compact(Png.encode(rgba(gray = true, alpha = false))))
        assertEquals(PageWork.EFFORT_LOSSLESS, jxl.efforts.last())
    }

    @Test
    fun aLosslessPageThatDecodesBackOtherwiseIsKept() {
        jxl.badDecode = true
        assertNull(work.compact(Png.encode(rgba(gray = false, alpha = false))))
        assertEquals(1, jxl.encodes)
    }

    @Test
    fun aJpegXlHoldingAJpegIsRepackedAndALosslessOneRecompressedHarder() {
        val held = jxl.transcode(jpeg, PageWork.EFFORT_TRANSCODE)
        // The fake packs the same at any effort: no smaller, so kept.
        assertNull(work.compact(held))
        assertEquals(PageWork.EFFORT_REPACK, jxl.efforts.last())
        val lossless = jxl.encode(pixels(3).data, 40, 30, 3, 8, null, PageWork.EFFORT_LOSSLESS)
        assertNull(work.compact(lossless))
        assertEquals(PageWork.EFFORT_RECOMPRESS, jxl.efforts.last())
        // A lossy one is never touched.
        val encodes = jxl.encodes
        assertNull(work.compact(jxl.lossy(rgba(gray = false, alpha = false))))
        assertEquals(encodes, jxl.encodes)
    }

    @Test
    fun theMemoryAPageTakesCountsItsPixels() {
        assertEquals(jpeg.size * 3L, work.memoryFor(jpeg))
        val png = Png.encode(pixels(3))
        assertEquals(png.size * 3L + 40 * 30 * PageWork.BYTES_PER_PIXEL, work.memoryFor(png))
        assertEquals(Long.MAX_VALUE, work.memoryFor("GIF89a....".toByteArray()))
    }

    @Test
    fun aPngWithAColorProfileKeepsItsSamplesAndProfile() {
        val icc = "a profile Android may not read".toByteArray()
        // Noise, which PNG's filters do not help.
        val random = Random(5)
        val stored = pixels(3) { random.nextInt() }
        val made = work.compact(Png.encode(stored, icc))!!
        assertEquals("jxl", made.ext)
        assertArrayEquals(icc, jxl.iccOfPixels(made.bytes))
        assertArrayEquals(stored.data, jxl.decode(made.bytes, 3, 8))
        // Read as stored, not through the platform.
        assertEquals(listOf(3), jxl.channelsEncoded)
        // With a transparent color, which that reading leaves out, it stays.
        val png = Png.encode(stored, icc)
        val trns = byteArrayOf(0, 0, 0, 2, 't'.code.toByte(), 'R'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 0, 1, 0, 0, 0, 0)
        val withTrns = png.copyOfRange(0, 33) + trns + png.copyOfRange(33, png.size)
        assertNull(work.compact(withTrns))
    }

    @Test
    fun makeCompatibleGivesBackTheJpegByteForByte() {
        val made = work.compatible(jxl.transcode(jpeg, 7))!!
        assertEquals("jpg", made.ext)
        assertArrayEquals(jpeg, made.bytes)
        // Pages not JPEG XL are left alone.
        assertNull(work.compatible(jpeg))
        assertNull(work.compatible(Png.encode(pixels(3))))
    }

    @Test
    fun aLosslessJpegXlBecomesLosslessWebpCheckedPixelForPixel() {
        val source = rgba(gray = false, alpha = true)
        val made = work.compatible(jxl.encode(source.data, 40, 30, 4, 8, null, 7))!!
        assertEquals("webp", made.ext)
        assertFalse(made.reencoded)
        assertTrue(source.same(images.decode(made.bytes)))
    }

    @Test
    fun aWebpThatDoesNotDecodeBackTheSameGivesWayToPng() {
        val source = rgba(gray = false, alpha = true)
        images.badWebp = true
        val made = work.compatible(jxl.encode(source.data, 40, 30, 4, 8, null, 7))!!
        assertEquals("png", made.ext)
        assertTrue(source.same(Png.decode(made.bytes)))
    }

    /** A minimal ISO-BMFF image of [brand]: its `ftyp`, then an `ispe` of [width] x [height]. */
    private fun iso(brand: String, width: Int, height: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = java.nio.ByteBuffer.allocate(64)
        b.putInt(16).put("ftyp".toByteArray()).put(brand.toByteArray()).putInt(0)
        b.putInt(20).put("ispe".toByteArray()).putInt(0).putInt(width).putInt(height)
        out.write(b.array(), 0, b.position())
        return out.toByteArray()
    }

    @Test
    fun avifAndHeifPagesBecomeVeryHighQualityWebp() {
        for (brand in listOf("avif", "heic", "mif1")) {
            val made = work.compatible(iso(brand, 300, 200))!!
            assertEquals("webp", made.ext)
            assertTrue(made.reencoded)
            assertEquals("RIFF", String(made.bytes, 0, 4))
        }
        assertEquals(PageWork.LOSSY_QUALITY, 90)
        // Compact leaves them alone.
        assertNull(work.compact(iso("avif", 300, 200)))
    }

    @Test
    fun animatedOrUndecodableAvifStaysAndSaysSo() {
        assertThrows(PageWork.Unconverted::class.java) { work.compatible(iso("avis", 30, 20)) }
        assertThrows(PageWork.Unconverted::class.java) { PageWork(jxl, FakeImages().apply { decodesIso = false }).compatible(iso("avif", 30, 20)) }
    }

    @Test
    fun anAvifsSizeComesFromItsLargestIspe() {
        assertArrayEquals(intArrayOf(300, 200), PageProbe.sizeOf(iso("avif", 300, 200)))
        assertEquals(iso("avif", 300, 200).size * 3L + 300L * 200 * PageWork.BYTES_PER_PIXEL, work.memoryFor(iso("avif", 300, 200)))
    }

    @Test
    fun aJpegXlThatCannotBeMadeCompatibleSaysSo() {
        assertThrows(PageWork.Unconverted::class.java) { work.compatible(FakeJxl.CONTAINER + ByteArray(20)) }
    }

    @Test
    fun aGrayLosslessJpegXlInItsOwnProfileKeepsItInTheWebp() {
        val gray = pixels(1)
        val made = work.compatible(jxl.encode(gray.data, 40, 30, 1, 8, "own".toByteArray(), 7))!!
        assertEquals("VP8X", String(made.bytes, 12, 4))
        assertTrue(String(made.bytes, Charsets.ISO_8859_1).contains("ICCP\u0003\u0000\u0000\u0000icc"))
    }

    @Test
    fun withoutLosslessWebpOrForSixteenBitsItIsAPng() {
        images.losslessWebp = false
        val made = work.compatible(jxl.encode(pixels(3).data, 40, 30, 3, 8, null, 7))!!
        assertEquals("png", made.ext)
        assertTrue(pixels(3).same(Png.decode(made.bytes)))
        val deep = Pixels(10, 4, 2, 16, ByteArray(160) { (it * 11).toByte() })
        val made16 = PageWork(jxl, FakeImages()).compatible(jxl.encode(deep.data, 10, 4, 2, 16, null, 7))!!
        assertEquals("png", made16.ext)
        assertTrue(deep.same(Png.decode(made16.bytes)))
    }

    @Test
    fun aLossyJpegXlIsReencodedAsWebpAtQualityNinety() {
        val source = rgba(gray = false, alpha = false)
        val made = work.compatible(jxl.lossy(source))!!
        assertEquals("webp", made.ext)
        assertTrue(made.reencoded)
        assertEquals("VP8 ", String(made.bytes, 12, 4))
        // Its size as in its frame.
        assertEquals(40, ByteBuffer.wrap(made.bytes, 26, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
    }
}
