package com.infinity.comicreader.reader

import com.infinity.comicreader.compact.PageProbe
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page info's formats, from synthetic pages and the AVIF fixtures. */
class PageFormatTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("avif/$name")!!.use { it.readBytes() }

    /** A WebP of [chunks] (type, data), as RIFF lays them out. */
    private fun webp(vararg chunks: Pair<String, Int>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for ((type, size) in chunks) {
            body.write(type.toByteArray(Charsets.US_ASCII))
            body.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0))
            body.write(ByteArray(size + (size and 1)))
        }
        val b = body.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + byteArrayOf(b.size.toByte(), (b.size shr 8).toByte(), 0, 0) + b
    }

    @Test
    fun jpegIsLossyAndMayBeProgressive() {
        assertEquals(PageFormat(ImageKind.JPEG, false), PageFormat.of(TestImages.jpegHead(40, 60)))
        assertEquals(PageFormat(ImageKind.JPEG, false, progressive = true), PageFormat.of(TestImages.jpegHead(40, 60, sof = 0xC2)))
    }

    @Test
    fun pngBmpAndTiffAreLosslessAndAnAnimatedPngSaysSo() {
        assertEquals(PageFormat(ImageKind.PNG, true), PageFormat.of(TestImages.png(4, 4)))
        val animated = TestImages.png(4, 4, extra = listOf("acTL" to ByteArray(8)))
        assertEquals(PageFormat(ImageKind.PNG, true, animated = true), PageFormat.of(animated))
        assertEquals(PageFormat(ImageKind.BMP, true), PageFormat.of(TestImages.bmp(4, 4, 0)))
    }

    @Test
    fun webpTellsLosslessFromLossyByItsImageChunk() {
        assertEquals(PageFormat(ImageKind.WEBP, true), PageFormat.of(webp("VP8X" to 10, "ICCP" to 3, "VP8L" to 20)))
        assertEquals(PageFormat(ImageKind.WEBP, false), PageFormat.of(webp("VP8 " to 21)))
        assertEquals(PageFormat(ImageKind.WEBP, null, animated = true), PageFormat.of(webp("VP8X" to 10, "ANIM" to 6, "ANMF" to 40)))
    }

    @Test
    fun aWebpChunkLongerThanTheFileEndsTheWalk() {
        val bytes = webp("VP8X" to 10, "XTRA" to 16)
        // The second chunk's length: 0x7FFFFFFF.
        val at = 12 + 8 + 10 + 4
        for (i in 0 until 4) bytes[at + i] = if (i == 3) 0x7F else 0xFF.toByte()
        assertEquals(PageFormat(ImageKind.WEBP, null), PageFormat.of(bytes))
    }

    @Test
    fun avifTellsItsLayers() {
        assertEquals(0, PageFormat.of(fixture("single.avif")).layers)
        for (name in listOf("layered.avif", "layered-gray.avif", "layered-tiny.avif")) {
            val format = PageFormat.of(fixture(name))
            assertEquals(name, ImageKind.AVIF, format.kind)
            assertTrue("$name: ${format.layers}", format.layers >= 2)
        }
        // Cut short of its metadata, a layered file tells none.
        assertEquals(0, AvifPreview.layers(fixture("layered.avif").copyOf(64)))
    }

    @Test
    fun aLosslessAvifIsLosslessWithAlphaAndInGray() {
        // avifenc -l: 4:4:4, identity matrix; its alpha plane and a gray image are monochrome (gray: matrix 6).
        for (name in listOf("lossless.avif", "lossless-alpha.avif", "lossless-gray.avif")) {
            assertEquals(name, true, PageFormat.of(fixture(name)).lossless)
        }
        // Compaction's check, which wants decodes equal to the samples, takes no monochrome plane.
        assertEquals(false, PageProbe.avifLossless(fixture("lossless-alpha.avif")))
        assertEquals(false, PageProbe.avifLossless(fixture("lossless-gray.avif")))
        assertEquals(false, PageFormat.of(fixture("single.avif")).lossless)
    }

    @Test
    fun unknownBytesTellNothing() {
        assertEquals(PageFormat(null, null), PageFormat.of(ByteArray(16)))
        assertEquals(PageFormat(ImageKind.GIF, null), PageFormat.of(TestImages.gif(2, 2, 0)))
    }
}
