package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
import kotlin.math.abs

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
        val bitmap = Decoders.decode(jpeg, Target.NATIVE)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
        assertArrayEquals(intArrayOf(40, 30), Decoders.size(jpeg))
    }

    @Test
    @Config(sdk = [27])
    fun fileResampledToTarget() {
        val bitmap = Decoders.decode(file(encoded(400, 300, Bitmap.CompressFormat.JPEG)), Target(150, 150))
        assertEquals(150, bitmap.width)
        assertEquals(113, bitmap.height)
    }

    /** A [w] x [h] PNG with a diagonal color ramp and a soft alpha edge. */
    private fun ramp(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val alpha = if (x < 4) 64 * x else 255
            bitmap.setPixel(x, y, Color.argb(alpha, (x * 7) and 0xFF, (y * 5) and 0xFF, ((x + y) * 3) and 0xFF))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun decodeIsFullResolutionThenLanczos() {
        val png = ramp(90, 60)
        val full = pixels(Decoders.decode(png, Target.NATIVE))
        for (target in listOf(Target(37, 0), Target(200, 200), Target(40, 40, Fit.COVER), Target(31, 77, Fit.STRETCH))) {
            val plan = target.plan(90.0, 60.0)
            val bitmap = Decoders.decode(png, target)
            assertEquals(plan.width, bitmap.width)
            assertEquals(plan.height, bitmap.height)
            val expected = Lanczos.resample(full, 90, 60, Lanczos.Window(plan.x, plan.y, plan.spanW, plan.spanH), plan.width, plan.height)
            val actual = pixels(bitmap)
            for (i in expected.indices) {
                // The bitmap stores premultiplied 8-bit values, so a translucent color
                // reads back within 255 / alpha codes; an opaque one exactly.
                val alpha = expected[i] ushr 24
                val tolerance = if (alpha == 255) 0 else 255 / alpha.coerceAtLeast(1) + 1
                for (s in 0..16 step 8) {
                    val d = abs(((expected[i] shr s) and 0xFF) - ((actual[i] shr s) and 0xFF))
                    assertTrue("$target pixel $i differs by $d", alpha == 0 || d <= tolerance)
                }
                assertTrue(abs(alpha - (actual[i] ushr 24)) <= 1)
            }
        }
    }

    @Test
    fun autoCropTrimsBeforeScaling() {
        // A black 120 x 160 block on a white 200 x 200 page.
        val page = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in 20 until 180) for (x in 40 until 160) page.setPixel(x, y, Color.BLACK)
        val out = ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val cropped = Decoders.decode(out, Target(0, 400, autoCrop = true))
        // The crop keeps a small margin around the content (128 x 168), then fills the height.
        assertEquals(400, cropped.height)
        assertEquals(305, cropped.width)
        assertEquals(Color.BLACK, cropped.getPixel(cropped.width / 2, 200))
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
        val bitmap = Decoders.decode(png, Target.NATIVE)
        assertArrayEquals(intArrayOf(bitmap.width, bitmap.height), Decoders.size(png))
        assertArrayEquals(intArrayOf(40, 30), Decoders.size(TestImages.png(40, 30)))
    }

    @Test
    fun sixteenBitImageDecodesToEightBit() {
        val bitmap = Decoders.decode(TestImages.png(40, 30, bitDepth = 16), Target.NATIVE)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertEquals(40 * 30 * 4, bitmap.byteCount)
        // Every channel of the fixture is 0x8080 of 0xFFFF.
        assertEquals(0x80.toDouble(), Color.red(bitmap.getPixel(5, 5)).toDouble(), 2.0)
    }

    @Test
    fun oversizedJxlRefusedBeforeDecoding() {
        // SizeHeader only: 100000 x 100000 pixels.
        val e = assertThrows(DecodeException::class.java) { Decoders.decode(hex("ff0afc342c0100"), Target(100, 100)) }
        assertEquals("JXL image is too large (100000 x 100000)", e.message)
        assertFalse(e.transient)
    }

    @Test
    fun jxlGoesToJxlCoder() {
        // The host has no jxl-coder native library, so reaching it fails this way.
        val e = assertThrows(DecodeException::class.java) { Decoders.decode(hex("ff0ab00048e0271300130801"), Target.NATIVE) }
        assertEquals("No JXL decoder on this device", e.message)
    }

    @Test
    fun undecodableBytesFailForGood() {
        val e = assertThrows(DecodeException::class.java) { Decoders.decode(ByteArray(100) { 7 }, Target.NATIVE) }
        assertEquals("Could not decode this unrecognized image", e.message)
        assertFalse(e.transient)
    }

    @Test
    fun truncatedImageKeepsDecodedPart() {
        val png = TestImages.png(40, 30)
        val bitmap = Decoders.decode(png.copyOf(png.size - 20), Target.NATIVE)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
    }

    @Test
    fun corruptImageKeepsDecodedPart() {
        // Garbles the compressed pixel data after the IDAT chunk header.
        val png = TestImages.png(40, 30).also { for (i in 60 until 70) it[i] = 0x55 }
        val bitmap = Decoders.decode(png, Target.NATIVE)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
    }

    /** Fine gray stripes, where linear light visibly changes the mean. */
    private fun stripes(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
    }

    @Test
    fun linearLightOnlyShrinks() {
        fun fit(w: Int, linear: Boolean) = pixels(Decoders.fit(stripes(20, 20), Target(w, 0, linear = linear)))
        assertArrayEquals(fit(50, false), fit(50, true))
        assertFalse(fit(7, false).contentEquals(fit(7, true)))
    }

    @Test
    fun canceledResampleIsNotADecodeFailure() {
        val png = encoded(400, 300, Bitmap.CompressFormat.PNG)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            Cancellation.during({ true }) { Decoders.decode(png, Target(100, 0)) }
        }
        assertEquals(100, Decoders.decode(png, Target(100, 0)).width)
    }

    /** A [w] x [h] white page with a black block over columns [x0, x1) and rows [y0, y1), as PNG. */
    private fun block(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): ByteArray {
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in y0 until y1) for (x in x0 until x1) page.setPixel(x, y, Color.BLACK)
        return ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun measuringHandsOnWhatAutoCropFound() {
        var found: Triple<Box, Int, Int>? = null
        Decoders.measuring({ box, w, h -> found = Triple(box, w, h) }) {
            Decoders.decode(block(200, 200, 40, 20, 160, 180), Target(16, 16, autoCrop = true))
        }
        val (box, w, h) = found!!
        assertEquals(200, w)
        assertEquals(200, h)
        // The content plus the crop's small margin.
        assertEquals(listOf(36, 16, 164, 184), box.pixels(w, h).toList())
        // Without auto-crop there is nothing to hand on.
        found = null
        Decoders.measuring({ b, x, y -> found = Triple(b, x, y) }) { Decoders.decode(block(200, 200, 40, 20, 160, 180), Target(16, 16)) }
        assertEquals(null, found)
    }

    @Test
    fun aBoxCropsInPlaceOfTheContent() {
        val out = Decoders.decode(block(200, 200, 40, 20, 160, 180), Target(autoCrop = true, box = Box(0.0, 0.0, 0.5, 1.0)))
        assertEquals(100, out.width)
        assertEquals(200, out.height)
        assertEquals(Color.WHITE, out.getPixel(10, 10))
    }

    @Test
    fun aSpreadInOneFileIsCroppedBeforeItIsSplit() {
        // Content 40..360 of 400: the halves meet at 200, the middle of the content, not of the margins.
        val spread = block(400, 200, 40, 20, 360, 180)
        val left = Decoders.decode(spread, Target(autoCrop = true, half = Half.LEFT))
        val right = Decoders.decode(spread, Target(autoCrop = true, half = Half.RIGHT))
        assertEquals(left.width, right.width)
        assertEquals(left.height, right.height)
        assertEquals(Color.BLACK, left.getPixel(left.width - 1, left.height / 2))
        assertEquals(Color.BLACK, right.getPixel(0, right.height / 2))
    }

    /** A [width] x [height] white PNG with a black block over columns [x0, x1) and rows [y0, y1). */
    private fun blocked(width: Int, height: Int, x0: Int, y0: Int, x1: Int, y1: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        android.graphics.Canvas(bitmap).drawRect(
            x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(),
            android.graphics.Paint().apply { color = Color.BLACK }
        )
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun aSampledMeasureTellsTheCropInTermsOfTheFullImage() {
        val png = blocked(800, 1200, 100, 150, 700, 1050)
        var found: Triple<Box, Int, Int>? = null
        // Past the cap: decoded at half size each way.
        Decoders.measuring({ box, w, h -> found = Triple(box, w, h) }) {
            Decoders.decode(png, Target(16, 16, autoCrop = true, capPixels = 300_000))
        }
        val (box, w, h) = found!!
        assertEquals(800, w)
        assertEquals(1200, h)
        val px = box.pixels(w, h)
        // Never inside the block, and its margin about what a full-size measure leaves ([96, 146, 704, 1054]).
        assertTrue(px.contentToString(), px[0] in 86..99 && px[1] in 136..149 && px[2] in 701..714 && px[3] in 1051..1064)
    }

    @Test
    fun aFullSizeMeasureTellsTheExactCrop() {
        val png = blocked(800, 1200, 100, 150, 700, 1050)
        var found: Triple<Box, Int, Int>? = null
        Decoders.measuring({ box, w, h -> found = Triple(box, w, h) }) {
            Decoders.decode(png, Target(16, 16, autoCrop = true))
        }
        val (box, w, h) = found!!
        assertArrayEquals(intArrayOf(800, 1200), intArrayOf(w, h))
        // The block with auto-crop's margin of 4 pixels.
        assertArrayEquals(intArrayOf(96, 146, 704, 1054), box.pixels(w, h))
    }
}
