package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Box
import com.infinity.comicreader.reader.Half
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A page not decoded yet shows its kept small preview, cut as the page shows, until the page itself takes over. */
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

    /** A whole page's preview, its left half red and its right half blue. */
    private fun halves(): Bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 8) for (y in 0 until 6) setPixel(x, y, if (x < 4) Color.RED else Color.BLUE)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aHalfShowsItsHalfOfThePreview() {
        val source = Source(halves())
        // One file split in halves, each a screen, then a page alone.
        val layout = PageLayout(false, views = listOf(listOf(PageLayout.Part(0, Half.LEFT)), listOf(PageLayout.Part(0, Half.RIGHT)), listOf(PageLayout.Part(1))))
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0, pageLayout = layout)
        val left = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(left, 0)
        val right = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(right, 1)
        idleUntil { shown(left) != null && shown(right) != null }
        assertEquals(4, shown(left)?.width)
        assertEquals(6, shown(left)?.height)
        assertEquals(Color.RED, shown(left)?.getPixel(0, 0))
        assertEquals(4, shown(right)?.width)
        assertEquals(Color.BLUE, shown(right)?.getPixel(0, 0))
        source.gate.countDown()
        adapter.release()
    }

    @Test
    fun aPageAutoCropStillMeasuresShowsNoPreview() {
        val source = Source(Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888))
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, autoCrop = true, preload = 0)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        repeat(20) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        // Uncropped, it would jump once the page shows cropped.
        assertNull(shown(holder))
        source.gate.countDown()
        idleUntil { shown(holder)?.width == 40 }
        assertEquals(40, shown(holder)?.width)
        adapter.release()
    }

    @Test
    fun aBoxCutsThePreviewThenItsHalf() {
        val preview = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        assertSame(preview, PageAdapter.previewPart(preview, Target(10, 10)))
        val boxed = PageAdapter.previewPart(preview, Target(10, 10, box = Box(0.1, 0.0, 0.9, 0.5), half = Half.RIGHT))
        assertEquals(40, boxed.width)
        assertEquals(100, boxed.height)
        assertTrue(PageAdapter.previewShows(Target(10, 10, autoCrop = true, box = Box(0.1, 0.0, 0.9, 0.5))))
        assertFalse(PageAdapter.previewShows(Target(10, 10, autoCrop = true)))
    }

    /** Pages read in any order, each with a quick preview read from the book; pages [held] wait for their latches. */
    private class QuickSource : PageSource {
        override val pageCount: Int get() = 4
        val quick: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val started: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val held = (0 until 4).associateWith { CountDownLatch(if (it == 0) 0 else 1) }

        override fun loadPage(index: Int, target: Target): Bitmap {
            started += index
            held.getValue(index).await(5, TimeUnit.SECONDS)
            return Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        }

        override fun loadPreview(index: Int): Bitmap {
            quick += index
            return Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    @Test
    fun aQuickPreviewIsReadOnOpeningOrWhileThePageWaitsForADecoder() {
        val source = QuickSource()
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val first = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(first, 0)
        idleUntil { shown(first)?.width == 40 && 0 in source.quick }
        // Pages 1 and 2 decode at once, with both decoders: no quick preview races them.
        for (page in 1..2) {
            adapter.onBindViewHolder(adapter.onCreateViewHolder(FrameLayout(context), 0), page)
            idleUntil { page in source.started }
        }
        // Page 3 waits for a decoder: its quick preview shows meanwhile.
        val waiting = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(waiting, 3)
        idleUntil { shown(waiting) != null }
        assertEquals(4, shown(waiting)?.width)
        assertEquals(listOf(0, 3), source.quick.toList())
        source.held.values.forEach { it.countDown() }
        idleUntil { shown(waiting)?.width == 40 }
        adapter.release()
    }
}
