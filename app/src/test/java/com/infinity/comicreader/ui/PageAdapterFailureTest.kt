package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.DecodeException
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.awaitUntil
import com.infinity.comicreader.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterFailureTest {

    /** Page 1 never decodes; the others are 1x1 bitmaps. */
    private class BrokenPageSource : PageSource {
        val loads = AtomicInteger()
        val perPage = AtomicIntegerArray(3)
        val done = AtomicIntegerArray(3)
        override val pageCount: Int = 3
        override fun loadPage(index: Int, target: Target): Bitmap {
            loads.incrementAndGet()
            perPage.incrementAndGet(index)
            try {
                if (index == 1) throw DecodeException("Could not decode this JXL image")
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            } finally {
                done.incrementAndGet(index)
            }
        }

        override fun pageName(index: Int): String = "chapter/page-$index.jxl"
        override fun close() {}
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun bindAndWait(adapter: PageAdapter, position: Int): PageAdapter.PageHolder {
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, position)
        idleUntil("page $position shown") { holder.images[0].drawable != null }
        return holder
    }

    @Test
    fun failedPageShowsPlaceholderAndIsNotRetried() {
        val source = BrokenPageSource()
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)

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

    /** Idles until no decode of [adapter] is under way: each one's end handled on the main thread, where decodes are asked for. */
    private fun settled(adapter: PageAdapter) = idleUntil("the decodes under way to end") { !adapter.decoding }

    @Test
    fun prefetchSkipsFailedPage() {
        val source = BrokenPageSource()
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 1)

        // Showing page 0 prefetches page 1, which fails.
        bindAndWait(adapter, 0)
        idleUntil("page 1's decode to fail") { source.done.get(1) == 1 }
        settled(adapter)
        assertEquals(1, source.perPage.get(1))

        // Showing page 0 again must not decode page 1 a second time.
        bindAndWait(adapter, 0)
        settled(adapter)
        assertEquals(1, source.perPage.get(1))
        adapter.release()
    }

    /** Fails its only page once with [error], then decodes it. */
    private class FlakySource(private val error: Throwable) : PageSource {
        val loads = AtomicInteger()
        val failed = AtomicInteger()
        override val pageCount: Int = 1
        override fun loadPage(index: Int, target: Target): Bitmap {
            if (loads.incrementAndGet() == 1) {
                failed.incrementAndGet()
                throw error
            }
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    private fun assertRetriedAfter(error: Throwable) {
        val source = FlakySource(error)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        assertTrue(bindAndWait(adapter, 0).images[0].drawable is ErrorPageDrawable)
        assertTrue(bindAndWait(adapter, 0).images[0].drawable is BitmapDrawable)
        assertEquals(2, source.loads.get())
        adapter.release()
    }

    @Test
    fun readErrorIsRetriedOnNextBind() = assertRetriedAfter(IOException("Read timed out"))

    @Test
    fun lowMemoryIsRetriedOnNextBind() =
        assertRetriedAfter(DecodeException("Not enough memory for this JPEG image", transient = true))

    @Test
    fun transientFailureIsRetriedBeforeItsDecodeIsForgotten() {
        val source = FlakySource(IOException("Read timed out"))
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        adapter.onBindViewHolder(adapter.onCreateViewHolder(FrameLayout(context), 0), 0)
        // The decode fails; the main thread has not yet run its completion when the page binds again.
        awaitUntil("the failed decode to end") { source.failed.get() == 1 && adapter.running == 0 }
        val holder = bindAndWait(adapter, 0)
        assertTrue(holder.images[0].drawable is BitmapDrawable)
        assertEquals(2, source.loads.get())
        adapter.release()
    }

    /** The first decode waits for [release], then fails; later ones succeed. */
    private class StalledSource : PageSource {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val done = AtomicInteger()
        override val pageCount: Int = 1
        override fun loadPage(index: Int, target: Target): Bitmap {
            try {
                if (loads.incrementAndGet() == 1) {
                    entered.countDown()
                    release.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
                    throw DecodeException("Could not decode this JPEG image")
                }
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            } finally {
                done.incrementAndGet()
            }
        }

        override fun close() {}
    }

    @Test
    fun failureFromOldDecodeSizeIsDropped() {
        val source = StalledSource()
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        adapter.onBindViewHolder(adapter.onCreateViewHolder(FrameLayout(context), 0), 0)
        assertTrue(source.entered.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS))

        // The decode size changes while the old decode is still running, then it fails.
        adapter.fitMode = ZoomImageView.FitMode.WIDTH
        source.release.countDown()
        idleUntil("the stalled decode to fail") { source.done.get() == 1 }
        settled(adapter)

        val holder = bindAndWait(adapter, 0)
        assertTrue(holder.images[0].drawable is BitmapDrawable)
        assertEquals(2, source.loads.get())
        adapter.release()
    }

    /** The first decode fails at once; the second waits for [release], then decodes. */
    private class FailThenStallSource : PageSource {
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val done = AtomicInteger()
        override val pageCount: Int = 1
        override fun loadPage(index: Int, target: Target): Bitmap {
            try {
                val n = loads.incrementAndGet()
                if (n == 1) throw IOException("Read timed out")
                release.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            } finally {
                done.incrementAndGet()
            }
        }

        override fun close() {}
    }

    @Test
    fun oldWaiterLeavingDoesNotCancelANewerDecode() {
        val source = FailThenStallSource()
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val first = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(first, 0)
        // The first decode fails; before its holder hears of it, another holder starts a new one.
        awaitUntil("the first decode to end") { source.done.get() == 1 && adapter.running == 0 }
        val second = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(second, 0)
        idleUntil("the second decode to start") { source.loads.get() == 2 }

        // The first holder leaving must not abandon the decode the second waits for.
        adapter.onViewRecycled(first)
        source.release.countDown()
        idleUntil("the second holder's page shown") { second.images[0].drawable != null }
        assertTrue(second.images[0].drawable is BitmapDrawable)
        adapter.release()
    }

    @Test
    fun goodPageShowsItsBitmap() {
        val adapter = PageAdapter(source = BrokenPageSource(), mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
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
