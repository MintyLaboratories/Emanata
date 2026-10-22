package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Two pages shown as one picture: the left page's view lays out both and zooms them together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZoomImageViewJoinTest {

    private fun view(w: Int, h: Int) = ZoomImageView(ApplicationProvider.getApplicationContext<Application>()).apply {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    /** A left and a right view over one [w] x [h] area, joined, showing pages of the sizes given. */
    private fun pair(w: Int, h: Int, left: IntArray, right: IntArray): Pair<ZoomImageView, ZoomImageView> {
        val l = view(w, h)
        val r = view(w, h)
        l.joinWith(r)
        l.setImageBitmap(Bitmap.createBitmap(left[0], left[1], Bitmap.Config.ARGB_8888))
        r.setImageBitmap(Bitmap.createBitmap(right[0], right[1], Bitmap.Config.ARGB_8888))
        l.setFitMode(ZoomImageView.FitMode.SCREEN)
        r.setFitMode(ZoomImageView.FitMode.SCREEN)
        shadowOf(Looper.getMainLooper()).idle()
        return l to r
    }

    private fun values(v: ZoomImageView) = FloatArray(9).also { v.imageMatrix.getValues(it) }

    @Test
    fun bothPagesFitAsOnePictureCenteredAndMeetAtTheGutter() {
        val (l, r) = pair(1000, 600, intArrayOf(400, 600), intArrayOf(400, 600))
        val a = values(l)
        val b = values(r)
        assertEquals(1f, a[Matrix.MSCALE_X], 1e-4f)
        assertEquals(100f, a[Matrix.MTRANS_X], 1e-3f)
        assertEquals(1f, b[Matrix.MSCALE_X], 1e-4f)
        assertEquals(500f, b[Matrix.MTRANS_X], 1e-3f)
        assertTrue(l.drawsOneToOne && r.drawsOneToOne)
    }

    @Test
    fun theRightPageIsBroughtToTheLeftOnesHeight() {
        val (l, r) = pair(1000, 600, intArrayOf(400, 600), intArrayOf(420, 630))
        val a = values(l)
        val b = values(r)
        // Both 600 px tall on screen, top edges level.
        assertEquals(600f, 630 * b[Matrix.MSCALE_Y], 1e-2f)
        assertEquals(a[Matrix.MTRANS_Y], b[Matrix.MTRANS_Y], 1e-3f)
        assertEquals(a[Matrix.MTRANS_X] + 400 * a[Matrix.MSCALE_X], b[Matrix.MTRANS_X], 1e-3f)
    }

    @Test
    fun zoomingAndPanningMoveBothPages() {
        val (l, r) = pair(1000, 600, intArrayOf(400, 600), intArrayOf(400, 600))
        l.zoomTo(2f, 500f, 300f)
        val a = values(l)
        val b = values(r)
        assertEquals(2f, a[Matrix.MSCALE_X], 1e-4f)
        assertEquals(2f, b[Matrix.MSCALE_X], 1e-4f)
        // Zoomed at the gutter, it stays where it was.
        assertEquals(500f, a[Matrix.MTRANS_X] + 400 * 2, 1e-3f)
        assertEquals(500f, b[Matrix.MTRANS_X], 1e-3f)
        assertTrue(l.isZoomed && r.isZoomed)
        // The picture pans as one up to its outer edges, not the gutter.
        val t = SystemClock.uptimeMillis()
        l.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 300f, 0))
        l.onTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, 900f, 300f, 0))
        l.onTouchEvent(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_UP, 900f, 300f, 0))
        assertEquals(0f, values(l)[Matrix.MTRANS_X], 1e-3f)
        assertEquals(800f, values(r)[Matrix.MTRANS_X], 1e-3f)
    }

    @Test
    fun aPageThatArrivesLaterJoinsTheLayout() {
        val l = view(1000, 600)
        val r = view(1000, 600)
        l.joinWith(r)
        // Only the right page so far: laid out as if the left one were alike.
        r.setImageBitmap(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))
        r.setFitMode(ZoomImageView.FitMode.SCREEN)
        assertEquals(500f, values(r)[Matrix.MTRANS_X], 1e-3f)
        l.setImageBitmap(Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888))
        l.setFitMode(ZoomImageView.FitMode.SCREEN)
        assertEquals(150f, values(l)[Matrix.MTRANS_X], 1e-3f)
        assertEquals(450f, values(r)[Matrix.MTRANS_X], 1e-3f)
    }
}
