package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterReleaseTest {

    private class FakeSource(override val pageCount: Int) : PageSource {
        var closed = false
        override fun loadPage(index: Int, target: Target): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun close() {
            closed = true
        }
    }

    private fun adapter(source: PageSource, mode: PageAdapter.Mode = PageAdapter.Mode.PAGED) =
        PageAdapter(source = source, mode = mode, onTap = {}, doubleTapZoom = true)

    @Test
    fun releaseClosesChapters() {
        val source = FakeSource(4)
        adapter(source).release()
        assertTrue(source.closed)
    }

    @Test
    fun rebuildKeepsChaptersOpen() {
        val source = FakeSource(4)
        val ahead = FakeSource(3)
        val old = adapter(source, PageAdapter.Mode.LONG_STRIP)
        old.append(ahead)
        old.release(closeSources = false)
        assertFalse(source.closed)
        assertFalse(ahead.closed)

        // The replacement takes the same chapters over and closes them in the end.
        val next = adapter(source, PageAdapter.Mode.LONG_STRIP)
        next.append(ahead)
        next.release()
        assertTrue(source.closed)
        assertTrue(ahead.closed)
    }
}
