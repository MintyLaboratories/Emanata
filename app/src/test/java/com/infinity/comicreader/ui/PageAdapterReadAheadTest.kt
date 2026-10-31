package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    /** Loads that wait while [holds] says so, until [open]; each load started is listed with its target. */
    private class GatedSource(override val pageCount: Int, private val holds: (Int, Target) -> Boolean) : PageSource {
        val started: MutableList<Pair<Int, Target>> = Collections.synchronizedList(ArrayList())
        private val gate = CountDownLatch(1)

        fun open() = gate.countDown()

        override fun loadPage(index: Int, target: Target): Bitmap {
            started += index to target
            if (holds(index, target)) gate.await(5, TimeUnit.SECONDS)
            return Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    private fun recycler(adapter: PageAdapter): RecyclerView =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            // Without change animations, whose pre-layout binds the next item too.
            itemAnimator = null
            this.adapter = adapter
            layOut(this)
        }

    private fun layOut(rv: RecyclerView) {
        rv.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        rv.layout(0, 0, 1200, 800)
    }

    /** Idles [rv] until [done] or a while passes, laying it out again meanwhile, as a window would after items change. */
    private fun idleUntil(rv: RecyclerView, done: () -> Boolean = { false }, ms: Long = 5_000) {
        val deadline = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
            layOut(rv)
        }
    }

    @Test
    fun withAutoCropReadingAheadWaitsForTheFirstPairsMeasureAndDecode() {
        // Page 1's crop measure (the load without a crop box) is held.
        val source = GatedSource(6) { page, target -> page == 1 && target.box == null }
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, autoCrop = true, preload = 2,
            pageLayout = PageLayout.of(listOf(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5)), spread = true, rtl = false, joined = emptySet())
        )
        val rv = recycler(adapter)
        idleUntil(rv, { source.started.any { it.first == 1 } })
        // Page 0's measure ends meanwhile; nothing after the pair starts.
        idleUntil(rv, ms = 300)
        assertTrue(source.started.toList().toString(), source.started.all { it.first <= 1 })

        source.open()
        idleUntil(rv, { source.started.any { it.first >= 2 } })
        // The pair's own decodes, with their shared crop, came before reading ahead.
        val firstAhead = source.started.indexOfFirst { it.first >= 2 }
        assertTrue(firstAhead > 0)
        assertEquals(setOf(0, 1), source.started.take(firstAhead).filter { it.second.box != null }.map { it.first }.toSet())
        adapter.release()
    }
}
