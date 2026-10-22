package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/** [Avif] and its use in [Decoders], on a [FakeAvifCodec] standing in for libavif. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvifTest {

    private val full = Color.rgb(40, 120, 200)
    private val layer = Color.rgb(200, 60, 20)
    private val bytes = ByteArray(100)

    private fun use(codec: FakeAvifCodec) = codec.also { Avif.codec = it }

    /** A 600 x 900 file with a 150 x 225 first layer. */
    private fun layered(file: IntArray = FakeAvifCodec.info(600, 900), metadataBytes: Int = 0, layerBytes: Int = 0) = use(
        FakeAvifCodec(
            file, listOf(FakeAvifCodec.info(150, 225), FakeAvifCodec.info(600, 900)), listOf(layer, full),
            metadataBytes, layerBytes
        )
    )

    private fun single(info: IntArray) = use(FakeAvifCodec(info, listOf(info), listOf(full)))

    @After
    fun reset() {
        Avif.codec = null
    }

    @Test
    fun singleImageDecodesWhole() {
        val codec = single(FakeAvifCodec.info(600, 900))
        val bitmap = Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS)
        assertArrayEquals(intArrayOf(600, 900), intArrayOf(bitmap.width, bitmap.height))
        assertEquals(full, bitmap.getPixel(300, 450))
        assertFalse(bitmap.hasAlpha())
        assertEquals(listOf(1), codec.samples)
        assertEquals(false, codec.layersAsked)
        assertEquals(1, codec.closed)
    }

    @Test
    fun overTheCapIsSampledInTheDecoder() {
        val codec = single(FakeAvifCodec.info(601, 900))
        val bitmap = Avif.decode(bytes, 140_000L)
        assertEquals(listOf(2), codec.samples)
        assertArrayEquals(intArrayOf(301, 450), intArrayOf(bitmap.width, bitmap.height))
    }

    @Test
    fun layeredImageShowsItsLastLayer() {
        val codec = layered()
        val bitmap = Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS)
        assertEquals(false, codec.layersAsked)
        assertEquals(1, codec.nexts)
        assertArrayEquals(intArrayOf(600, 900), intArrayOf(bitmap.width, bitmap.height))
        assertEquals(full, bitmap.getPixel(0, 0))
    }

    @Test
    fun firstLayerIsHandedOverBeforeTheFullImage() {
        val codec = layered()
        var first: Bitmap? = null
        var fullSize: IntArray? = null
        val bitmap = Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS) { it, size ->
            first = it
            fullSize = size
        }
        assertArrayEquals(intArrayOf(600, 900), fullSize)
        assertEquals(true, codec.layersAsked)
        assertEquals(2, codec.nexts)
        assertArrayEquals(intArrayOf(150, 225), intArrayOf(first!!.width, first!!.height))
        assertEquals(layer, first!!.getPixel(10, 10))
        assertEquals(full, bitmap.getPixel(10, 10))
        assertEquals(listOf(1, 1), codec.samples)
    }

    @Test
    fun firstLayerThatFailsToRenderIsSkipped() {
        val file = FakeAvifCodec.info(600, 900)
        val codec = use(FakeAvifCodec(file, listOf(FakeAvifCodec.info(150, 225), file), listOf(layer, full), failRenderOf = 0))
        var handed = false
        val bitmap = Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS) { _, _ -> handed = true }
        assertFalse(handed)
        assertEquals(full, bitmap.getPixel(10, 10))
        assertEquals(1, codec.closed)
    }

    @Test
    fun firstLayerTakerThatFailsDoesNotFailTheDecode() {
        layered()
        val bitmap = Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS) { _, _ -> throw OutOfMemoryError("test") }
        assertEquals(full, bitmap.getPixel(10, 10))
    }

    @Test
    fun firstLayerOverHalfTheSizeIsNotHandedOver() {
        val file = FakeAvifCodec.info(600, 900)
        val codec = use(FakeAvifCodec(file, listOf(FakeAvifCodec.info(301, 450), file), listOf(layer, full)))
        var handed = false
        Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS) { _, _ -> handed = true }
        assertFalse(handed)
        assertEquals(2, codec.nexts)
        assertEquals(listOf(1), codec.samples)
    }

    @Test
    fun previewLayerIsAtMostHalfEachWayRoundedUp() {
        val file = AvifInfo(FakeAvifCodec.info(601, 900))
        assertTrue(Avif.isPreviewLayer(AvifInfo(FakeAvifCodec.info(301, 450)), file))
        assertFalse(Avif.isPreviewLayer(AvifInfo(FakeAvifCodec.info(302, 450)), file))
        assertFalse(Avif.isPreviewLayer(AvifInfo(FakeAvifCodec.info(301, 451)), file))
        assertFalse(Avif.isPreviewLayer(AvifInfo(FakeAvifCodec.info(1200, 1800)), file))
    }

    @Test
    fun firstLayerLargerThanTheImageIsNone() {
        val file = FakeAvifCodec.info(600, 900)
        use(FakeAvifCodec(file, listOf(FakeAvifCodec.info(1200, 1800), file), listOf(layer, full)))
        assertNull(Avif.firstLayer(bytes))
        var handed = false
        Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS) { _, _ -> handed = true }
        assertFalse(handed)
    }

    @Test
    fun firstLayerAloneFromTheStartOfTheFile() {
        val codec = layered(metadataBytes = 30, layerBytes = 60)
        assertNull(Avif.firstLayer(ByteArray(20)))
        assertNull(Avif.firstLayer(ByteArray(40)))
        val first = Avif.firstLayer(ByteArray(60))!!
        assertArrayEquals(intArrayOf(150, 225), intArrayOf(first.width, first.height))
        assertEquals(layer, first.getPixel(0, 0))
        assertEquals(codec.opened, codec.closed)
    }

    @Test
    fun firstLayerSmallerThanAskedIsNone() {
        layered()
        assertNull(Avif.firstLayer(bytes) { _, _ -> intArrayOf(171, 256) })
        assertNotNull(Avif.firstLayer(bytes) { _, _ -> intArrayOf(150, 225) })
    }

    @Test
    fun singleImageHasNoFirstLayer() {
        single(FakeAvifCodec.info(600, 900))
        assertNull(Avif.firstLayer(bytes))
    }

    @Test
    fun tooLargeImagesAreRefused() {
        val codec = single(FakeAvifCodec.info(8000, 6000))
        try {
            Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS)
            fail()
        } catch (e: DecodeException) {
            assertTrue(e.message!!.contains("8000 x 6000"))
        }
        assertEquals(1, codec.closed)
    }

    @Test
    fun sizeComesFromTheMetadata() {
        single(FakeAvifCodec.info(600, 900, rotation = 1, crop = intArrayOf(10, 20, 500, 800)))
        assertArrayEquals(intArrayOf(800, 500), Avif.size(bytes))
        use(FakeAvifCodec(FakeAvifCodec.info(600, 900), emptyList(), emptyList(), metadataBytes = 50))
        assertNull(Avif.size(ByteArray(40), complete = false))
        assertArrayEquals(intArrayOf(600, 900), Avif.size(ByteArray(50), complete = false))
        use(FakeAvifCodec(FakeAvifCodec.info(600, 900), emptyList(), emptyList(), failOpen = true))
        assertNull(Avif.size(bytes))
    }

    @Test
    fun turnedAnticlockwise() {
        val info = AvifInfo(FakeAvifCodec.info(4, 2, rotation = 1))
        val src = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.RED) }
        val out = Avif.oriented(src, info)
        assertArrayEquals(intArrayOf(2, 4), intArrayOf(out.width, out.height))
        // A quarter turn anticlockwise takes the top-left corner to the bottom left.
        assertEquals(Color.RED, out.getPixel(0, 3))
    }

    @Test
    fun mirroredBothWays() {
        val src = { Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.RED) } }
        assertEquals(Color.RED, Avif.oriented(src(), AvifInfo(FakeAvifCodec.info(4, 2, mirror = AvifInfo.MIRROR_LEFT_RIGHT))).getPixel(3, 0))
        assertEquals(Color.RED, Avif.oriented(src(), AvifInfo(FakeAvifCodec.info(4, 2, mirror = AvifInfo.MIRROR_TOP_BOTTOM))).getPixel(0, 1))
    }

    @Test
    fun cleanApertureCropsBeforeTurning() {
        val info = AvifInfo(FakeAvifCodec.info(8, 4, rotation = 3, crop = intArrayOf(2, 0, 4, 2)))
        val src = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { setPixel(2, 0, Color.RED) }
        val out = Avif.oriented(src, info)
        assertArrayEquals(intArrayOf(2, 4), intArrayOf(out.width, out.height))
        // Three quarter turns anticlockwise (one clockwise) take the top-left corner to the top right.
        assertEquals(Color.RED, out.getPixel(1, 0))
    }

    @Test
    fun cleanApertureScalesToSmallerRenderings() {
        val info = AvifInfo(FakeAvifCodec.info(600, 900, crop = intArrayOf(100, 150, 400, 600)))
        assertArrayEquals(intArrayOf(25, 37, 125, 188), info.cropIn(150, 225))
        assertNull(AvifInfo(FakeAvifCodec.info(600, 900)).cropIn(150, 225))
    }

    @Test
    fun plainImagesAreLeftAsTheyAre() {
        val src = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        assertSame(src, Avif.oriented(src, AvifInfo(FakeAvifCodec.info(4, 2))))
    }

    @Test
    fun widerPrimariesComeOutAsSrgb() {
        single(FakeAvifCodec.info(60, 90, primaries = 9))
        val bitmap = Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
        // BT.2020 primaries reach further than sRGB's: the same values are converted.
        assertNotEquals(full, bitmap.getPixel(30, 45))
    }

    @Test
    fun alphaIsKept() {
        single(FakeAvifCodec.info(60, 90, alpha = true))
        assertTrue(Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS).hasAlpha())
    }

    @Test
    fun aFailedRenderClosesTheFile() {
        val info = FakeAvifCodec.info(6, 9)
        val codec = use(FakeAvifCodec(info, listOf(info), listOf(full), failRender = true))
        try {
            Avif.decode(bytes, Target.DEFAULT_CAP_PIXELS)
            fail()
        } catch (e: IOException) {
            assertEquals("Could not convert AVIF", e.message)
        }
        assertEquals(1, codec.closed)
    }

    // ------------------------------------------------------------------ Decoders

    /** A file [Decoders] takes for AVIF: an `ftyp` box with the `avif` brand. */
    private val avifBytes = byteArrayOf(0, 0, 0, 16) + "ftypavif".toByteArray() + ByteArray(4) + ByteArray(64)

    /** A sink that keeps the size of what it is handed. */
    private class SizeSink : Decoders.PreviewSink {
        var previewed: IntArray? = null

        override fun encoded(bytes: ByteArray) = fail()

        override fun decoded(full: Bitmap) {
            previewed = intArrayOf(full.width, full.height)
        }
    }

    @Test
    fun decodersShowTheFirstLayerAndKeepItsPreview() {
        val file = FakeAvifCodec.info(1200, 1800)
        use(FakeAvifCodec(file, listOf(FakeAvifCodec.info(300, 450), file), listOf(layer, full)))
        var standIn: Bitmap? = null
        val sink = SizeSink()
        val page = Decoders.capturing(sink) {
            Decoders.standingIn({ standIn = it }) { Decoders.decode(avifBytes, Target(600, 900)) }
        }
        assertArrayEquals(intArrayOf(600, 900), intArrayOf(page.width, page.height))
        assertArrayEquals(intArrayOf(300, 450), sink.previewed)
        // Made to the page's target, but never enlarged.
        assertArrayEquals(intArrayOf(300, 450), intArrayOf(standIn!!.width, standIn!!.height))
        assertEquals(layer, standIn!!.getPixel(150, 200))
    }

    @Test
    fun decodersPreviewFromTheFullImageWhenTheFirstLayerIsSmaller() {
        layered()
        var standIn: Bitmap? = null
        val sink = SizeSink()
        Decoders.capturing(sink) {
            Decoders.standingIn({ standIn = it }) { Decoders.decode(avifBytes, Target(300, 450)) }
        }
        // 150 x 225 is under the preview size of 600 x 900; it still stands in.
        assertArrayEquals(intArrayOf(600, 900), sink.previewed)
        assertArrayEquals(intArrayOf(150, 225), intArrayOf(standIn!!.width, standIn!!.height))
    }

    @Test
    fun decodersMakeNoStandInWhileNoneIsWanted() {
        layered()
        var shown = false
        val page = Decoders.standingIn({ shown = true }, { false }) { Decoders.decode(avifBytes, Target(300, 450)) }
        assertFalse(shown)
        assertEquals(full, page.getPixel(10, 10))
    }

    @Test
    fun decodersPreviewFromTheFirstLayer() {
        val file = FakeAvifCodec.info(1200, 1800)
        use(FakeAvifCodec(file, listOf(FakeAvifCodec.info(600, 900), file), listOf(layer, full)))
        val preview = Decoders.preview(avifBytes)!!
        assertArrayEquals(Previews.sizeOf(1200, 1800), intArrayOf(preview.width, preview.height))
    }

    @Test
    fun decodersReadTheSizeThroughLibavif() {
        single(FakeAvifCodec.info(600, 900, rotation = 1))
        assertArrayEquals(intArrayOf(900, 600), Decoders.size(avifBytes))
        assertArrayEquals(intArrayOf(900, 600), Decoders.headerSize(avifBytes.inputStream()))
    }

    @Test
    fun withoutTheNativeLibraryAvifDoesNotDecode() {
        try {
            Decoders.decode(avifBytes, Target(300, 450))
            fail()
        } catch (e: DecodeException) {
            assertEquals("No AVIF decoder on this device", e.message)
        }
        assertNull(Decoders.size(avifBytes))
    }
}
