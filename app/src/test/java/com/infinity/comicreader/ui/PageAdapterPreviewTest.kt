package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A page not decoded yet shows its kept small preview until the page itself takes over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterPreviewTest {

    private class Source(private val kept: Bitmap?) : PageSource {
        override val pageCount: Int get() = 3
        val gate = CountDownLatch(1)
        val page: Bitmap get() = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)

        override fun loadPage(index: Int, target: Target): Bitmap {
            gate.await(5, TimeUnit.SECONDS)
            return page
        }

        override fun keptPreview(index: Int): Bitmap? = kept

        // Read in order only: no quick preview is read from the book.
        override val cheapSizes: Boolean get() = false

        override fun close() {}
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun shown(holder: PageAdapter.PageHolder): Bitmap? = (holder.images[0].drawable as? BitmapDrawable)?.bitmap

    @Test
    fun theKeptPreviewShowsWhileThePageDecodes() {
        val preview = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val source = Source(preview)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        idleUntil { shown(holder) != null }
        assertSame(preview, shown(holder))
        source.gate.countDown()
        idleUntil { shown(holder)?.width == 40 }
        assertEquals(40, shown(holder)?.width)
        adapter.release()
    }

    @Test
    fun withoutAPreviewNothingShowsUntilThePage() {
        val source = Source(null)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        repeat(20) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertNull(shown(holder))
        source.gate.countDown()
        idleUntil { shown(holder)?.width == 40 }
        assertEquals(40, shown(holder)?.width)
        adapter.release()
    }
}
