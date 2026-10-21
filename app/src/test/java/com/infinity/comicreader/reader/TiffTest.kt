package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Tiff] against files written by libtiff (through ImageMagick, `tools/tiff-fixtures.py`).
 * The patterns below are the formulas that script draws.
 */
class TiffTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/tiff/$name")?.use { it.readBytes() } ?: error("missing fixture $name")

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun rgb(x: Int, y: Int) = argb((x * 6) and 0xFF, (y * 10) and 0xFF, (x * 3 + y * 5) and 0xFF)

    private fun busy(x: Int, y: Int) = argb((x * x + y * 7) and 0xFF, (x * y + 3 * x) and 0xFF, (x xor (y * 3)) and 0xFF)

    private fun gray(x: Int, y: Int) = (x * 4 + y * 4).let { argb(it, it, it) }

    private val quadColors = intArrayOf(argb(200, 30, 30), argb(30, 200, 30), argb(30, 30, 200), argb(240, 240, 40))

    private fun quad(x: Int, y: Int) = quadColors[(if (x >= 18) 1 else 0) + 2 * (if (y >= 11) 1 else 0)]

    /** A decoded image: [width] x [height] ARGB. */
    private class Image(val width: Int, val height: Int, val pixels: IntArray) {
        operator fun get(x: Int, y: Int) = pixels[y * width + x]
    }

    private fun decode(bytes: ByteArray, sample: Int = 1, jpeg: Tiff.JpegDecoder = Tiff.JpegDecoder { error("no JPEG expected") }): Image {
        val input = Tiff.ByteInput(bytes)
        val page = Tiff.page(input)
        val w = (page.width + sample - 1) / sample
        val h = (page.height + sample - 1) / sample
        val pixels = IntArray(w * h)
        Tiff.decode(input, page, sample, jpeg) { x, y, row, count ->
            assertTrue(x + count <= w && y < h)
            System.arraycopy(row, 0, pixels, y * w + x, count)
        }
        return Image(w, h, pixels)
    }

    private fun assertPattern(name: String, image: Image, sample: Int = 1, expected: (Int, Int) -> Int) {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val want = expected(x * sample, y * sample)
            assertEquals("$name at $x,$y", Integer.toHexString(want), Integer.toHexString(image[x, y]))
        }
    }

    private val rgbFixtures = listOf(
        "rgb-none-strips.tif", "rgb-lzw-predictor-msb.tif", "rgb-packbits.tif", "rgb-deflate-tiles.tif",
        "rgb-lzw-planar.tif", "rgb16-none-tiles-msb.tif", "rgb-lzw-bigtiff.tif", "two-pages.tif"
    )

    @Test
    fun losslessRgbVariants() {
        for (name in rgbFixtures) {
            val bytes = fixture(name)
            assertArrayEquals(name, intArrayOf(37, 23), Tiff.size(Tiff.ByteInput(bytes)))
            val image = decode(bytes)
            assertEquals(37, image.width)
            assertEquals(23, image.height)
            assertPattern(name, image, expected = ::rgb)
        }
    }

    @Test
    fun sampledDecodeKeepsEverySamplethPixel() {
        for (name in rgbFixtures) {
            for (sample in listOf(2, 3, 4)) {
                val image = decode(fixture(name), sample)
                assertEquals((37 + sample - 1) / sample, image.width)
                assertEquals((23 + sample - 1) / sample, image.height)
                assertPattern("$name / $sample", image, sample, ::rgb)
            }
        }
    }

    @Test
    fun grayVariants() {
        assertPattern("gray8", decode(fixture("gray8-deflate-predictor.tif")), expected = ::gray)
        assertPattern("gray16", decode(fixture("gray16-lzw-msb.tif")), expected = ::gray)
    }

    @Test
    fun paletteAndBilevel() {
        assertPattern("palette", decode(fixture("palette-lzw.tif")), expected = ::quad)
        val black = argb(0, 0, 0)
        val white = argb(255, 255, 255)
        assertPattern("bilevel", decode(fixture("bilevel-packbits.tif"))) { x, _ -> if (x >= 18) white else black }
    }

    @Test
    fun unassociatedAlphaKeepsColors() {
        val image = decode(fixture("rgba-deflate.tif"))
        assertTrue(Tiff.page(Tiff.ByteInput(fixture("rgba-deflate.tif"))).hasAlpha)
        for (y in 0 until 23) for (x in 0 until 37) {
            val p = image[x, y]
            assertEquals(rgb(x, y) and 0xFFFFFF, p and 0xFFFFFF)
            assertTrue((p ushr 24) in 127..128)
        }
    }

    @Test
    fun lzwPastTwelveBitCodes() {
        val bytes = fixture("busy-lzw.tif")
        assertArrayEquals(intArrayOf(128, 96), Tiff.size(Tiff.ByteInput(bytes)))
        assertPattern("busy", decode(bytes), expected = ::busy)
    }

    @Test
    fun jpegChunksGetTablesAndColorModel() {
        // The decoder is handed whole JPEG streams: SOI, then the shared tables, then the chunk.
        val streams = ArrayList<ByteArray>()
        val image = decode(fixture("jpeg-rgb.tif"), jpeg = { jpeg ->
            streams += jpeg
            Tiff.Raster(37, 16, IntArray(37 * 16) { argb(1, 2, 3) })
        })
        assertEquals(2, streams.size)
        for (s in streams) {
            assertEquals(0xFF, s[0].toInt() and 0xFF)
            assertEquals(0xD8, s[1].toInt() and 0xFF)
            // Photometric RGB: an Adobe marker with transform 0 comes first.
            assertEquals(0xEE, s[3].toInt() and 0xFF)
            assertEquals("Adobe", String(s, 6, 5, Charsets.US_ASCII))
            assertEquals(0, s[17].toInt())
            // Then the quantization tables from JPEGTables.
            assertEquals(0xDB, s[19].toInt() and 0xFF)
        }
        assertEquals(argb(1, 2, 3), image[36, 22])
    }

    @Test
    fun jpegStreamMergesTables() {
        val tables = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val chunk = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 9, 9), Tiff.jpegStream(chunk, tables, rgb = false))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9), Tiff.jpegStream(chunk, null, rgb = false))
        assertThrows(DecodeException::class.java) { Tiff.jpegStream(byteArrayOf(1, 2, 3), null, rgb = false) }
    }

    @Test
    fun truncatedFileKeepsDecodedStrips() {
        // Uncompressed strips of 5 rows come first; the directory follows them.
        val bytes = fixture("rgb-none-strips.tif")
        val input = Tiff.ByteInput(bytes)
        val page = Tiff.page(input)
        val cut = Tiff.ByteInput(bytes.copyOf(8 + 37 * 3 * 7))
        val rows = HashSet<Int>()
        Tiff.decode(cut, page, 1, { error("no JPEG") }) { _, y, _, _ -> rows += y }
        // The first strip whole, and the two rows of the second that are still there.
        assertEquals((0 until 7).toSet(), rows)
    }

    @Test
    fun laterChunkFailureThrows() {
        // Only an early end is forgiven: a strip that does not decode fails the page.
        var calls = 0
        val e = assertThrows(DecodeException::class.java) {
            decode(fixture("jpeg-rgb.tif"), jpeg = {
                if (calls++ > 0) throw DecodeException("bad strip")
                Tiff.Raster(37, 16, IntArray(37 * 16))
            })
        }
        assertEquals("bad strip", e.message)
    }

    @Test
    fun notTiffOrUnsupported() {
        assertNull(Tiff.size(Tiff.ByteInput(ByteArray(64))))
        assertThrows(DecodeException::class.java) { Tiff.page(Tiff.ByteInput("II*\u0000".toByteArray() + ByteArray(4))) }
        // Compression 4 (CCITT Group 4 fax) in place of none.
        val bytes = fixture("rgb-none-strips.tif")
        val at = indexOfEntry(bytes, tag = 259)
        bytes[at + 9] = 4
        val e = assertThrows(DecodeException::class.java) { Tiff.page(Tiff.ByteInput(bytes)) }
        assertEquals("TIFF compression CCITT G4 is not supported", e.message)
        // The size is still known from the headers.
        assertArrayEquals(intArrayOf(37, 23), Tiff.size(Tiff.ByteInput(bytes)))
    }

    /** Offset of the 12-byte entry of [tag] in the first directory of a big-endian classic TIFF. */
    private fun indexOfEntry(bytes: ByteArray, tag: Int): Int {
        fun be(at: Int, n: Int) = (0 until n).fold(0) { v, k -> (v shl 8) or (bytes[at + k].toInt() and 0xFF) }
        assertEquals('M'.code, bytes[0].toInt())
        val ifd = be(4, 4)
        for (i in 0 until be(ifd, 2)) {
            val at = ifd + 2 + i * 12
            if (be(at, 2) == tag) return at
        }
        error("no tag $tag")
    }

    @Test
    fun packBitsVector() {
        // The example from Apple's PackBits technical note.
        val packed = hex("FEAA0280002AFDAA0380002A22F7AA")
        val expected = hex("AAAAAA80002AAAAAAAAA80002A22AAAAAAAAAAAAAAAAAAAA")
        val out = Tiff.PackBitsStream(ByteArrayInputStream(packed)).readBytes()
        assertArrayEquals(expected, out)
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A little-endian classic TIFF: [data] at offset 8, then one directory of LONG [tags]. */
    private fun tiff(data: ByteArray, vararg tags: Pair<Int, LongArray>): ByteArray {
        val sorted = tags.sortedBy { it.first }
        val ifdAt = 8 + data.size
        var extraAt = ifdAt + 2 + sorted.size * 12 + 4
        val size = extraAt + sorted.sumOf { (_, v) -> if (v.size > 1) 4 * v.size else 0 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdAt).put(data)
        buf.putShort(sorted.size.toShort())
        for ((tag, values) in sorted) {
            buf.putShort(tag.toShort()).putShort(4).putInt(values.size)
            if (values.size == 1) {
                buf.putInt(values[0].toInt())
            } else {
                buf.putInt(extraAt)
                for ((i, v) in values.withIndex()) buf.putInt(extraAt + 4 * i, v.toInt())
                extraAt += 4 * values.size
            }
        }
        buf.putInt(0)
        return buf.array()
    }

    private fun tags(vararg pairs: Pair<Int, Long>) = pairs.map { (t, v) -> t to longArrayOf(v) }.toTypedArray()

    @Test
    fun planarJpegRefused() {
        val bytes = tiff(
            ByteArray(4),
            *tags(256 to 2L, 257 to 2L, 259 to 7L, 262 to 2L, 277 to 3L, 284 to 2L, 278 to 2L),
            258 to longArrayOf(8, 8, 8), 273 to longArrayOf(8, 9, 10), 279 to longArrayOf(1, 1, 1)
        )
        val e = assertThrows(DecodeException::class.java) { Tiff.page(Tiff.ByteInput(bytes)) }
        assertEquals("Planar JPEG TIFF is not supported", e.message)
    }

    @Test
    fun planesIndexedByLayoutNotOffsetCount() {
        // 3 x 2 planar RGB in one strip per plane, listing three spare strips after the planes.
        val planes = ByteArray(24) { i -> if (i < 18) (10 * (i / 6 + 1) + i % 6).toByte() else 0xEE.toByte() }
        val bytes = tiff(
            planes,
            *tags(256 to 3L, 257 to 2L, 259 to 1L, 262 to 2L, 277 to 3L, 284 to 2L, 278 to 2L),
            258 to longArrayOf(8, 8, 8),
            273 to longArrayOf(8, 14, 20, 26, 26, 26),
            279 to longArrayOf(6, 6, 6, 6, 6, 6)
        )
        val image = decode(bytes)
        for (i in 0 until 6) assertEquals(argb(10 + i, 20 + i, 30 + i), image[i % 3, i / 3])
    }
}
