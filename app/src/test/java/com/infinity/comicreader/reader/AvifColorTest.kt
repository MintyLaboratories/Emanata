package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.ColorSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AvifColorTest {

    @Test
    fun srgbAndSdrVideoCurvesNeedNoConversion() {
        // BT.709 or unspecified primaries with sRGB, BT.709, BT.601 or BT.2020 SDR curves, or unspecified.
        for (primaries in listOf(1, 2)) for (transfer in listOf(1, 2, 6, 13, 14, 15)) {
            assertTrue("$primaries/$transfer", AvifColor.isSrgb(primaries, transfer))
            assertNull(AvifColor.space(primaries, transfer))
        }
    }

    @Test
    fun otherPrimariesAreConverted() {
        val bt2020 = AvifColor.space(9, 13) as ColorSpace.Rgb
        assertArrayEquals(floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f), bt2020.getPrimaries(), 1e-4f)
        val p3 = AvifColor.space(12, 13) as ColorSpace.Rgb
        assertArrayEquals(floatArrayOf(0.3127f, 0.3290f), p3.whitePoint, 1e-4f)
        val dci = AvifColor.space(11, 13) as ColorSpace.Rgb
        assertArrayEquals(floatArrayOf(0.314f, 0.351f), dci.whitePoint, 1e-4f)
        // The sRGB curve stays: mid gray decodes as sRGB's does.
        assertEquals((ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb).toLinear(0.5f, 0.5f, 0.5f)[0], bt2020.toLinear(0.5f, 0.5f, 0.5f)[0], 1e-4f)
    }

    @Test
    fun powerCurvesAndLinearLightAreConverted() {
        val linear = AvifColor.space(1, 8) as ColorSpace.Rgb
        assertEquals(0.5f, linear.toLinear(0.5f, 0.5f, 0.5f)[0], 1e-4f)
        val gamma22 = AvifColor.space(1, 4) as ColorSpace.Rgb
        assertEquals(Math.pow(0.5, 2.2).toFloat(), gamma22.toLinear(0.5f, 0.5f, 0.5f)[0], 1e-4f)
        assertFalse(AvifColor.isSrgb(1, 17))
    }

    @Test
    fun hdrCurvesUseTheNamedSpacesFromApi34() {
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_PQ), AvifColor.space(9, 16))
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_HLG), AvifColor.space(9, 18))
    }

    @Test
    @Config(sdk = [33])
    fun beforeApi34HdrCurvesShowAsSrgbCurves() {
        val space = AvifColor.space(9, 16) as ColorSpace.Rgb
        assertEquals((ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb).toLinear(0.5f, 0.5f, 0.5f)[0], space.toLinear(0.5f, 0.5f, 0.5f)[0], 1e-4f)
        assertNull(AvifColor.space(1, 16))
    }
}
