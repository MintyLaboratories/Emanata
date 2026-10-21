package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PairCropTest {

    private val left = Box(0.05, 0.10, 0.95, 0.90)
    private val right = Box(0.02, 0.20, 0.90, 0.80)

    @Test
    fun unrelatedPagesShareOneTopAndBottom() {
        val (l, r) = PairCrop.boxes(left, right, spread = false)
        assertEquals(Box(0.05, 0.10, 0.95, 0.90), l)
        assertEquals(Box(0.02, 0.10, 0.90, 0.90), r)
    }

    @Test
    fun theHalvesOfASpreadKeepTheirGutterEdges() {
        val (l, r) = PairCrop.boxes(left, right, spread = true)
        assertEquals(Box(0.05, 0.10, 1.0, 0.90), l)
        assertEquals(Box(0.0, 0.10, 0.90, 0.90), r)
    }

    @Test
    fun aPageWithNoBorderKeepsItsPartnerWhole() {
        val (l, r) = PairCrop.boxes(Box.FULL, right, spread = false)
        assertEquals(Box.FULL, l)
        assertEquals(Box(0.02, 0.0, 0.90, 1.0), r)
    }

    @Test
    fun aspectIsOfTheWholePixelsCropped() {
        // 0.05..0.95 of 1300 and 0.10..0.90 of 2000: 1170 x 1600.
        assertEquals(1170.0 / 1600.0, PairCrop.aspect(left, 1300, 2000), 1e-12)
    }
}
