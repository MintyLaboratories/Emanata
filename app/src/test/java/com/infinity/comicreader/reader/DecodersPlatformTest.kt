package com.infinity.comicreader.reader

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [Decoders] against the real platform decoders (Robolectric native graphics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodersPlatformTest {

    @Test
    fun truncatedImageKeepsDecodedPart() {
        val png = TestImages.png(40, 30)
        val bitmap = Decoders.decode(png.copyOf(png.size - 20), 0, 0)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
    }

    @Test
    fun corruptImageKeepsDecodedPart() {
        // Garbles the compressed pixel data after the IDAT chunk header.
        val png = TestImages.png(40, 30).also { for (i in 60 until 70) it[i] = 0x55 }
        val bitmap = Decoders.decode(png, 0, 0)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
    }
}
