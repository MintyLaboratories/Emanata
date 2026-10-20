package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.DecodeException
import com.infinity.comicreader.reader.PageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterFailureTest {

    /** Page 1 never decodes; the others are 1x1 bitmaps. */
    private class BrokenPageSource : PageSource {
        val loads = AtomicInteger()
        override val pageCount: Int = 3
        override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
            loads.incrementAndGet()
            if (index == 1) throw DecodeException("Could not decode this JXL image")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun pageName(index: Int): String = "chapter/page-$index.jxl"
        override fun close() {}
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun bindAndWait(adapter: PageAdapter, position: Int): PageAdapter.PageHolder {
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, position)
        val deadline = System.currentTimeMillis() + 5_000
        while (holder.images[0].drawable == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return holder
    }

    @Test
    fun failedPageShowsPlaceholderAndIsNotRetried() {
        val source = BrokenPageSource()
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = {}, doubleTapZoom = false, preload = 0)

        val holder = bindAndWait(adapter, 1)
        assertTrue(holder.images[0].drawable is ErrorPageDrawable)
        val loads = source.loads.get()

        // Binding again shows the placeholder straight away, without a second decode.
        val again = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(again, 1)
        assertTrue(again.images[0].drawable is ErrorPageDrawable)
        assertEquals(loads, source.loads.get())
        adapter.release()
    }

    @Test
    fun goodPageShowsItsBitmap() {
        val adapter = PageAdapter(source = BrokenPageSource(), mode = PageAdapter.Mode.PAGED, onTap = {}, doubleTapZoom = false, preload = 0)
        val holder = bindAndWait(adapter, 0)
        assertTrue(holder.images[0].drawable is BitmapDrawable)
        adapter.release()
    }

    @Test
    fun placeholderHasPageShapeAndDraws() {
        val d = ErrorPageDrawable("Could not load the page", "a/very/long/path/to/page.jxl", "Could not decode this JXL image")
        assertTrue(d.intrinsicHeight > d.intrinsicWidth)
        val bitmap = Bitmap.createBitmap(200, 283, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 200, 283)
        d.draw(Canvas(bitmap))
    }
}
