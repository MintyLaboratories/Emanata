package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodersTest {

    @Test
    fun sampleKeepsBothSidesAtOrAboveTheTarget() {
        assertEquals(1, Decoders.sampleSize(2000, 3000, 1600, 2560, Long.MAX_VALUE))
        assertEquals(2, Decoders.sampleSize(4000, 6000, 1600, 2560, Long.MAX_VALUE))
        assertEquals(4, Decoders.sampleSize(8000, 12000, 1600, 2560, Long.MAX_VALUE))
        assertEquals(1, Decoders.sampleSize(500, 700, 1600, 2560, Long.MAX_VALUE))
        assertEquals(32, Decoders.sampleSize(4000, 6000, 100, 100, Long.MAX_VALUE))
    }

    @Test
    fun freeAxisOnlyLimitsTheOther() {
        // Fit-width requests pass 1 for the height.
        assertEquals(2, Decoders.sampleSize(4000, 60000, 1600, 1, Long.MAX_VALUE))
        // A free axis counts as 4096 px when the request is 0.
        assertEquals(2, Decoders.sampleSize(9000, 9000, 0, 0, Long.MAX_VALUE))
    }

    @Test
    fun pixelCapHalvesFurther() {
        assertEquals(2, Decoders.sampleSize(4000, 6000, 4000, 6000, 12_000_000L))
        assertEquals(4, Decoders.sampleSize(4000, 6000, 4000, 6000, 1_500_000L))
    }

    @Test
    fun degenerateSizesDecodeWhole() {
        assertEquals(1, Decoders.sampleSize(0, 100, 10, 10, 1000))
        assertEquals(1, Decoders.sampleSize(100, -1, 10, 10, 1000))
    }
}
