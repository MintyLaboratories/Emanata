package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun scaledBitmapIsFiltered() {
        assertFalse(view(1848, 2960, 924, 1386, ZoomImageView.FitMode.SCREEN).drawsOneToOne)
    }
}
