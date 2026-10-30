package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class ImageHeadersTest {

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun be32(v: Long) = bytes((v shr 24).toInt(), (v shr 16).toInt(), (v shr 8).toInt(), v.toInt())

    // ------------------------------------------------------------------ PNG

    private fun png(width: Long, height: Long): ByteArray {
        val ihdr = "IHDR".toByteArray() + be32(width) + be32(height) + bytes(8, 6, 0, 0, 0)
        val crc = CRC32().apply { update(ihdr) }.value
        return bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + ihdr + be32(crc)
    }

    @Test
    fun pngSizeFromIhdr() {
        assertArrayEquals(intArrayOf(37, 23), ImageHeaders.pngSize(png(37, 23)))
        assertArrayEquals(intArrayOf(1988, 3056), ImageHeaders.pngSize(png(1988, 3056)))
    }

    @Test
    fun pngRejectsBadHeaders() {
        assertNull(ImageHeaders.pngSize(png(37, 23).copyOf(20)))
        assertNull(ImageHeaders.pngSize(png(0, 23)))
        assertNull(ImageHeaders.pngSize(png(0x80000000L, 23)))
        val noIhdr = png(37, 23).also { it[12] = 'X'.code.toByte() }
        assertNull(ImageHeaders.pngSize(noIhdr))
        assertNull(ImageHeaders.pngSize(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun pngExifBeforeImageData() {
        assertFalse(ImageHeaders.pngMayHaveExif(TestImages.png(4, 3)))
        assertFalse(ImageHeaders.pngMayHaveExif(TestImages.png(4, 3, extra = listOf("tEXt" to "a\u0000b".toByteArray()))))
        assertTrue(ImageHeaders.pngMayHaveExif(TestImages.png(4, 3, extra = listOf("eXIf" to ByteArray(8)))))
        // Unknown when the bytes end before the image data.
        assertTrue(ImageHeaders.pngMayHaveExif(png(37, 23)))
    }

    // ------------------------------------------------------------------ JPEG XL

    // First bytes of tiny generated JPEG XL codestreams.
    @Test
    fun jxlSizeFromEncodedCodestreams() {
        assertArrayEquals(intArrayOf(37, 23), ImageHeaders.jxlSize(hex("ff0ab00048e0271300130801")))
        assertArrayEquals(intArrayOf(64, 48), ImageHeaders.jxlSize(hex("ff0acbf09309001308010000")))
        assertArrayEquals(intArrayOf(800, 600), ImageHeaders.jxlSize(hex("ff0aba12c34f260013080100")))
        assertArrayEquals(intArrayOf(600, 800), ImageHeaders.jxlSize(hex("ff0afa18e84ae02713001308")))
        assertArrayEquals(intArrayOf(3001, 4500), ImageHeaders.jxlSize(hex("ff0a9a8c0877e12713001308")))
        assertArrayEquals(intArrayOf(1000, 1), ImageHeaders.jxlSize(hex("ff0a0080ce077e3201001308")))
    }

    @Test
    fun jxlTransposingOrientationSwapsSize() {
        // 37x23 pixels stored with orientation 6 (rotate 90 degrees clockwise).
        assertArrayEquals(intArrayOf(23, 37), ImageHeaders.jxlSize(hex("ff0ab0004858f8c909001388")))
    }

    /** Writes JPEG XL header fields, least significant bit first. */
    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        fun u(value: Long, n: Int) = apply { for (k in 0 until n) bits += (value shr k) and 1L == 1L }
        fun flag(on: Boolean) = u(if (on) 1 else 0, 1)
        fun bytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8 + 1)
            bits.forEachIndexed { i, b -> if (b) out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte() }
            return out
        }
    }

    private fun codestream(body: BitWriter) = bytes(0xFF, 0x0A) + body.bytes()

    @Test
    fun jxlFixedRatioAndSmallSizes() {
        // Not small, height 1080 via the 13-bit distribution, ratio 16:9, default metadata.
        val wide = BitWriter().flag(false).u(1, 2).u(1079, 13).u(5, 3).flag(true)
        assertArrayEquals(intArrayOf(1920, 1080), ImageHeaders.jxlSize(codestream(wide)))
        // Small: 8 * (31 + 1) high, ratio 2:1.
        val small = BitWriter().flag(true).u(31, 5).u(7, 3).flag(true)
        assertArrayEquals(intArrayOf(512, 256), ImageHeaders.jxlSize(codestream(small)))
        // The largest height at ratio 2:1 is wider than a bitmap can be.
        val huge = BitWriter().flag(false).u(3, 2).u((1L shl 30) - 1, 30).u(7, 3).flag(true)
        assertNull(ImageHeaders.jxlSize(codestream(huge)))
    }

    @Test
    fun jxlInsideContainer() {
        val stream = hex("ff0ab00048e0271300130801")
        val signature = hex("0000000c4a584c200d0a870a")
        val ftyp = be32(20) + "ftypjxl ".toByteArray() + be32(0) + "jxl ".toByteArray()
        val exif = be32(12) + "Exif".toByteArray() + be32(0)

        val jxlc = signature + ftyp + exif + be32(8L + stream.size) + "jxlc".toByteArray() + stream
        assertArrayEquals(intArrayOf(37, 23), ImageHeaders.jxlSize(jxlc))

        val jxlp = signature + ftyp + be32(12L + stream.size) + "jxlp".toByteArray() + be32(0) + stream
        assertArrayEquals(intArrayOf(37, 23), ImageHeaders.jxlSize(jxlp))

        // A 64-bit box size: 1, then the real size after the type.
        val large = ByteArrayOutputStream().apply {
            write(signature)
            write(be32(1)); write("jxlc".toByteArray()); write(be32(0)); write(be32(16L + stream.size))
            write(stream)
        }.toByteArray()
        assertArrayEquals(intArrayOf(37, 23), ImageHeaders.jxlSize(large))
    }

    @Test
    fun jxlRejectsTruncatedOrForeignData() {
        assertNull(ImageHeaders.jxlSize(hex("ff0ab0")))
        assertNull(ImageHeaders.jxlSize(hex("ff0a")))
        assertNull(ImageHeaders.jxlSize(hex("ffd8ffe0")))
        // A container that ends before any codestream box.
        assertNull(ImageHeaders.jxlSize(hex("0000000c4a584c200d0a870a")))
        // A box claiming to be shorter than its own header.
        assertNull(ImageHeaders.jxlSize(hex("0000000c4a584c200d0a870a00000004667479706a786c20")))
    }

    // ------------------------------------------------------------------ JPEG

    private fun jpeg(w: Int, h: Int, orientation: Int? = null, little: Boolean = true, sof: Int = 0xC0) =
        TestImages.jpegHead(w, h, orientation, little, sof)

    @Test
    fun jpegProgressiveFromFrameHeader() {
        assertFalse(ImageHeaders.jpegProgressive(jpeg(640, 480)))
        assertTrue(ImageHeaders.jpegProgressive(jpeg(640, 480, sof = 0xC2)))
        assertTrue(ImageHeaders.jpegProgressive(jpeg(640, 480, orientation = 6, sof = 0xC2)))
        assertFalse(ImageHeaders.jpegProgressive(ByteArray(8)))
    }

    @Test
    fun jpegSizeFromFrameHeader() {
        assertArrayEquals(intArrayOf(1988, 3056), ImageHeaders.jpegSize(jpeg(1988, 3056)))
        assertArrayEquals(intArrayOf(640, 480), ImageHeaders.jpegSize(jpeg(640, 480, sof = 0xC2)))
    }

    @Test
    fun jpegExifRotationSwapsTheSides() {
        assertArrayEquals(intArrayOf(3056, 1988), ImageHeaders.jpegSize(jpeg(1988, 3056, orientation = 6)))
        assertArrayEquals(intArrayOf(3056, 1988), ImageHeaders.jpegSize(jpeg(1988, 3056, orientation = 8, little = false)))
        assertArrayEquals(intArrayOf(1988, 3056), ImageHeaders.jpegSize(jpeg(1988, 3056, orientation = 3)))
    }

    @Test
    fun jpegOrientationIsReadAsStored() {
        assertEquals(1, ImageHeaders.jpegOrientation(jpeg(1988, 3056)))
        for (o in 1..8) assertEquals(o, ImageHeaders.jpegOrientation(jpeg(1988, 3056, orientation = o, little = o % 2 == 0)))
        assertNull(ImageHeaders.jpegOrientation(png(37, 23)))
    }

    @Test
    fun jpegRejectsTruncatedOrForeignData() {
        val whole = jpeg(1988, 3056)
        assertNull(ImageHeaders.jpegSize(whole.copyOf(whole.size - 30)))
        assertNull(ImageHeaders.jpegSize(png(37, 23)))
        assertNull(ImageHeaders.jpegSize(jpeg(1988, 0)))
    }
}
