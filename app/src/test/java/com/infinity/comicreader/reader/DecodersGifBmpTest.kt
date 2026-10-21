package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** GIF and BMP pages go to the platform decoder; an animated GIF shows its first frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodersGifBmpTest {

    private val red = 0xFF0000
    private val blue = 0x0000FF

    @Test
    fun gifFirstFrame() {
        val gif = TestImages.gif(30, 20, red, blue)
        assertEquals(ImageKind.GIF, ImageKind.sniff(gif))
        assertArrayEquals(intArrayOf(30, 20), Decoders.size(gif))
        val bitmap = Decoders.decode(gif, Target.NATIVE)
        assertEquals(30, bitmap.width)
        assertEquals(20, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(15, 10))
    }

    @Test
    fun gifResampledToTarget() {
        val bitmap = Decoders.decode(TestImages.gif(40, 20, blue), Target(20, 0))
        assertEquals(20, bitmap.width)
        assertEquals(10, bitmap.height)
        assertEquals(Color.BLUE, bitmap.getPixel(10, 5))
    }

    @Test
    fun bmp() {
        val bmp = TestImages.bmp(31, 17, red)
        assertEquals(ImageKind.BMP, ImageKind.sniff(bmp))
        assertArrayEquals(intArrayOf(31, 17), Decoders.size(bmp))
        val bitmap = Decoders.decode(bmp, Target.NATIVE)
        assertEquals(31, bitmap.width)
        assertEquals(17, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(30, 16))
    }

    @Test
    @Config(sdk = [27])
    fun legacyDecoderBelowApi28() {
        val gif = Decoders.decode(TestImages.gif(12, 8, red, blue), Target.NATIVE)
        assertEquals(Color.RED, gif.getPixel(6, 4))
        val bmp = Decoders.decode(TestImages.bmp(12, 8, blue), Target.NATIVE)
        assertEquals(Color.BLUE, bmp.getPixel(0, 0))
    }
}
