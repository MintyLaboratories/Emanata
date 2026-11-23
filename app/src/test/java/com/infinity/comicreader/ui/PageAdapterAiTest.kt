package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.AiOutcome
import com.infinity.comicreader.ai.Fallback
import com.infinity.comicreader.ai.PageEnhancer
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Pages shown plain at once, then swapped in place for their AI version, the page read first, then the next. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAdapterAiTest {
    private val plain = Color.rgb(200, 200, 200)
    private val processed = Color.rgb(10, 120, 30)

    private class Source(override val pageCount: Int, private val color: Int) : PageSource {
        /** Pages whose loads wait until their latch opens. */
        val held = ConcurrentHashMap<Int, CountDownLatch>()

        override fun loadPage(index: Int, target: Target): Bitmap {
            held[index]?.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
            val plan = target.plan(1000.0, 1500.0)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }

        override fun close() {}
    }

    /** Makes every page [color] at the size asked, listing the pages asked for; [on] false makes it process none. */
    private class Enhancer(
        private val color: Int,
        // On the main looper: the work runs as the tests idle it, with no thread of its own to wait for.
        override val worker: CoroutineDispatcher = Dispatchers.Main
    ) : PageEnhancer {
        val asked: MutableList<Int> = Collections.synchronizedList(ArrayList())

        /** What each page's work was told to stop on, by page, as last asked. */
        val canceled = ConcurrentHashMap<Int, () -> Boolean>()

        /** Runs as each page is processed, before it is done. */
        @Volatile
        var onEnhance: (Int) -> Unit = {}

        @Volatile
        var on = true

        /** What [pageMs] says. */
        @Volatile
        var ms = 0L

        /** Pages [stored] says are kept on disk: read back without a model. */
        val kept: MutableSet<Int> = Collections.synchronizedSet(HashSet())

        /** True: every page is skipped as needing no model, none of its models opened. */
        @Volatile
        var unneeded = false

        override fun enhances(source: PageSource): Boolean = on

        override fun pageMs(source: PageSource): Long = ms

        override fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int): Boolean = index in kept

        override fun enhance(
            source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean, models: () -> Unit,
            detailBytes: Long, remake: Boolean, canceled: () -> Boolean
        ): AiOutcome {
            asked += index
            this.canceled[index] = canceled
            if (!on || unneeded) return AiOutcome.Skipped(Fallback.UNNEEDED)
            if (index !in kept) models()
            onEnhance(index)
            return AiOutcome.Done(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        }

        fun close() = Unit
    }

    /** Runs the first work inline, as a worker that ends a page before the main thread waits for it; later work on the main looper. */
    private class InlineOnce : CoroutineDispatcher() {
        private val inline = AtomicBoolean(true)
        private val main = Handler(Looper.getMainLooper())

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !inline.getAndSet(false)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            main.post(block)
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val enhancer = Enhancer(processed)

    @After
    fun tearDown() {
        enhancer.close()
    }

    /**
     * Idles until no decode of [adapter] is under way. Decodes are asked for on the main thread
     * only, AI work runs on it here, and a decode is under way until the main thread has handled
     * its end: once none is, nothing more is processed until the test acts.
     */
    private fun settled(adapter: PageAdapter) = idleUntil("the decodes under way to end") { !adapter.decoding }

    private fun RecyclerView.layOut(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun shownBitmap(rv: RecyclerView, position: Int): Bitmap? =
        ((rv.findViewHolderForAdapterPosition(position) as? PageAdapter.PageHolder)?.images?.get(0)?.drawable as? BitmapDrawable)?.bitmap

    private fun colorAt(rv: RecyclerView, position: Int): Int? = shownBitmap(rv, position)?.let { it.getPixel(it.width / 2, it.height / 2) }

    private fun reader(preload: Int = 1, pages: Int = 4, enhancer: Enhancer = this.enhancer): Pair<PageAdapter, RecyclerView> {
        val adapter = PageAdapter(
            source = Source(pages, plain), mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            preload = preload, enhancer = enhancer
        )
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(600, 900)
        }
        return adapter to rv
    }

    @Test
    fun thePlainPageShowsThenTheProcessedOneTakesItsPlaceAtTheSameSize() {
        val (adapter, rv) = reader()
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        val before = shownBitmap(rv, 0)!!
        adapter.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        val after = shownBitmap(rv, 0)!!
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        adapter.release()
    }

    @Test
    fun zoomsSharpenThePlainPageButNotTheProcessedOne() {
        val (adapter, rv) = reader()
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        assertTrue(image.sharpensPage)
        adapter.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        assertFalse(image.sharpensPage)
        adapter.release()
    }

    @Test
    fun thePageReadGoesFirstThenTheNextAndNoFurther() {
        val (adapter, rv) = reader(preload = 2)
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        // Reading ahead decodes the pages after it.
        settled(adapter)
        adapter.reading(0)
        idleUntil("two pages processed") { enhancer.asked.size >= 2 }
        settled(adapter)
        assertEquals(listOf(0, 1), enhancer.asked.toList())
        adapter.release()
    }

    @Test
    fun nothingIsProcessedUntilThePageReadHasDecoded() {
        val source = Source(4, plain)
        val held = CountDownLatch(1).also { source.held[0] = it }
        val loaded = ArrayList<Int>()
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0, enhancer = enhancer,
            onLoaded = { loaded += it }
        )
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(600, 900)
        }
        // The next page decodes first, as when a turn lands before the page read.
        val next = adapter.onCreateViewHolder(rv, 0)
        adapter.onBindViewHolder(next, 1)
        adapter.reading(0)
        idleUntil("the next page shown") { (next.images[0].drawable as? BitmapDrawable) != null }
        // Its end handled, where AI work is asked for: still none while the page read decodes.
        idleUntil("the next page's decode handled") { 1 in loaded }
        assertTrue("the next page waits for the page read", enhancer.asked.isEmpty())
        held.countDown()
        idleUntil("two pages processed") { enhancer.asked.size >= 2 }
        assertEquals(listOf(0, 1), enhancer.asked.toList())
        adapter.release()
    }

    @Test
    fun aProcessedPageShowsAtOnceWhenBoundAgain() {
        val (adapter, rv) = reader()
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        val holder = adapter.onCreateViewHolder(rv, 0)
        adapter.onBindViewHolder(holder, 0)
        val bitmap = (holder.images[0].drawable as BitmapDrawable).bitmap
        assertEquals(processed, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        assertSame(shownBitmap(rv, 0), bitmap)
        adapter.release()
    }

    @Test
    fun anAdapterTakingProcessedPagesShowsThemPlainOnceTheChainChanges() {
        val source = Source(4, plain)
        fun adapterOf() = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0, enhancer = enhancer
        )
        val first = adapterOf()
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = first
            layOut(600, 900)
        }
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        first.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        val decoded = first.decoded(source)
        val done = first.processedOf(decoded)
        first.release(closeSources = false)
        val second = adapterOf()
        second.adopt(decoded, done)
        rv.adapter = second
        rv.layOut(600, 900)
        second.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        settled(second)
        assertEquals("taken as processed, not processed again", 1, enhancer.asked.size)
        enhancer.on = false
        second.aiChanged()
        rv.layOut(600, 900)
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        assertEquals(plain, colorAt(rv, 0))
        second.release()
    }

    @Test
    fun aChangedChainShowsPagesPlainAgain() {
        val (adapter, rv) = reader()
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        enhancer.on = false
        adapter.aiChanged()
        rv.layOut(600, 900)
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        assertEquals(plain, colorAt(rv, 0))
        assertTrue(enhancer.asked.size >= 1)
        // Back on, the plain page goes through AI again without a decode.
        enhancer.on = true
        adapter.aiChanged()
        rv.layOut(600, 900)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        adapter.release()
    }

    @Test
    fun aReplanRedoesOnlyThePagesPickedThePageUnderWayGoingOn() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val enhancer = Enhancer(processed, worker.asCoroutineDispatcher())
            val going = CountDownLatch(1)
            enhancer.onEnhance = { if (it == 1) going.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS) }
            val source = Source(4, plain)
            val adapter = PageAdapter(
                source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 1, enhancer = enhancer
            )
            val rv = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                this.adapter = adapter
                layOut(600, 900)
            }
            idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
            settled(adapter)
            adapter.reading(0)
            idleUntil("page 0 processed, page 1 under way") { colorAt(rv, 0) == processed && enhancer.asked.toList() == listOf(0, 1) }
            val judged = ArrayList<Int>()
            adapter.aiReplanned { _, index, _, _, _ -> judged += index; false }
            assertEquals("the page processed and the one under way judged", setOf(0, 1), judged.toSet())
            assertFalse("the page under way goes on", enhancer.canceled.getValue(1)())
            assertEquals(processed, colorAt(rv, 0))
            going.countDown()
            idleUntil("page 1 processed") { adapter.processedOf(adapter.decoded(source)).size == 2 }
            assertEquals(listOf(0, 1), enhancer.asked.toList())

            // Picked, page 0 shows plain and is processed anew; page 1 stays.
            adapter.aiReplanned { _, index, _, _, _ -> index == 0 }
            rv.layOut(600, 900)
            idleUntil("page 0 processed anew") { enhancer.asked.toList() == listOf(0, 1, 0) && colorAt(rv, 0) == processed }
            assertEquals(2, adapter.processedOf(adapter.decoded(source)).size)
            adapter.release()
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun aPausedReaderProcessesNothingUntilResumed() {
        val (adapter, rv) = reader()
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        adapter.pauseAi()
        adapter.reading(0)
        settled(adapter)
        assertTrue("out of sight, nothing is processed", enhancer.asked.isEmpty())
        assertEquals(plain, colorAt(rv, 0))
        adapter.resumeAi()
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        adapter.release()
    }

    @Test
    fun turningFasterThanPagesAreProcessedSkipsToThePagesReachedOnceDone() {
        val (adapter, rv) = reader(preload = 7, pages = 8)
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        settled(adapter)
        // Three turns 3 s apart with nothing to process set the pace.
        enhancer.on = false
        for (item in 0..2) {
            adapter.reading(item)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
        }
        enhancer.on = true
        enhancer.ms = 5_000
        enhancer.kept += 4
        adapter.reading(3)
        idleUntil("three pages processed") { enhancer.asked.size >= 3 }
        settled(adapter)
        // Page 4 is kept on disk: read back first; 3 would be done after the reader left it.
        assertEquals(listOf(4, 5, 6), enhancer.asked.toList())
        adapter.release()
    }

    @Test
    fun thePagesAskedForAsAPageDoneAtOnceArrivesAreProcessed() {
        val e = Enhancer(processed, InlineOnce())
        val (adapter, rv) = reader(preload = 7, pages = 8, enhancer = e)
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        settled(adapter)
        // Three turns 3 s apart with nothing to process set the pace.
        e.on = false
        for (item in 0..2) {
            adapter.reading(item)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
        }
        e.on = true
        // Page 3 ends before the main thread waits for it; its time then puts the work two items on, at 5 and 6.
        e.onEnhance = { e.ms = 5_000 }
        adapter.reading(3)
        idleUntil("three pages processed") { e.asked.size >= 3 }
        settled(adapter)
        assertEquals(listOf(3, 5, 6), e.asked.toList())
        adapter.release()
    }

    @Test
    fun aContinuousColumnProcessesThePageReadThenTheNext() {
        val adapter = PageAdapter(
            source = Source(4, plain), mode = PageAdapter.Mode.CONTINUOUS, onTap = { _, _ -> }, doubleTapZoom = false,
            preload = 2, enhancer = enhancer
        )
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.adapter = adapter
            layOut(600, 900)
        }
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        settled(adapter)
        adapter.reading(0)
        idleUntil("page 0 shown processed") { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        assertEquals(0, enhancer.asked.first())
        adapter.release()
    }

    @Test
    fun busyIsHeardOnceWhileModelsWorkOnThePagesQueued() {
        val heard = ArrayList<Boolean>()
        val (adapter, rv) = reader()
        adapter.onAiBusy = { heard += it }
        val during = ArrayList<List<Boolean>>()
        enhancer.onEnhance = { during += heard.toList() }
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        settled(adapter)
        adapter.reading(0)
        idleUntil("two pages processed, busy over") { enhancer.asked.size >= 2 && heard.lastOrNull() == false }
        assertEquals(listOf(0, 1), enhancer.asked.toList())
        assertEquals("busy through both pages, the next screen's included", listOf(listOf(true), listOf(true)), during)
        assertEquals(listOf(true, false), heard)
        adapter.release()
        assertEquals(listOf(true, false), heard)
    }

    @Test
    fun pagesReadBackFromDiskAreNotBusy() {
        val heard = ArrayList<Boolean>()
        enhancer.kept += listOf(0, 1)
        val (adapter, rv) = reader()
        adapter.onAiBusy = { heard += it }
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        settled(adapter)
        adapter.reading(0)
        idleUntil("two pages processed") { enhancer.asked.size >= 2 }
        settled(adapter)
        assertEquals(processed, colorAt(rv, 0))
        assertTrue(heard.isEmpty())
        adapter.release()
    }

    @Test
    fun pagesNeedingNoModelAreNotBusy() {
        val heard = ArrayList<Boolean>()
        enhancer.unneeded = true
        val (adapter, rv) = reader()
        adapter.onAiBusy = { heard += it }
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        settled(adapter)
        adapter.reading(0)
        idleUntil("two pages processed") { enhancer.asked.size >= 2 }
        settled(adapter)
        assertTrue(heard.isEmpty())
        adapter.release()
    }

    @Test
    fun withAiOffNothingIsBusy() {
        val heard = ArrayList<Boolean>()
        enhancer.on = false
        val (adapter, rv) = reader()
        adapter.onAiBusy = { heard += it }
        idleUntil("page 0 shown plain") { colorAt(rv, 0) == plain }
        adapter.reading(0)
        settled(adapter)
        adapter.release()
        assertTrue(heard.isEmpty())
    }
}
