package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** [Decoders] against the real platform decoders (Robolectric native graphics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodersPlatformTest {

    private fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(format, 90, out)
        return out.toByteArray()
    }

    @Test
    fun sizeFromPlatformHeaderPass() {
        repeat(3) {
            assertArrayEquals(intArrayOf(40, 30), Decoders.size(encoded(40, 30, Bitmap.CompressFormat.JPEG)))
        }
        assertArrayEquals(intArrayOf(41, 29), Decoders.size(encoded(41, 29, Bitmap.CompressFormat.WEBP_LOSSLESS)))
        assertNull(Decoders.size(ByteArray(100) { 7 }))
    }

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    // The host runtime cannot open file-backed ImageDecoder sources, so file decodes
    // run on API 27, where they go through BitmapFactory.

    @Test
    @Config(sdk = [27])
    fun filesDecodeAndSize() {
        val jpeg = file(encoded(40, 30, Bitmap.CompressFormat.JPEG))
        val bitmap = Decoders.decode(jpeg, 0, 0)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
        assertArrayEquals(intArrayOf(40, 30), Decoders.size(jpeg))
    }

    @Test
    @Config(sdk = [27])
    fun fileSampledToTarget() {
        val bitmap = Decoders.decode(file(encoded(400, 300, Bitmap.CompressFormat.JPEG)), 100, 75)
        assertEquals(100, bitmap.width)
        assertEquals(75, bitmap.height)
    }

    @Test
    fun fileSizesFromHeaders() {
        assertArrayEquals(intArrayOf(41, 29), Decoders.size(file(TestImages.png(41, 29))))
        // The JPEG XL SizeHeader of a 37x23 image; no pixel data needed for the size.
        assertArrayEquals(intArrayOf(37, 23), Decoders.size(file(hex("ff0ab00048e0271300130801"))))
    }

    @Test
    @Config(sdk = [27])
    fun fileSizePastThePrefix() {
        // Image data starts beyond the prefix read for sizes, so the platform reads it.
        val text = "k\u0000".toByteArray() + ByteArray(ImageHeaders.PREFIX_BYTES) { 'x'.code.toByte() }
        val png = TestImages.png(41, 29, extra = listOf("tEXt" to text))
        assertArrayEquals(intArrayOf(41, 29), Decoders.size(file(png)))
    }

    @Test
    fun missingFileHasNoSize() {
        assertNull(Decoders.size(File(temp.root, "absent.png")))
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun pngWithExifSizedLikeItsDecode() {
        // Big-endian TIFF with one entry: Orientation = 6 (rotate 90 degrees clockwise).
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0)
        val png = TestImages.png(40, 30, extra = listOf("eXIf" to tiff))
        val bitmap = Decoders.decode(png, 0, 0)
        assertArrayEquals(intArrayOf(bitmap.width, bitmap.height), Decoders.size(png))
        assertArrayEquals(intArrayOf(40, 30), Decoders.size(TestImages.png(40, 30)))
    }

    @Test
    fun sixteenBitImageDecodesToEightBit() {
        val bitmap = Decoders.decode(TestImages.png(40, 30, bitDepth = 16), 0, 0)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertEquals(40 * 30 * 4, bitmap.byteCount)
        // Every channel of the fixture is 0x8080 of 0xFFFF.
        assertEquals(0x80.toDouble(), Color.red(bitmap.getPixel(5, 5)).toDouble(), 2.0)
    }

    @Test
    fun undecodableBytesFailForGood() {
        val e = assertThrows(DecodeException::class.java) { Decoders.decode(ByteArray(100) { 7 }, 0, 0) }
        assertEquals("Could not decode this unrecognized image", e.message)
        assertFalse(e.transient)
    }

    @Test
    fun truncatedImageKeepsDecodedPart() {
        val png = TestImages.png(40, 30)
        val bitmap = Decoders.decode(png.copyOf(png.size - 20), 0, 0)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
    }

    @Test
    fun corruptImageKeepsDecodedPart() {
        // Garbles the compressed pixel data after the IDAT chunk header.
        val png = TestImages.png(40, 30).also { for (i in 60 until 70) it[i] = 0x55 }
        val bitmap = Decoders.decode(png, 0, 0)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
    }
}
