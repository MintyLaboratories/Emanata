package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** Gray pages stay gray through models that tint them ([AiPages.chain], [AiPages.render], [TileEngine.run]); color pages are left alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrayChainTest {

    private fun bitmapOf(px: IntArray, w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(px, 0, w, 0, 0, w, h)
        setHasAlpha(false)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun grayPage(w: Int, h: Int): Bitmap {
        val r = Random(w * 5 + h)
        return bitmapOf(IntArray(w * h) { r.nextInt(256).let { v -> 0xFF000000.toInt() or (v * 0x010101) } }, w, h)
    }

    private fun colorPage(w: Int, h: Int): Bitmap {
        val r = Random(w * 7 + h)
        return bitmapOf(IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }, w, h)
    }

    private fun session(model: FakeModel) = Session(model, ModelProbe.measure(model), 48)

    /** What [FakeModel] with [TINT] makes of [src], each pixel collapsed to gray when [gray]. */
    private fun modeled(src: Bitmap, scale: Int, reach: Int, gray: Boolean): Bitmap {
        val px = FakeModel.reference(src, scale, reach).map { v ->
            val tinted = (v and 0xFF00FFFF.toInt()) or (minOf(255, ((v shr 16) and 0xFF) + TINT) shl 16)
            if (gray) GrayPages.luma(tinted) else tinted
        }.toIntArray()
        return bitmapOf(px, src.width * scale, src.height * scale)
    }

    /** Shows a 60 x 90 page at 70 x 105: enlarged. */
    private val plan = Plan(70, 105, 0.0, 0.0, 60.0, 90.0)

    @Test
    fun aGrayPageStaysGrayThroughEveryModelOfAChain() {
        val src = grayPage(60, 90)
        val run = AiPages.chain(
            listOf(session(FakeModel(scale = 1, reach = 1, tint = TINT)), session(FakeModel(scale = 2, reach = 2, tint = TINT))), src, plan
        )
        assertNull(run.fallback)
        assertEquals(2, run.ran)
        // The second model reads the first's output collapsed, and the resampling the second's.
        val once = modeled(src, 1, 1, gray = true)
        val twice = modeled(once, 2, 2, gray = true)
        val expected = BitmapUtils.resample(twice, AiPages.scaled(plan, 2), false, Scaling.DEFAULT)
        assertArrayEquals(pixels(expected), pixels(run.bitmap!!))
        assertTrue(GrayPages.isGray(run.bitmap))
    }

    @Test
    fun aColorPageKeepsWhatTheModelsMake() {
        val src = colorPage(60, 90)
        val run = AiPages.chain(
            listOf(session(FakeModel(scale = 1, reach = 1, tint = TINT)), session(FakeModel(scale = 2, reach = 2, tint = TINT))), src, plan
        )
        assertNull(run.fallback)
        val once = modeled(src, 1, 1, gray = false)
        val twice = modeled(once, 2, 2, gray = false)
        val expected = BitmapUtils.resample(twice, AiPages.scaled(plan, 2), false, Scaling.DEFAULT)
        assertArrayEquals(pixels(expected), pixels(run.bitmap!!))
    }

    @Test
    fun renderKeepsAGrayPageGrayAndAColorPageAsModeled() {
        val model = FakeModel(scale = 2, reach = 1, tint = TINT)
        val s = session(model)
        val gray = grayPage(60, 90)
        val shownGray = AiPages.render(s, gray, plan)
        assertNull(shownGray.run.fallback)
        assertArrayEquals(
            pixels(BitmapUtils.resample(modeled(gray, 2, 1, gray = true), AiPages.scaled(plan, 2), false, Scaling.DEFAULT)), pixels(shownGray.bitmap)
        )
        assertTrue(GrayPages.isGray(shownGray.bitmap))

        val color = colorPage(60, 90)
        val shownColor = AiPages.render(s, color, plan)
        assertArrayEquals(
            pixels(BitmapUtils.resample(modeled(color, 2, 1, gray = false), AiPages.scaled(plan, 2), false, Scaling.DEFAULT)), pixels(shownColor.bitmap)
        )
    }

    @Test
    fun aTintingModelColorsAGrayPageUnlessCollapsed() {
        val s = session(FakeModel(scale = 2, reach = 1, tint = TINT))
        val page = grayPage(60, 90)
        val raw = TileEngine.run(s, page).bitmap!!
        assertEquals("the fault the collapse undoes", TINT, GrayPages.spread(raw))
        val collapsed = TileEngine.run(s, page, gray = true).bitmap!!
        assertEquals(0, GrayPages.spread(collapsed))
        assertArrayEquals(pixels(raw).map(GrayPages::luma).toIntArray(), pixels(collapsed))
    }

    @Test
    fun aPageWithOneColoredPixelIsNotCollapsed() {
        val px = pixels(grayPage(60, 90))
        px[px.size / 2] = 0xFF204060.toInt()
        val src = bitmapOf(px, 60, 90)
        val run = AiPages.render(session(FakeModel(scale = 2, reach = 1, tint = TINT)), src, plan)
        assertFalse(GrayPages.isGray(run.bitmap))
    }

    private companion object {
        /** Codes a model adds to red. */
        const val TINT = 9
    }
}
