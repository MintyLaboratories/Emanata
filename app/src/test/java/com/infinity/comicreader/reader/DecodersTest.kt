package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodersTest {

    @Test
    fun imagesUnderTheCapDecodeWhole() {
        assertEquals(1, Decoders.capSample(4000, 6000, Target.DEFAULT_CAP_PIXELS))
        assertEquals(1, Decoders.capSample(500, 700, 1_000_000))
    }

    @Test
    fun capHalvesUntilTheImageFits() {
        assertEquals(2, Decoders.capSample(4000, 6000, 12_000_000L))
        assertEquals(4, Decoders.capSample(4000, 6000, 1_500_000L))
        assertEquals(2, Decoders.capSample(10000, 8000, Target.DEFAULT_CAP_PIXELS))
    }

    @Test
    fun degenerateSizesDecodeWhole() {
        assertEquals(1, Decoders.capSample(0, 100, 10))
        assertEquals(1, Decoders.capSample(100, -1, 10))
    }
}
