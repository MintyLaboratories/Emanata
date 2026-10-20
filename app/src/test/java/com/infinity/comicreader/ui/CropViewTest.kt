package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CropViewTest {

    private fun view(w: Int, h: Int, bitmapW: Int, bitmapH: Int): CropView =
        CropView(ApplicationProvider.getApplicationContext<Application>()).apply {
            setBitmap(Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888))
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }

    @Test
    fun pageIsShownAtItsOnScreenSize() {
        assertArrayEquals(intArrayOf(1000, 1333), view(1000, 1600, 2400, 3200).previewSize)
        // A bitmap that already fits is shown as it is.
        assertArrayEquals(intArrayOf(800, 600), view(800, 1600, 800, 600).previewSize)
    }

    @Test
    fun cropMapsBackToSourcePixels() {
        // The initial crop is inset by a tenth of the shown page on every side.
        val crop = view(1000, 1600, 2400, 3200).bitmapCropRect()
        assertEquals(240f, crop.left.toFloat(), 2f)
        assertEquals(320f, crop.top.toFloat(), 2f)
        assertEquals(2160f, crop.right.toFloat(), 2f)
        assertEquals(2880f, crop.bottom.toFloat(), 2f)
    }
}
