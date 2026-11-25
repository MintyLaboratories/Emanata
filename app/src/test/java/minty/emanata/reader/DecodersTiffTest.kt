package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** TIFF through [Decoders]: the built-in decoder, with JPEG chunks on the platform's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodersTiffTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/tiff/$name")?.use { it.readBytes() } ?: error("missing fixture $name")

    private fun rgb(x: Int, y: Int) = intArrayOf((x * 6) and 0xFF, (y * 10) and 0xFF, (x * 3 + y * 5) and 0xFF)

    private fun assertNear(name: String, bitmap: Bitmap, tolerance: Int) {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            val got = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            val want = rgb(x, y)
            for (c in 0..2) assertTrue("$name at $x,$y: ${got.toList()} vs ${want.toList()}", abs(got[c] - want[c]) <= tolerance)
        }
    }

    @Test
    fun sniffedAndSizedFromHeaders() {
        val bytes = fixture("rgb-lzw-predictor-msb.tif")
        assertEquals(ImageKind.TIFF, ImageKind.sniff(bytes))
        assertEquals(ImageKind.TIFF, ImageKind.sniff(fixture("rgb-lzw-bigtiff.tif")))
        assertArrayEquals(intArrayOf(37, 23), Decoders.size(bytes))
        val file = temp.newFile("page.tif").apply { writeBytes(bytes) }
        assertArrayEquals(intArrayOf(37, 23), Decoders.size(file))
    }

    @Test
    fun losslessDecodeIsExact() {
        val bitmap = Decoders.decode(fixture("rgb-deflate-tiles.tif"), Target.NATIVE)
        assertEquals(37, bitmap.width)
        assertEquals(23, bitmap.height)
        assertFalse(bitmap.hasAlpha())
        assertNear("tiles", bitmap, 0)
    }

    @Test
    fun fileDecodeAndResample() {
        val file = temp.newFile("page.tif").apply { writeBytes(fixture("rgb-lzw-planar.tif")) }
        assertNear("file", Decoders.decode(file, Target.NATIVE), 0)
        val small = Decoders.decode(file, Target(20, 0))
        assertEquals(20, small.width)
        assertEquals(12, small.height)
    }

    @Test
    fun capSamplesBeforeResampling() {
        // 37 x 23 = 851 pixels over a cap of 300: every second pixel of every second row.
        val bitmap = Decoders.decode(fixture("rgb-none-strips.tif"), Target(capPixels = 300))
        assertEquals(19, bitmap.width)
        assertEquals(12, bitmap.height)
        val p = bitmap.getPixel(3, 2)
        assertEquals(rgb(6, 4).toList(), listOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF))
    }

    @Test
    fun jpegInTiff() {
        for (name in listOf("jpeg-rgb.tif", "jpeg-ycbcr.tif", "jpeg-tiles.tif")) {
            val bitmap = Decoders.decode(fixture(name), Target.NATIVE)
            assertEquals(37, bitmap.width)
            assertEquals(23, bitmap.height)
            assertNear(name, bitmap, 16)
        }
    }

    @Test
    fun jpegInTiffSampledUnderCap() {
        // 37 x 23 over a cap of 300: libjpeg-turbo halves each strip, averaging 2 x 2 blocks.
        val bitmap = Decoders.decode(fixture("jpeg-rgb.tif"), Target(capPixels = 300))
        assertEquals(19, bitmap.width)
        assertEquals(12, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            val got = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            val want = rgb(2 * x, 2 * y)
            for (c in 0..2) assertTrue("at $x,$y: ${got.toList()} vs ${want.toList()}", abs(got[c] - want[c]) <= 24)
        }
    }

    @Test
    fun unsupportedCompressionSaysWhy() {
        val bytes = fixture("rgb-none-strips.tif")
        // The Compression entry's value (big-endian SHORT) becomes 4, CCITT Group 4.
        val ifd = ((bytes[4].toInt() and 0xFF) shl 24) or ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val n = ((bytes[ifd].toInt() and 0xFF) shl 8) or (bytes[ifd + 1].toInt() and 0xFF)
        val entry = (0 until n).map { ifd + 2 + it * 12 }.first { ((bytes[it].toInt() and 0xFF) shl 8 or (bytes[it + 1].toInt() and 0xFF)) == 259 }
        bytes[entry + 9] = 4
        val e = org.junit.Assert.assertThrows(DecodeException::class.java) { Decoders.decode(bytes, Target.NATIVE) }
        assertEquals("TIFF compression CCITT G4 is not supported", e.message)
    }
}
