package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random

/** One-channel files, with and without a gray color profile, decode to pages found gray ([GrayPages.isGray]) through [Decoders]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodedGrayPagesTest {

    /** A [W] x [H] ramp with noise over it, as a screentone scan is, one byte per pixel. */
    private val grays: ByteArray = Random(5).let { r ->
        ByteArray(W * H) { i -> (i % W * 255 / (W - 1) + r.nextInt(-40, 41)).coerceIn(0, 255).toByte() }
    }

    private fun deflated(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray() + data
        val crc = CRC32().apply { update(body) }.value.toInt()
        return ByteBuffer.allocate(4 + body.size + 4).putInt(data.size).put(body).putInt(crc).array()
    }

    /** [grays] as an 8-bit one-channel PNG, with an iCCP chunk of [icc] when given. */
    private fun grayPng(icc: ByteArray? = null): ByteArray {
        val header = ByteBuffer.allocate(13).putInt(W).putInt(H).put(8).put(0).put(0).put(0).put(0).array()
        val rows = ByteArrayOutputStream().apply {
            for (y in 0 until H) {
                write(0)
                write(grays, y * W, W)
            }
        }.toByteArray()
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            chunk("IHDR", header) +
            (icc?.let { chunk("iCCP", "gray".toByteArray() + byteArrayOf(0, 0) + deflated(it)) } ?: ByteArray(0)) +
            chunk("IDAT", deflated(rows)) +
            chunk("IEND", ByteArray(0))
    }

    /** [GRAY_JPEG], with an APP2 ICC_PROFILE segment of [icc] after APP0 when given. */
    private fun grayJpeg(icc: ByteArray? = null): ByteArray {
        val jpeg = Base64.getDecoder().decode(GRAY_JPEG)
        if (icc == null) return jpeg
        val payload = "ICC_PROFILE".toByteArray() + byteArrayOf(0, 1, 1) + icc
        val segment = ByteBuffer.allocate(4 + payload.size).put(0xFF.toByte()).put(0xE2.toByte()).putShort((payload.size + 2).toShort()).put(payload).array()
        // SOI (2), then APP0: its marker (2) and its length, which counts itself.
        val app0 = 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        return jpeg.copyOfRange(0, app0) + segment + jpeg.copyOfRange(app0, jpeg.size)
    }

    /** A minimal ICC v2 gray monitor profile whose tone curve is [gamma] (u8Fixed8). */
    private fun grayProfile(gamma: Int): ByteArray {
        val curve = ByteBuffer.allocate(16).put("curv".toByteArray()).putInt(0).putInt(1).putShort(gamma.toShort()).array()
        val white = ByteBuffer.allocate(20).put("XYZ ".toByteArray()).putInt(0).putInt(D50_X).putInt(D50_Y).putInt(D50_Z).array()
        val tags = listOf("kTRC" to curve, "wtpt" to white)
        var at = 128 + 4 + 12 * tags.size
        val table = ByteBuffer.allocate(4 + 12 * tags.size).putInt(tags.size)
        for ((sig, data) in tags) {
            table.put(sig.toByteArray()).putInt(at).putInt(data.size)
            at += data.size
        }
        val header = ByteBuffer.allocate(128).putInt(at).putInt(0).putInt(0x02100000)
            .put("mntrGRAYXYZ ".toByteArray()).put(ByteArray(12)).put("acsp".toByteArray())
            .putInt(0).putInt(0).putInt(0).putInt(0).putLong(0).putInt(0)
            .putInt(D50_X).putInt(D50_Y).putInt(D50_Z)
            .array()
        return header + table.array() + tags.fold(ByteArray(0)) { all, t -> all + t.second }
    }

    private fun decoded(bytes: ByteArray): Bitmap = Decoders.decode(bytes, Target.NATIVE)

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun oneChannelPngAndJpegDecodeGray() {
        val png = decoded(grayPng())
        assertEquals(0, GrayPages.spread(png))
        assertArrayEquals("no profile: read as is", grays.map { 0xFF000000.toInt() or ((it.toInt() and 0xFF) * 0x010101) }.toIntArray(), pixels(png))
        assertEquals(0, GrayPages.spread(decoded(grayJpeg())))
    }

    @Test
    fun oneChannelFilesWithAGrayProfileDecodeGray() {
        for (gamma in intArrayOf(GAMMA_1_0, GAMMA_2_2)) {
            val icc = grayProfile(gamma)
            for (page in listOf(decoded(grayPng(icc)), decoded(grayJpeg(icc)))) {
                assertTrue("gamma ${'$'}gamma", GrayPages.spread(page) <= GrayPages.TOLERANCE)
                assertTrue("gamma ${'$'}gamma", GrayPages.isGray(page))
            }
        }
        // The profile was applied: linear midtones come out brighter.
        assertFalse(pixels(decoded(grayJpeg())).contentEquals(pixels(decoded(grayJpeg(grayProfile(GAMMA_1_0))))))
    }

    @Test
    @Config(sdk = [27])
    fun oneChannelFilesWithAGrayProfileDecodeGrayBeforeImageDecoder() {
        val icc = grayProfile(GAMMA_2_2)
        assertTrue(GrayPages.isGray(decoded(grayPng(icc))))
        assertTrue(GrayPages.isGray(decoded(grayJpeg(icc))))
    }

    @Test
    fun aColorPixelInAColorFileIsNotGray() {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF808080.toInt())
            setPixel(5, 5, 0xFF204060.toInt())
        }.compress(Bitmap.CompressFormat.PNG, 100, out)
        assertFalse(GrayPages.isGray(decoded(out.toByteArray())))
    }

    private companion object {
        const val W = 40
        const val H = 30

        /** Tone curve gammas as u8Fixed8. */
        const val GAMMA_1_0 = 0x0100
        const val GAMMA_2_2 = 0x0233

        /** D50 white as s15Fixed16. */
        const val D50_X = 0xF6D6
        const val D50_Y = 0x10000
        const val D50_Z = 0xD32D

        /** A 24 x 16 one-channel baseline JPEG (JFIF), quality 90: a ramp with noise over it. */
        const val GRAY_JPEG =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/wAALCAAQABgBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/APhP4UWk15NaWRZr2O4vHjmMIVsKMF2aQ5ZclC20feVM4BOR90fAewiu7a3isVljWAqkkCPI6yRgsbgqzHILBkBVkzwNo+avur4R+ZgyPbRy3A+SOSKRFFwwPzOcFtzIcgs3PKE88D8OPhVbQaherEkqw/uDsigieYycPxjAbPluVGQVbav3vuV9y/B+Y6bqqS3F/JBeXe2SC3eMOGjJUg/MpKcKBk/cMZzndivuz4XxuLe0eeECCZFmgjRmjkjblmRwFBd1HmAZwMRDAznH/9k="
    }
}
