package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.DecodeException
import com.infinity.comicreader.reader.PageSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThumbAdapterTest {

    /** Page 1 never decodes; the others are 1x1 bitmaps. */
    private class BrokenPageSource : PageSource {
        override val pageCount: Int = 2
        override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
            if (index == 1) throw DecodeException("Could not decode this JXL image")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun pageName(index: Int): String = "chapter/page-$index.jxl"
        override fun close() {}
    }

    @Test
    fun failedThumbnailShowsPlaceholder() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_ComicReader)
        val adapter = ThumbAdapter(BrokenPageSource(), currentPage = 0) {}
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 400, 2000)
        }
        fun image(position: Int) =
            (list.findViewHolderForAdapterPosition(position) as ThumbAdapter.Holder).image

        val deadline = System.currentTimeMillis() + 5_000
        while ((image(0).drawable == null || image(1).drawable == null) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(image(0).drawable is BitmapDrawable)
        assertTrue(image(1).drawable is ErrorPageDrawable)
        adapter.release()
    }
}
