package com.infinity.comicreader.compact

import com.infinity.comicreader.testing.FakeImages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PNGs written for pages made compatible, and ICC profiles embedded in WebPs. */
class PngWebpTest {

    private fun pixels(channels: Int, bits: Int, width: Int = 37, height: Int = 23) =
        Pixels(width, height, channels, bits, ByteArray(width * height * channels * bits / 8) { (it * 31 + it / 97).toByte() })

    @Test
    fun pngsReadBackAsWrittenForEveryLayout() {
        for (channels in 1..4) for (bits in listOf(8, 16)) {
            val p = pixels(channels, bits)
            val png = Png.encode(p)
            assertTrue("$channels x $bits", p.same(Png.decode(png)))
            // Color type and depth as asked.
            assertEquals(bits, png[24].toInt())
            assertEquals(intArrayOf(0, 4, 2, 6)[channels - 1], png[25].toInt())
        }
    }

    @Test
    fun aPngCarriesItsIccProfile() {
        val png = Png.encode(pixels(3, 8), icc = ByteArray(300) { it.toByte() })
        val text = String(png, Charsets.ISO_8859_1)
        assertTrue(text.indexOf("iCCP") in 0 until text.indexOf("IDAT"))
        assertTrue(text.contains("ICC profile"))
        assertTrue(pixels(3, 8).same(Png.decode(png)))
        assertNull(Png.decode(png.copyOf(20)))
    }

    private fun chunks(webp: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = 12
        while (at + 8 <= webp.size) {
            val n = ByteBuffer.wrap(webp, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            out += String(webp, at, 4, Charsets.ISO_8859_1) to webp.copyOfRange(at + 8, at + 8 + n)
            at += 8 + n + (n and 1)
        }
        return out
    }

    @Test
    fun anIccProfileGoesAfterAnExtendedHeaderBeforeTheImage() {
        val webp = FakeImages().webp(pixels(4, 8, 300, 200), lossless = true, quality = 100)!!
        val icc = ByteArray(101) { (it + 1).toByte() }
        val out = WebpIcc.embed(webp, icc)!!
        assertEquals(out.size - 8, ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        val parts = chunks(out)
        assertEquals(listOf("VP8X", "ICCP", "VP8L"), parts.map { it.first })
        val header = parts[0].second
        assertEquals(0x20, header[0].toInt() and 0x20)
        assertEquals(299, (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8))
        assertEquals(199, (header[7].toInt() and 0xFF) or ((header[8].toInt() and 0xFF) shl 8))
        assertArrayEquals(icc, parts[1].second)
        assertArrayEquals(chunks(webp)[0].second, parts[2].second)
        // Once is enough.
        assertNull(WebpIcc.embed(out, icc))
    }

    @Test
    fun aLossyWebpTakesItsSizeFromItsFrame() {
        val webp = FakeImages().webp(pixels(4, 8, 64, 48), lossless = false, quality = 90)!!
        val parts = chunks(WebpIcc.embed(webp, ByteArray(4))!!)
        assertEquals(listOf("VP8X", "ICCP", "VP8 "), parts.map { it.first })
        assertEquals(63, parts[0].second[4].toInt() and 0xFF)
        assertEquals(47, parts[0].second[7].toInt() and 0xFF)
    }
}
