package com.infinity.comicreader.reader

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WraparoundTest {

    private val w = 1300
    private val h = 1000

    /** A synthetic wraparound in luminance: gray art blocks on both halves, barcodes on the halves asked for. */
    private fun cover(barcodeLeft: Boolean, barcodeRight: Boolean): ByteArray {
        val px = ByteArray(w * h) { 0xF0.toByte() }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = v.toByte()
        }
        // Artwork: a title bar and a figure on the front, a text block on the back.
        rect(700, 80, 1250, 220, 0x30)
        rect(820, 300, 1100, 900, 0x80)
        rect(60, 100, 600, 500, 0xA0)
        fun barcode(x0: Int) {
            val matrix = MultiFormatWriter().encode("9780201379624", BarcodeFormat.EAN_13, 300, 120)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                px[(800 + y) * w + x0 + x] = (if (matrix.get(x, y)) 0 else 0xFF).toByte()
            }
        }
        if (barcodeLeft) barcode(100)
        if (barcodeRight) barcode(w / 2 + 250)
        return px
    }

    @Test
    fun theFrontIsTheHalfWithoutTheBarcode() {
        assertEquals(Wraparound.Half.RIGHT, Wraparound.front(cover(barcodeLeft = true, barcodeRight = false), w, h))
        assertEquals(Wraparound.Half.LEFT, Wraparound.front(cover(barcodeLeft = false, barcodeRight = true), w, h))
    }

    @Test
    fun noBarcodeOrTwoTellNothing() {
        assertNull(Wraparound.front(cover(barcodeLeft = false, barcodeRight = false), w, h))
        assertNull(Wraparound.front(cover(barcodeLeft = true, barcodeRight = true), w, h))
    }

    @Test
    fun wraparoundsAreAboutTwoCoversWide() {
        // Wraparounds run about 1.3-1.6; single covers about 0.65-0.78.
        assertTrue(Wraparound.isWraparound(1320, 1000))
        assertTrue(Wraparound.isWraparound(1560, 1000))
        assertFalse(Wraparound.isWraparound(780, 1000))
        assertFalse(Wraparound.isWraparound(1000, 1000))
        assertFalse(Wraparound.isWraparound(3000, 1000))
        assertFalse(Wraparound.isWraparound(0, 0))
    }

    @Test
    fun aWraparoundIsTwoInnerPagesWide() {
        val portrait = 1000.0 / 1500
        // Two pages and a spine, at another scan size than the pages.
        assertTrue(Wraparound.isWraparound(2750, 2000, portrait))
        assertTrue(Wraparound.isWraparound(1500 * 2 * 85 / 100 + 10, 1500 * 3 / 2, portrait))
        assertFalse(Wraparound.isWraparound(1400, 1500, portrait))
        assertFalse(Wraparound.isWraparound(3000, 1500, portrait))
        // Tall pages make a square wraparound, which shape alone would miss.
        assertTrue(Wraparound.isWraparound(1000, 1000, 0.5))
        assertFalse(Wraparound.isWraparound(1000, 1000, null))
        // In a landscape book, a landscape cover is one page.
        assertFalse(Wraparound.isWraparound(1400, 1000, 1.4))
        assertTrue(Wraparound.isWraparound(2800, 1000, 1.4))
        // Unknown inner pages fall back to shape alone.
        assertTrue(Wraparound.isWraparound(1320, 1000, null))
        assertFalse(Wraparound.isWraparound(0, 1000, portrait))
    }

    @Test
    fun onlyWideCoversAreCandidates() {
        assertFalse(Wraparound.isCandidate(650, 1000))
        assertTrue(Wraparound.isCandidate(900, 1000))
        assertFalse(Wraparound.isCandidate(0, 0))
    }

    @Test
    fun theTypicalInnerPageIsTheMedianShape() {
        assertEquals(0.5, Wraparound.innerRatio(listOf(intArrayOf(500, 1000), intArrayOf(2000, 1000), intArrayOf(400, 1000)))!!, 1e-9)
        assertEquals(0.55, Wraparound.innerRatio(listOf(intArrayOf(500, 1000), intArrayOf(600, 1000), intArrayOf(0, 0)))!!, 1e-9)
        assertNull(Wraparound.innerRatio(emptyList()))
    }

    @Test
    fun theBarcodeShowsTheFrontBeforeTheText() {
        val left = Wraparound.Half.LEFT
        val right = Wraparound.Half.RIGHT
        assertEquals(right, Wraparound.Fronts(right, left).shown)
        assertEquals(left, Wraparound.Fronts(left, null).shown)
        assertEquals(left, Wraparound.Fronts(null, left).shown)
        // Neither tells: shown whole, never by reading direction.
        assertNull(Wraparound.Fronts(null, null).shown)
    }

    @Test
    fun onlyBarcodeAndTextTogetherSetTheDirection() {
        val left = Wraparound.Half.LEFT
        val right = Wraparound.Half.RIGHT
        assertEquals(left, Wraparound.Fronts(left, left).agreed)
        assertEquals(right, Wraparound.Fronts(right, right).agreed)
        assertNull(Wraparound.Fronts(left, right).agreed)
        assertNull(Wraparound.Fronts(right, null).agreed)
        assertNull(Wraparound.Fronts(null, left).agreed)
        assertNull(Wraparound.Fronts(null, null).agreed)
    }

    @Test
    fun theFrontIsOnePageWideFromTheOuterEdge() {
        // 2100 wide: two 1000-wide pages and a 100-wide spine.
        assertArrayEquals(intArrayOf(1100, 2100), Wraparound.frontColumns(2100, 1500, Wraparound.Half.RIGHT, 1000.0 / 1500))
        assertArrayEquals(intArrayOf(0, 1000), Wraparound.frontColumns(2100, 1500, Wraparound.Half.LEFT, 1000.0 / 1500))
        // Never more than half; half without inner pages.
        assertArrayEquals(intArrayOf(1000, 2000), Wraparound.frontColumns(2000, 1500, Wraparound.Half.RIGHT, 0.9))
        assertArrayEquals(intArrayOf(0, 1050), Wraparound.frontColumns(2100, 1500, Wraparound.Half.LEFT, null))
    }

    @Test
    fun luminanceWeighsGreenMost() {
        val out = ByteArray(3)
        Wraparound.luminance(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFFFF.toInt()), 3, out, 0)
        assertEquals(76, out[0].toInt() and 0xFF)
        assertEquals(150, out[1].toInt() and 0xFF)
        assertEquals(255, out[2].toInt() and 0xFF)
    }
}
