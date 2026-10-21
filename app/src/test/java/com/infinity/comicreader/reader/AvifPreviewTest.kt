package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AvifPreview] on small AVIF files made from a synthetic 600 x 900 page with avifenc:
 * layered with a 1/2 (300 x 450) or 1/8 (75 x 113) first layer, a grayscale layered
 * one, a single-layer one, and one with a 200 x 300 thumbnail item.
 */
class AvifPreviewTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("avif/$name")!!.use { it.readBytes() }

    private val minSize: (Int, Int) -> IntArray = Previews::sizeOf

    /** The top-level and `meta`/`iprp`/`ipco` box types and bodies of an AVIF file, in order. */
    private fun boxes(b: ByteArray, from: Int = 0, to: Int = b.size): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = from
        while (at + 8 <= to) {
            val size = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
            val type = String(b, at + 4, 4, Charsets.US_ASCII)
            out += type to b.copyOfRange(at + 8, at + size)
            when (type) {
                "meta" -> out += boxes(b, at + 12, at + size)
                "iinf" -> out += boxes(b, at + 14, at + size)
                "iprp", "ipco" -> out += boxes(b, at + 8, at + size)
            }
            at += size
        }
        return out
    }

    private fun u32(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun ispe(boxes: List<Pair<String, ByteArray>>) = boxes.single { it.first == "ispe" }.second.let { intArrayOf(u32(it, 4), u32(it, 8)) }

    private fun mdat(boxes: List<Pair<String, ByteArray>>) = boxes.single { it.first == "mdat" }.second

    @Test
    fun firstLayerIsCutOutWithItsOwnSize() {
        val out = AvifPreview.extract(fixture("layered.avif"), minSize)
        assertNotNull(out)
        val boxes = boxes(out!!)
        assertEquals("ftyp", boxes.first().first)
        assertArrayEquals(intArrayOf(300, 450), ispe(boxes))
        assertFalse(boxes.any { it.first == "a1lx" })
        val data = mdat(boxes)
        // a1lx gives the first layer as 1388 bytes.
        assertEquals(1388, data.size)
        assertArrayEquals(intArrayOf(300, 450), Av1.maxFrameSize(data))
        val layer = Av1.firstLayer(data)!!
        assertArrayEquals(intArrayOf(300, 450), intArrayOf(layer.width, layer.height))
        // The item's data points into the mdat.
        assertEquals(out.size - data.size, u32(boxes.single { it.first == "iloc" }.second, 14))
    }

    @Test
    fun onlyTheSequenceHeaderChangesInTheLayer() {
        val file = fixture("layered.avif")
        val data = mdat(boxes(AvifPreview.extract(file, minSize)!!))
        // The first layer starts the primary item's data at 299 (from iloc).
        val original = file.copyOfRange(299, 299 + data.size)
        assertArrayEquals(intArrayOf(600, 900), Av1.maxFrameSize(original))
        val changed = data.indices.filter { data[it] != original[it] }
        assertTrue(changed.isNotEmpty())
        assertTrue("changed bytes $changed", changed.all { it < 24 })
    }

    @Test
    fun grayscaleLayerToo() {
        val boxes = boxes(AvifPreview.extract(fixture("layered-gray.avif"), minSize)!!)
        assertArrayEquals(intArrayOf(300, 450), ispe(boxes))
        assertArrayEquals(intArrayOf(300, 450), Av1.maxFrameSize(mdat(boxes)))
    }

    @Test
    fun firstLayerSmallerThanAPreviewIsNone() {
        assertNull(AvifPreview.extract(fixture("layered-tiny.avif"), minSize))
    }

    @Test
    fun singleLayerHasNone() {
        val file = fixture("single.avif")
        assertEquals(0, AvifPreview.needed(file, file.size, minSize))
        assertNull(AvifPreview.extract(file, minSize))
    }

    @Test
    fun thumbnailIsCutOut() {
        val file = fixture("with-thumbnail.avif")
        val boxes = boxes(AvifPreview.extract(file, minSize)!!)
        assertArrayEquals(intArrayOf(200, 300), ispe(boxes))
        assertEquals(1, boxes.count { it.first == "infe" })
        // The thumbnail is the second item, stored last.
        val data = mdat(boxes)
        assertArrayEquals(file.copyOfRange(file.size - data.size, file.size), data)
        assertEquals(file.size, AvifPreview.needed(file, file.size, minSize))
    }

    @Test
    fun thumbnailSmallerThanAPreviewIsNone() {
        assertNull(AvifPreview.extract(fixture("with-thumbnail.avif")) { _, _ -> intArrayOf(300, 450) })
    }

    @Test
    fun neededGrowsFromHeaderToMetadataToFirstLayer() {
        val file = fixture("layered.avif")
        val metaEnd = AvifPreview.needed(file, ImageKind.HEADER_BYTES, minSize)
        // ftyp (32) and meta (259).
        assertEquals(291, metaEnd)
        val end = AvifPreview.needed(file, metaEnd, minSize)
        assertEquals(299 + 1388, end)
        assertEquals(end, AvifPreview.needed(file.copyOf(end), end, minSize))
        assertArrayEquals(AvifPreview.extract(file, minSize), AvifPreview.extract(file.copyOf(end), minSize))
        assertNull(AvifPreview.extract(file.copyOf(end - 1), minSize))
    }

    @Test
    fun brokenFilesHaveNone() {
        val file = fixture("layered.avif")
        assertEquals(0, AvifPreview.needed(ByteArray(64), 64, minSize))
        // An iloc version (at 99) no file has.
        val broken = file.copyOf().also { it[99] = 3 }
        assertNull(AvifPreview.extract(broken, minSize))
        assertNull(AvifPreview.extract(file.copyOf(200), minSize))
        val garbledLayer = file.copyOf().also { for (i in 300 until 320) it[i] = 0xFF.toByte() }
        assertNull(AvifPreview.extract(garbledLayer, minSize))
    }
}
