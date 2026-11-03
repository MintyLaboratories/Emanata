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
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.Collections
import java.util.concurrent.Executors

/** Pages shown plain at once, then swapped in place for their AI version, the page read first, then the next. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAdapterAiTest {
    private val plain = Color.rgb(200, 200, 200)
    private val processed = Color.rgb(10, 120, 30)

    private class Source(override val pageCount: Int, private val color: Int) : PageSource {
        override fun loadPage(index: Int, target: Target): Bitmap {
            val plan = target.plan(1000.0, 1500.0)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }

        override fun close() {}
    }

    /** Makes every page [color] at the size asked, listing the pages asked for; [on] false makes it process none. */
    private class Enhancer(private val color: Int) : PageEnhancer {
        private val executor = Executors.newSingleThreadExecutor()
        override val worker = executor.asCoroutineDispatcher()
        val asked: MutableList<Int> = Collections.synchronizedList(ArrayList())

        @Volatile
        var on = true

        override fun enhances(source: PageSource): Boolean = on

        override fun enhance(source: PageSource, index: Int, target: Target, width: Int, height: Int, canceled: () -> Boolean): AiOutcome {
            asked += index
            if (!on) return AiOutcome.Skipped(Fallback.UNNEEDED)
            return AiOutcome.Done(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        }

        fun close() = executor.shutdownNow()
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val enhancer = Enhancer(processed)

    @After
    fun tearDown() {
        enhancer.close()
    }

    private fun idle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun RecyclerView.layOut(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun shownBitmap(rv: RecyclerView, position: Int): Bitmap? =
        ((rv.findViewHolderForAdapterPosition(position) as? PageAdapter.PageHolder)?.images?.get(0)?.drawable as? BitmapDrawable)?.bitmap

    private fun colorAt(rv: RecyclerView, position: Int): Int? = shownBitmap(rv, position)?.let { it.getPixel(it.width / 2, it.height / 2) }

    private fun reader(preload: Int = 1): Pair<PageAdapter, RecyclerView> {
        val adapter = PageAdapter(
            source = Source(4, plain), mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
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
        idle { false.also { Thread.sleep(50) } }
        adapter.reading(0)
        idle { enhancer.asked.size >= 2 }
        idle { false.also { Thread.sleep(50) } }
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
        adapter.release()
    }
}
