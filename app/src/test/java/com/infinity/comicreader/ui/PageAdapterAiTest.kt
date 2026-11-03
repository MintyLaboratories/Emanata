package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
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
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.TimeUnit

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
            held[index]?.await(5, TimeUnit.SECONDS)
            val plan = target.plan(1000.0, 1500.0)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }

        override fun close() {}
    }

    /** Makes every page [color] at the size asked, listing the pages asked for; [on] false makes it process none. */
    private class Enhancer(private val color: Int) : PageEnhancer {
        // On the main looper: the work runs as the tests idle it, with no thread of its own to wait for.
        override val worker = Dispatchers.Main
        val asked: MutableList<Int> = Collections.synchronizedList(ArrayList())

        @Volatile
        var on = true

        /** What [pageMs] says. */
        @Volatile
        var ms = 0L

        /** Pages [stored] says are kept on disk. */
        val kept: MutableSet<Int> = Collections.synchronizedSet(HashSet())

        override fun enhances(source: PageSource): Boolean = on

        override fun pageMs(source: PageSource): Long = ms

        override fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int): Boolean = index in kept

        override fun enhance(source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean, canceled: () -> Boolean): AiOutcome {
            asked += index
            if (!on) return AiOutcome.Skipped(Fallback.UNNEEDED)
            return AiOutcome.Done(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        }

        fun close() = Unit
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val enhancer = Enhancer(processed)

    @After
    fun tearDown() {
        enhancer.close()
    }

    private fun idle(done: () -> Boolean) {
        // Only decodes still run on threads of their own: a generous bound, reached only on failure.
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Lets work run for a while, whatever it does. */
    private fun aWhile() = repeat(20) {
        Thread.sleep(10)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun RecyclerView.layOut(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun shownBitmap(rv: RecyclerView, position: Int): Bitmap? =
        ((rv.findViewHolderForAdapterPosition(position) as? PageAdapter.PageHolder)?.images?.get(0)?.drawable as? BitmapDrawable)?.bitmap

    private fun colorAt(rv: RecyclerView, position: Int): Int? = shownBitmap(rv, position)?.let { it.getPixel(it.width / 2, it.height / 2) }

    private fun reader(preload: Int = 1, pages: Int = 4): Pair<PageAdapter, RecyclerView> {
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
        idle { colorAt(rv, 0) == plain }
        val before = shownBitmap(rv, 0)!!
        adapter.reading(0)
        idle { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        val after = shownBitmap(rv, 0)!!
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        adapter.release()
    }

    @Test
    fun thePageReadGoesFirstThenTheNextAndNoFurther() {
        val (adapter, rv) = reader(preload = 2)
        idle { colorAt(rv, 0) == plain }
        // Let reading ahead decode the pages after it.
        aWhile()
        adapter.reading(0)
        idle { enhancer.asked.size >= 2 }
        aWhile()
        assertEquals(listOf(0, 1), enhancer.asked.toList())
        adapter.release()
    }

    @Test
    fun nothingIsProcessedUntilThePageReadHasDecoded() {
        val source = Source(4, plain)
        val held = CountDownLatch(1).also { source.held[0] = it }
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0, enhancer = enhancer
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
        idle { (next.images[0].drawable as? BitmapDrawable) != null }
        aWhile()
        assertTrue("the next page waits for the page read", enhancer.asked.isEmpty())
        held.countDown()
        idle { enhancer.asked.size >= 2 }
        assertEquals(listOf(0, 1), enhancer.asked.toList())
        adapter.release()
    }

    @Test
    fun aProcessedPageShowsAtOnceWhenBoundAgain() {
        val (adapter, rv) = reader()
        idle { colorAt(rv, 0) == plain }
        adapter.reading(0)
        idle { colorAt(rv, 0) == processed }
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
        idle { colorAt(rv, 0) == plain }
        first.reading(0)
        idle { colorAt(rv, 0) == processed }
        val decoded = first.decoded(source)
        val done = first.processedOf(decoded)
        first.release(closeSources = false)
        val second = adapterOf()
        second.adopt(decoded, done)
        rv.adapter = second
        rv.layOut(600, 900)
        second.reading(0)
        idle { colorAt(rv, 0) == processed }
        aWhile()
        assertEquals("taken as processed, not processed again", 1, enhancer.asked.size)
        enhancer.on = false
        second.aiChanged()
        rv.layOut(600, 900)
        idle { colorAt(rv, 0) == plain }
        assertEquals(plain, colorAt(rv, 0))
        second.release()
    }

    @Test
    fun aChangedChainShowsPagesPlainAgain() {
        val (adapter, rv) = reader()
        idle { colorAt(rv, 0) == plain }
        adapter.reading(0)
        idle { colorAt(rv, 0) == processed }
        enhancer.on = false
        adapter.aiChanged()
        rv.layOut(600, 900)
        idle { colorAt(rv, 0) == plain }
        assertEquals(plain, colorAt(rv, 0))
        assertTrue(enhancer.asked.size >= 1)
        // Back on, the plain page goes through AI again without a decode.
        enhancer.on = true
        adapter.aiChanged()
        rv.layOut(600, 900)
        idle { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        adapter.release()
    }

    @Test
    fun aPausedReaderProcessesNothingUntilResumed() {
        val (adapter, rv) = reader()
        idle { colorAt(rv, 0) == plain }
        adapter.pauseAi()
        adapter.reading(0)
        aWhile()
        assertTrue("out of sight, nothing is processed", enhancer.asked.isEmpty())
        assertEquals(plain, colorAt(rv, 0))
        adapter.resumeAi()
        idle { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        adapter.release()
    }

    @Test
    fun turningFasterThanPagesAreProcessedSkipsToThePagesReachedOnceDone() {
        val (adapter, rv) = reader(preload = 7, pages = 8)
        idle { colorAt(rv, 0) == plain }
        aWhile()
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
        idle { enhancer.asked.size >= 3 }
        aWhile()
        // Page 4 is kept on disk: read back first; 3 would be done after the reader left it.
        assertEquals(listOf(4, 5, 6), enhancer.asked.toList())
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
        idle { colorAt(rv, 0) == plain }
        aWhile()
        adapter.reading(0)
        idle { colorAt(rv, 0) == processed }
        assertEquals(processed, colorAt(rv, 0))
        assertEquals(0, enhancer.asked.first())
        adapter.release()
    }
}
