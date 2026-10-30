package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Looper
import android.widget.FrameLayout
import com.infinity.comicreader.reader.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** A page larger than its bitmap draws in bands at its fitted size, around what shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZoomImageViewBandsTest {

    /** Records band requests; delivers them at once when [immediate]. */
    private class Bands(override val bands: Boolean = true, var immediate: Boolean = true, var fails: Boolean = false) :
        ZoomImageView.DetailSource {
        class Request(val region: Region, val width: Int, val height: Int, val dropped: () -> Unit, val done: (Bitmap?) -> Unit) {
            var canceled = false
        }

        val requests = ArrayList<Request>()

        override fun prepare() = Unit

        override fun render(region: Region, width: Int, height: Int, done: (Bitmap) -> Unit): () -> Unit = {}

        override fun renderBand(region: Region, width: Int, height: Int, dropped: () -> Unit, done: (Bitmap?) -> Unit): () -> Unit {
            val request = Request(region, width, height, dropped, done)
            requests += request
            if (immediate) deliver(request)
            return { request.canceled = true }
        }

        fun deliver(request: Request) =
            request.done(if (fails) null else Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888))
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A view 400 x 800 fitting a 100 x 2000 stand-in to its width: a page 400 x 8000. */
    private fun shown(detail: Bands, bitmapW: Int = 100, bitmapH: Int = 2000): ZoomImageView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val v = ZoomImageView(activity)
        frame.addView(v, FrameLayout.LayoutParams(400, 800))
        pass(0)
        v.setImageBitmap(Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888))
        v.setFitMode(ZoomImageView.FitMode.WIDTH)
        v.detailSource = detail
        pass(0)
        v.updateBands()
        return v
    }

    @Test
    fun bandsCoverWhatShowsAndTheScreenBelow() {
        val detail = Bands()
        val v = shown(detail)
        // From a quarter screen above (none) to a screen below: rows 0..1600.
        assertEquals(setOf(0, 1), v.bandsShown.keys)
        val first = detail.requests.first()
        assertEquals(400, first.width)
        assertEquals(ZoomImageView.BAND_HEIGHT, first.height)
        assertEquals(Region(0.0, 0.0, 1.0, ZoomImageView.BAND_HEIGHT / 8000.0), first.region)
    }

    @Test
    fun scrollingDownRendersAheadAndDropsWhatIsFarBehind() {
        val detail = Bands()
        val v = shown(detail)
        while (v.scrollPage(PageAdapter.TALL_STEP)) pass(400)
        val m = FloatArray(9).also { v.imageMatrix.getValues(it) }
        assertEquals(-7200f, m[Matrix.MTRANS_Y], 0.5f)
        // The page's last screen and half a screen above it: rows 6800..8000.
        assertEquals(setOf(6, 7), v.bandsShown.keys)
        assertTrue(v.bandsShown.getValue(7).height == 8000 - 7 * ZoomImageView.BAND_HEIGHT)
    }

    @Test
    fun aPageAtItsOwnSizeHasNoBands() {
        val detail = Bands()
        val v = shown(detail, 400, 8000)
        assertTrue(detail.requests.isEmpty())
        assertTrue(v.bandsShown.isEmpty())
    }

    @Test
    fun aPageNotDrawnInBandsAsksForNone() {
        val detail = Bands(bands = false)
        shown(detail)
        assertTrue(detail.requests.isEmpty())
    }

    @Test
    fun anotherPageDropsTheBandsAndStopsTheirRenders() {
        val detail = Bands(immediate = false)
        val v = shown(detail)
        assertEquals(2, detail.requests.size)
        v.setImageBitmap(Bitmap.createBitmap(100, 2000, Bitmap.Config.ARGB_8888))
        assertTrue(detail.requests.all { it.canceled })
        // A render that lands late is not drawn.
        detail.deliver(detail.requests.first())
        assertTrue(v.bandsShown.isEmpty())
    }

    @Test
    fun aFailedBandIsNotAskedForAgain() {
        val detail = Bands(fails = true)
        val v = shown(detail)
        assertEquals(2, detail.requests.size)
        v.updateBands()
        assertEquals(2, detail.requests.size)
        assertTrue(v.bandsShown.isEmpty())
    }

    @Test
    fun aBandWhoseDetailWasDroppedIsAskedForAgain() {
        val detail = Bands(immediate = false)
        val v = shown(detail)
        assertEquals(2, detail.requests.size)
        // The page's detail let go of, as when another page's render keeps only its own.
        detail.requests.toList().forEach { it.dropped() }
        pass(0)
        assertEquals(4, detail.requests.size)
        detail.requests.drop(2).forEach { detail.deliver(it) }
        assertEquals(setOf(0, 1), v.bandsShown.keys)
    }

    @Test
    fun scrollPageStopsAtTheEdges() {
        val v = shown(Bands())
        assertFalse(v.scrollPage(-PageAdapter.TALL_STEP))
        assertTrue(v.scrollPage(PageAdapter.TALL_STEP))
        pass(400)
        val m = FloatArray(9).also { v.imageMatrix.getValues(it) }
        assertEquals(-720f, m[Matrix.MTRANS_Y], 0.5f)
    }

    @Test
    fun aPageTurnedBackToStartsAtItsBottom() {
        val v = shown(Bands())
        v.startAtBottom = true
        v.fit()
        val m = FloatArray(9).also { v.imageMatrix.getValues(it) }
        assertEquals(-7200f, m[Matrix.MTRANS_Y], 0.5f)
        assertFalse(v.scrollPage(PageAdapter.TALL_STEP))
    }

    @Test
    fun bandRangeTakesEveryBandTouched() {
        assertEquals(0..1, ZoomImageView.bandRange(-200.0, 1600.0, 1024, 8))
        assertEquals(6..7, ZoomImageView.bandRange(6800.0, 9000.0, 1024, 8))
        assertTrue(ZoomImageView.bandRange(9000.0, 9500.0, 1024, 8).isEmpty())
        assertTrue(ZoomImageView.bandRange(500.0, 500.0, 1024, 8).isEmpty())
    }
}
