package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZoomImageViewTest {

    private fun view(w: Int, h: Int, bitmapW: Int, bitmapH: Int, fit: ZoomImageView.FitMode): ZoomImageView =
        ZoomImageView(ApplicationProvider.getApplicationContext<Application>()).apply {
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
            setImageBitmap(Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888))
            setFitMode(fit)
        }

    private fun values(v: ZoomImageView) = FloatArray(9).also { v.imageMatrix.getValues(it) }

    @Test
    fun exactSizeBitmapIsDrawnOneToOneOnWholePixels() {
        // Centered with 24.5 px above it, which lands on a whole pixel.
        val v = view(1848, 2960, 1848, 2911, ZoomImageView.FitMode.SCREEN)
        val m = values(v)
        assertEquals(1f, m[Matrix.MSCALE_X], 0f)
        assertEquals(1f, m[Matrix.MSCALE_Y], 0f)
        assertEquals(m[Matrix.MTRANS_Y], Math.round(m[Matrix.MTRANS_Y]).toFloat(), 0f)
        assertTrue(v.drawsOneToOne)
    }

    @Test
    fun everyFitModeIsOneToOneAtItsOwnSize() {
        assertTrue(view(1848, 2960, 1848, 5000, ZoomImageView.FitMode.WIDTH).drawsOneToOne)
        assertTrue(view(1848, 2960, 1973, 2960, ZoomImageView.FitMode.HEIGHT).drawsOneToOne)
        assertTrue(view(1848, 2960, 1848, 2960, ZoomImageView.FitMode.STRETCH).drawsOneToOne)
    }

    @Test
    fun slowPanAccumulatesBelowAPixelPerMove() {
        val v = view(1848, 2960, 1848, 5000, ZoomImageView.FitMode.WIDTH)
        val t = SystemClock.uptimeMillis()
        var y = 1000f
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 900f, y, 0))
        repeat(10) {
            y -= 0.3f
            v.onTouchEvent(MotionEvent.obtain(t, t + 16L * (it + 1), MotionEvent.ACTION_MOVE, 900f, y, 0))
        }
        val m = values(v)
        assertEquals(-3f, m[Matrix.MTRANS_Y], 0f)
        assertTrue(v.drawsOneToOne)
    }

    @Test
    fun scaledBitmapIsFiltered() {
        assertFalse(view(1848, 2960, 924, 1386, ZoomImageView.FitMode.SCREEN).drawsOneToOne)
    }

    @Test
    fun zoomedBitmapGoesThroughTheLanczosShader() {
        val v = view(1848, 2960, 1848, 2772, ZoomImageView.FitMode.SCREEN)
        assertNull(v.shadedBitmap)
        v.zoomTo(2.5f, 900f, 1400f)
        assertNotNull(v.shadedBitmap)
        v.fit()
        assertNull(v.shadedBitmap)
    }

    @Test
    fun shrunkBitmapStaysBilinear() {
        val v = view(300, 400, 600, 800, ZoomImageView.FitMode.SCREEN)
        assertFalse(v.drawsOneToOne)
        assertNull(v.shadedBitmap)
        v.zoomTo(0.8f, 150f, 200f)
        assertNull(v.shadedBitmap)
        v.zoomTo(1.5f, 150f, 200f)
        assertNotNull(v.shadedBitmap)
    }

    @Test
    @Config(sdk = [32])
    fun zoomBeforeApi33StaysBilinear() {
        val v = view(1848, 2960, 1848, 2772, ZoomImageView.FitMode.SCREEN)
        v.zoomTo(2.5f, 900f, 1400f)
        assertFalse(v.drawsOneToOne)
        assertNull(v.shadedBitmap)
    }

    /** Records render requests; [deliver] completes the last one. */
    private class RecordingDetail : ZoomImageView.DetailSource {
        class Request(val region: Region, val width: Int, val height: Int, val done: (Bitmap) -> Unit) {
            var cancelled = false
        }

        val requests = ArrayList<Request>()
        var prepared = 0

        override fun prepare() {
            prepared++
        }

        override fun render(region: Region, width: Int, height: Int, done: (Bitmap) -> Unit): () -> Unit {
            val request = Request(region, width, height, done)
            requests += request
            return { request.cancelled = true }
        }

        fun deliver(): Bitmap {
            val r = requests.last()
            return Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).also { r.done(it) }
        }
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun zoomedWithDetail(): Pair<ZoomImageView, RecordingDetail> {
        val v = view(1848, 2960, 1848, 2772, ZoomImageView.FitMode.SCREEN)
        val detail = RecordingDetail()
        v.detailSource = detail
        v.zoomTo(2f, 924f, 1480f)
        return v to detail
    }

    @Test
    fun settledZoomAsksForTheViewAtScreenResolution() {
        val (v, detail) = zoomedWithDetail()
        pass(100)
        assertTrue(detail.requests.isEmpty())
        pass(60)
        assertEquals(1, detail.requests.size)
        val r = detail.requests[0]
        assertEquals(1848, r.width)
        assertEquals(2960, r.height)
        // The middle half across, and the rows of 2960 out of 5544 around the middle.
        assertEquals(0.25, r.region.x, 1e-6)
        assertEquals(0.5, r.region.width, 1e-6)
        assertEquals(2960.0 / 5544, r.region.height, 1e-6)
        assertEquals(0.5, r.region.y + r.region.height / 2, 1e-3)

        val shown = detail.deliver()
        assertTrue(v.detailShown === shown)
        // Settling again where it already is asks for nothing new.
        pass(200)
        assertEquals(1, detail.requests.size)
    }

    @Test
    fun movingCancelsTheRenderInFlightAndKeepsTheShownDetail() {
        val (v, detail) = zoomedWithDetail()
        pass(160)
        val shown = detail.deliver()
        v.zoomTo(2.2f, 924f, 1480f)
        pass(160)
        assertEquals(2, detail.requests.size)
        assertTrue(v.detailShown === shown)

        v.zoomTo(2.4f, 924f, 1480f)
        assertTrue(detail.requests[1].cancelled)
        // A render finishing after it was cancelled is not shown.
        detail.requests[1].done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        assertTrue(v.detailShown === shown)
    }

    @Test
    fun backToFitOrANewPageDropsTheDetail() {
        val (v, detail) = zoomedWithDetail()
        pass(160)
        detail.deliver()
        v.fit()
        assertNull(v.detailShown)
        pass(200)
        assertEquals(1, detail.requests.size)

        v.zoomTo(2f, 924f, 1480f)
        pass(160)
        detail.deliver()
        v.setImageBitmap(Bitmap.createBitmap(1848, 2772, Bitmap.Config.ARGB_8888))
        assertNull(v.detailShown)
    }

    @Test
    fun zoomedViewReattachedFromACacheRendersItsDetailAgain() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val v = ZoomImageView(activity)
        frame.addView(v, FrameLayout.LayoutParams(300, 400))
        pass(0)
        v.setImageBitmap(Bitmap.createBitmap(300, 360, Bitmap.Config.ARGB_8888))
        pass(0)
        val detail = RecordingDetail()
        v.detailSource = detail
        v.zoomTo(2f, 150f, 200f)
        pass(160)
        detail.deliver()
        assertNotNull(v.detailShown)

        // Detaching drops the detail; the cache gives the view back zoomed as it was.
        frame.removeView(v)
        assertNull(v.detailShown)
        frame.addView(v, FrameLayout.LayoutParams(300, 400))
        pass(160)
        assertEquals(2, detail.requests.size)
        val shown = detail.deliver()
        assertTrue(v.detailShown === shown)
    }

    @Test
    fun hiddenWindowDropsTheDetailUntilShownAgain() {
        val (v, detail) = zoomedWithDetail()
        pass(160)
        detail.deliver()
        v.dispatchWindowVisibilityChanged(View.GONE)
        assertNull(v.detailShown)
        pass(200)
        assertEquals(1, detail.requests.size)

        v.dispatchWindowVisibilityChanged(View.VISIBLE)
        pass(160)
        assertEquals(2, detail.requests.size)
        val shown = detail.deliver()
        assertTrue(v.detailShown === shown)
    }

    /** Draws [v] as the screen would, through the shaders, which the host runs in its own AGSL dialect. */
    private fun draw(v: ZoomImageView) {
        val canvas = Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888))
        Canvas::class.java.getMethod("setHwFeaturesInSwModeEnabled", Boolean::class.javaPrimitiveType).invoke(canvas, true)
        v.draw(canvas)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun shadersLetGoOfBitmapsNoLongerShown() {
        ZoomImageView.shaderSource = LanczosShader.SOURCE.replace("image.eval(", "sample(image, ")
        try {
            val v = view(300, 400, 300, 360, ZoomImageView.FitMode.SCREEN)
            val page = (v.drawable as BitmapDrawable).bitmap
            val detail = RecordingDetail()
            v.detailSource = detail
            v.zoomTo(2f, 150f, 200f)
            draw(v)
            assertEquals(listOf(page), v.shadersHold)

            pass(160)
            val tile = detail.deliver()
            v.zoomTo(2.2f, 150f, 200f)
            draw(v)
            assertTrue(v.shadersHold.any { it === tile })

            v.fit()
            assertEquals(listOf(page), v.shadersHold)
            v.setImageDrawable(null)
            assertTrue(v.shadersHold.isEmpty())
        } finally {
            ZoomImageView.shaderSource = LanczosShader.SOURCE
        }
    }

    @Test
    fun bandsAroundTheDetailCoverTheRestExactly() {
        val bands = ZoomImageView.bandsAround(0f, 0f, 100f, 200f, RectF(10f, 20f, 60f, 150f))
        assertEquals(
            listOf(
                RectF(0f, 0f, 100f, 20f),
                RectF(0f, 150f, 100f, 200f),
                RectF(0f, 20f, 10f, 150f),
                RectF(60f, 20f, 100f, 150f)
            ),
            bands
        )
        // A hole past an edge leaves no empty band; one off the view leaves it whole.
        assertEquals(listOf(RectF(0f, 150f, 100f, 200f)), ZoomImageView.bandsAround(0f, 0f, 100f, 200f, RectF(-5f, -5f, 105f, 150f)))
        assertEquals(listOf(RectF(0f, 0f, 100f, 200f)), ZoomImageView.bandsAround(0f, 0f, 100f, 200f, RectF(120f, 0f, 150f, 50f)))
    }
}
