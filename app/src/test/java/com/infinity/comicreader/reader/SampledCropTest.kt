package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SampledCropTest {

    @Test
    fun aFullSizeDecodeReportsWhatItFound() {
        val (box, size) = SampledCrop.of(intArrayOf(10, 20, 990, 1480), 1000, 1500, intArrayOf(1000, 1500))
        assertEquals(Box(0.01, 20 / 1500.0, 0.99, 1480 / 1500.0), box)
        assertArrayEquals(intArrayOf(1000, 1500), size)
    }

    @Test
    fun aSampledDecodeReportsItsCropOnTheFullSize() {
        val (box, size) = SampledCrop.of(intArrayOf(10, 20, 490, 730), 500, 750, intArrayOf(2000, 3000))
        assertArrayEquals(intArrayOf(2000, 3000), size)
        assertArrayEquals(intArrayOf(40, 80, 1960, 2920), box.pixels(2000, 3000))
    }

    @Test
    fun anOddSizeSampledDownStillCountsAsTheSameShape() {
        // 2001 x 3001 sampled by 4 rounds up to 501 x 751.
        val (_, size) = SampledCrop.of(intArrayOf(5, 5, 495, 745), 501, 751, intArrayOf(2001, 3001))
        assertArrayEquals(intArrayOf(2001, 3001), size)
    }

    @Test
    fun aSourceOfAnotherShapeIsNotTrusted() {
        // A header that disagrees with the decode, as a rotation the header does not tell.
        val (box, size) = SampledCrop.of(intArrayOf(10, 20, 490, 730), 500, 750, intArrayOf(3000, 2000))
        assertArrayEquals(intArrayOf(500, 750), size)
        assertEquals(Box.of(intArrayOf(10, 20, 490, 730), 500, 750), box)
    }

    @Test
    fun noBorderIsTheWholeImage() {
        val (box, size) = SampledCrop.of(null, 500, 750, intArrayOf(2000, 3000))
        assertEquals(Box.FULL, box)
        assertArrayEquals(intArrayOf(2000, 3000), size)
        assertArrayEquals(intArrayOf(500, 750), SampledCrop.of(null, 500, 750, null).second)
    }
}
