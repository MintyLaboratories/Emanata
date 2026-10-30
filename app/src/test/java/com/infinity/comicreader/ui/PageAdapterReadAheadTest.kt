package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reading ahead waits for the first pages shown after opening, then runs beside the pages shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterReadAheadTest {

    /** Pages whose loads wait until their latch opens ([hold]); each load started is listed in order. */
    private class HeldSource(override val pageCount: Int) : PageSource {
        val started: MutableList<Int> = Collections.synchronizedList(ArrayList())
        private val held = ConcurrentHashMap<Int, CountDownLatch>()

        fun hold(page: Int) = CountDownLatch(1).also { held[page] = it }

        override fun loadPage(index: Int, target: Target): Bitmap {
            started += index
            held[index]?.await(5, TimeUnit.SECONDS)
            return Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        }

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

    /** Lets work that is not due run for a while, then checks [check] still holds. */
    private fun stillAfterAWhile(check: () -> Boolean) {
        repeat(20) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(check())
    }

    @Test
    fun theFirstPageShownDecodesAloneThenReadingAheadRunsBesideTurns() {
        val source = HeldSource(8)
        val first = source.hold(0)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 2)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)

        idleUntil { source.started.isNotEmpty() }
        stillAfterAWhile { source.started.toList() == listOf(0) }

        first.countDown()
        idleUntil { source.started.size >= 3 }
        assertEquals(listOf(0, 1, 2), source.started.toList().sorted())

        // Turned to a page not read yet: its decode and the next ones run at once.
        val later = source.hold(5)
        val next = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(next, 5)
        idleUntil { 6 in source.started }
        assertTrue(5 in source.started)
        later.countDown()
        adapter.release()
    }
}
