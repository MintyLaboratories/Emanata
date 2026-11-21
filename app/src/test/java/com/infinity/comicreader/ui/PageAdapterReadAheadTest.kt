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
import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Reading ahead waits for the first pages shown after opening, then runs beside the pages shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterReadAheadTest {

    /** Pages whose loads wait until their latch opens ([hold]); each load started is listed in order. */
    private class HeldSource(override val pageCount: Int) : PageSource {
        private val loads = ArrayList<Int>()

        /** A copy, safe to read while decoders add to it. */
        val started: List<Int> get() = synchronized(loads) { loads.toList() }
        private val held = ConcurrentHashMap<Int, CountDownLatch>()
        private val waiting = AtomicInteger()

        /** How many loads wait at their latch now. */
        val blocked: Int get() = waiting.get()

        fun hold(page: Int) = CountDownLatch(1).also { held[page] = it }

        override fun loadPage(index: Int, target: Target): Bitmap {
            synchronized(loads) { loads += index }
            held[index]?.let { latch ->
                waiting.incrementAndGet()
                try {
                    latch.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
                } finally {
                    waiting.decrementAndGet()
                }
            }
            return Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /**
     * Idles until no load can start before the test opens a latch: every decode under way, or
     * every decoder, waits at one. Decodes are asked for on the main thread only, and one asked
     * for is under way until it ends; given a decoder, it starts, and is listed.
     */
    private fun stalled(adapter: PageAdapter, source: HeldSource) =
        idleUntil("every decode under way, or every decoder, to wait at a held page") {
            source.blocked == adapter.running || source.blocked == PageAdapter.decodeThreads
        }

    /** Idles until no decode is under way: each one's end handled on the main thread, where decodes are asked for. */
    private fun settled(adapter: PageAdapter) = idleUntil("the decodes under way to end") { !adapter.decoding }

    @Test
    fun theFirstPageShownDecodesAloneThenReadingAheadRunsBesideTurns() {
        val source = HeldSource(8)
        val first = source.hold(0)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 2)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)

        idleUntil("page 0's decode to start") { source.started.isNotEmpty() }
        stalled(adapter, source)
        assertEquals(listOf(0), source.started.toList())

        first.countDown()
        idleUntil("pages 1 and 2 to start") { source.started.size >= 3 }
        assertEquals(listOf(0, 1, 2), source.started.toList().sorted())

        // Turned to a page not read yet: its decode and the next ones run at once.
        val later = source.hold(5)
        val next = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(next, 5)
        idleUntil("page 6 to start") { 6 in source.started }
        assertTrue(5 in source.started)
        later.countDown()
        adapter.release()
    }

    /** Loads that wait while [holds] says so, until [open]; each load started is listed with its target. */
    private class GatedSource(override val pageCount: Int, private val holds: (Int, Target) -> Boolean) : PageSource {
        private val loads = ArrayList<Pair<Int, Target>>()

        /** A copy, safe to read while decoders add to it. */
        val started: List<Pair<Int, Target>> get() = synchronized(loads) { loads.toList() }
        private val gate = CountDownLatch(1)

        fun open() = gate.countDown()

        override fun loadPage(index: Int, target: Target): Bitmap {
            synchronized(loads) { loads += index to target }
            if (holds(index, target)) gate.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
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

    /** Idles [rv] until [done], laying it out again meanwhile, as a window would after items change; throws naming [what] if it never holds. */
    private fun idleLaidOut(rv: RecyclerView, what: String, done: () -> Boolean) = idleUntil(what, step = { layOut(rv) }, done = done)

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
        // A measure's end binds its one item again, then reads ahead if it may.
        val changed = ArrayList<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                if (itemCount == 1) changed += positionStart
            }
        })
        idleLaidOut(rv, "page 1's measure to start") { source.started.any { it.first == 1 } }
        // Page 0's measure ends meanwhile; nothing after the pair starts, nor is asked for:
        // no decode runs and no measure but page 1's (reading ahead would add measures at once).
        idleLaidOut(rv, "page 0's measure handled, only page 1's measure left") { 0 in changed && adapter.running == 0 && adapter.measures == 1 }
        assertTrue(source.started.toList().toString(), source.started.all { it.first <= 1 })

        source.open()
        idleLaidOut(rv, "reading ahead to start") { source.started.any { it.first >= 2 } }
        // The pair's own decodes, with their shared crop, came before reading ahead.
        val started = source.started
        val firstAhead = started.indexOfFirst { it.first >= 2 }
        assertTrue(firstAhead > 0)
        assertEquals(setOf(0, 1), started.take(firstAhead).filter { it.second.box != null }.map { it.first }.toSet())
        adapter.release()
    }

    @Test
    fun withBehindTheScreenBeforeDecodesAfterThoseAhead() {
        val source = HeldSource(8)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 2, behind = true)
        val ahead = listOf(source.hold(5), source.hold(6))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 4)
        // Both decoders busy with the pages ahead: the one behind waits its turn.
        idleUntil("pages 4 to 6 to start") { source.started.size >= 3 }
        stalled(adapter, source)
        assertEquals(listOf(4, 5, 6), source.started.toList().sorted())
        ahead.forEach { it.countDown() }
        idleUntil("page 3 to start") { 3 in source.started }
        settled(adapter)
        assertEquals(listOf(3, 4, 5, 6), source.started.toList().sorted())
        adapter.release()
    }

    /** Runs [block] with adapters made to decode one page at a time. */
    private fun withOneDecoder(block: () -> Unit) {
        val threads = PageAdapter.decodeThreads
        PageAdapter.decodeThreads = 1
        try {
            block()
        } finally {
            PageAdapter.decodeThreads = threads
        }
    }

    @Test
    fun oneDecoderTakesPagesInTheOrderTheyAreAskedFor() = withOneDecoder {
        val source = HeldSource(8)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 2, behind = true)
        val ahead = source.hold(5)
        adapter.onBindViewHolder(adapter.onCreateViewHolder(FrameLayout(context), 0), 4)
        // Read ahead asks for 5, 6, then 3 behind; 6 and 3 wait for the decoder 5 holds.
        idleUntil("page 5 to start") { 5 in source.started }
        stalled(adapter, source)
        assertEquals(listOf(4, 5), source.started)
        ahead.countDown()
        idleUntil("pages 6 and 3 to start") { source.started.size >= 4 }
        assertEquals(listOf(4, 5, 6, 3), source.started)
        adapter.release()
    }

    @Test
    fun aPairsCropMeasuresTakeTheDecoderBeforeReadingAheadAskedForAfterThem() = withOneDecoder {
        val source = GatedSource(4) { _, _ -> false }
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, autoCrop = true, preload = 3,
            pageLayout = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3)), spread = true, rtl = false, joined = emptySet())
        )
        val rv = recycler(adapter)
        // After page 0, reading ahead asks for the crops of pair 1-2, then for page 3.
        idleLaidOut(rv, "four loads to start") { source.started.size >= 4 }
        val started = source.started.take(4)
        assertEquals(started.toString(), listOf(0, 1, 2, 3), started.map { it.first })
        assertTrue(started.toString(), started.slice(1..2).all { it.second.box == null })
        adapter.release()
    }

    /** Pages that load at once; counts its closes. */
    private class ClosingSource(override val pageCount: Int) : PageSource {
        @Volatile
        var closes = 0

        override fun loadPage(index: Int, target: Target): Bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)

        override fun close() {
            closes++
        }
    }

    @Test
    fun aMeasureReleasedInTheBindsPassStillClosesTheSource() {
        val source = ClosingSource(4)
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, autoCrop = true, preload = 0,
            pageLayout = PageLayout.of(listOf(intArrayOf(0, 1), intArrayOf(2, 3)), spread = true, rtl = false, joined = emptySet())
        )
        // The pair's bind asks for its crop; the adapter is released before the main thread idles.
        adapter.onBindViewHolder(adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0)), 0)
        adapter.release()
        idleUntil("the source closed") { source.closes > 0 }
        assertEquals(1, source.closes)
    }

    @Test
    fun withoutBehindNothingBeforeTheScreenDecodes() {
        val source = HeldSource(8)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 2)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 4)
        idleUntil("pages 4 to 6 to start") { source.started.size >= 3 }
        settled(adapter)
        assertEquals(listOf(4, 5, 6), source.started.toList().sorted())
        adapter.release()
    }

    @Test
    fun awaitingIdleReturnsOnceTheDecodesEnd() {
        val source = HeldSource(4)
        val first = source.hold(0)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        idleUntil("page 0's decode to start") { source.started.isNotEmpty() }
        assertTrue(adapter.decoding)
        var idle = false
        val waiting = MainScope().launch {
            adapter.awaitIdle()
            idle = true
        }
        // Page 0 is held, so its decode cannot end: once the wait runs, it still waits.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(idle)
        first.countDown()
        idleUntil("the wait for idle to return") { idle }
        assertTrue(idle)
        assertFalse(adapter.decoding)
        waiting.cancel()
        adapter.release()
    }

    /** Pages of the list's size, 1080 x 1920 until laid out: each counts its loads. */
    private class ScreenSource(override val pageCount: Int) : PageSource {
        val loads = IntArray(pageCount)

        override fun loadPage(index: Int, target: Target): Bitmap {
            synchronized(loads) { loads[index]++ }
            return Bitmap.createBitmap(target.width.coerceAtLeast(1), target.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        override fun close() {}

        fun total() = synchronized(loads) { loads.sum() }
    }

    private val screen = 1080L * 1920 * 4

    /** Turns forward from [from] [turns] times, one holder per screen, as a pager binds them; idles until [decodes] in all. */
    private fun turnForward(adapter: PageAdapter, source: ScreenSource, from: Int, turns: Int, decodes: (Int) -> Int) {
        for (i in 0..turns) {
            adapter.onBindViewHolder(adapter.onCreateViewHolder(FrameLayout(context), 0), from + i)
            idleUntil("turn $i's decodes put away") { source.total() >= decodes(i) && !adapter.decoding }
        }
        // Decodes are asked for on the main thread only: none is, once it idles again.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(adapter.decoding)
    }

    @Test
    fun theCacheGrowsToHoldThePagesAheadAndBehind() {
        val source = ScreenSource(16)
        // A sixth of it holds four screens: one shown, three ahead, none behind.
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 3, behind = true)
        adapter.heapBytes = 24 * screen
        // Opened at 5: 5, 6, 7 and 8, then 4 behind; each turn decodes one page more, nothing again.
        turnForward(adapter, source, from = 5, turns = 4) { 5 + it }
        assertEquals(9, source.total())
        assertTrue(source.loads.all { it <= 1 })
        adapter.release()
    }

    @Test
    fun withoutRoomForTheScreenBehindItIsNotDecoded() {
        val source = ScreenSource(16)
        // Half of it holds five screens: one shown, three ahead and a spare, none behind.
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 3, behind = true)
        adapter.heapBytes = 10 * screen
        turnForward(adapter, source, from = 5, turns = 4) { 4 + it }
        assertEquals(8, source.total())
        assertEquals(0, source.loads[4])
        assertTrue(source.loads.all { it <= 1 })
        adapter.release()
    }

    @Test
    fun theCacheHoldsAScreenShownTheReadAheadOneBehindAndASpare() {
        val mb = 1_000_000L
        // One page a screen: 1 + 2 + 1 + 1 pages; two a screen: 2 + 2 + 2 + 1 half pages.
        assertEquals(5 * 20 * mb, PageAdapter.cacheFor(mb, 1000 * mb, 20 * mb, 1, 2, behind = true))
        assertEquals(7 * 10 * mb, PageAdapter.cacheFor(mb, 1000 * mb, 20 * mb, 2, 2, behind = true))
        assertEquals(4 * 20 * mb, PageAdapter.cacheFor(mb, 1000 * mb, 20 * mb, 1, 2, behind = false))
        // Never under the base, nor over the cap above it.
        assertEquals(500 * mb, PageAdapter.cacheFor(500 * mb, 1000 * mb, 20 * mb, 1, 2, behind = true))
        assertEquals(90 * mb, PageAdapter.cacheFor(mb, 90 * mb, 20 * mb, 1, 2, behind = true))
    }
}
