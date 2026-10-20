package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
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

/** Pages are requested at their exact on-screen size, and a size change requests them again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterTargetTest {

    /** Records every request; returns a bitmap of the size a fitted 2:3 page would have. */
    private class RecordingSource(override val pageCount: Int) : PageSource {
        val requests: MutableList<Pair<Int, Target>> = Collections.synchronizedList(ArrayList())
        override fun loadPage(index: Int, target: Target): Bitmap {
            requests += index to target
            val plan = target.plan(2000.0, 3000.0)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun idle(done: () -> Boolean = { false }) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun recycler(adapter: PageAdapter, w: Int, h: Int): RecyclerView =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(w, h)
        }

    private fun RecyclerView.layOut(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun adapter(source: PageSource, twoPage: Boolean = false) = PageAdapter(
        source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, twoPage = twoPage, preload = 0
    )

    @Test
    fun singlePageIsRequestedAtTheScreenSize() {
        val source = RecordingSource(3)
        val adapter = adapter(source)
        recycler(adapter, 1848, 2960)
        idle { source.requests.isNotEmpty() }
        assertEquals(0 to Target(1848, 2960), source.requests.first())
        adapter.release()
    }

    @Test
    fun spreadHalvesAndTheLonePageGetTheirOwnSizes() {
        val source = RecordingSource(3)
        val adapter = adapter(source, twoPage = true)
        val rv = recycler(adapter, 2960, 1848)
        idle { source.requests.size >= 2 }
        assertTrue(0 to Target(1480, 1848) in source.requests)
        assertTrue(1 to Target(1480, 1848) in source.requests)

        // The odd last page has the whole row.
        rv.scrollToPosition(1)
        rv.layOut(2960, 1848)
        idle { source.requests.any { it.first == 2 } }
        assertTrue(2 to Target(2960, 1848) in source.requests)
        adapter.release()
    }

    @Test
    fun resizeRequestsTheNewSizeAndShowsItOneToOne() {
        val source = RecordingSource(1)
        val adapter = adapter(source)
        val rv = recycler(adapter, 1848, 2960)
        idle { source.requests.isNotEmpty() }

        // Rotating the screen re-lays the pager out at the other size.
        rv.layOut(2960, 1848)
        // The size change rebinds the page in the layout pass that follows.
        idle()
        rv.layOut(2960, 1848)
        idle { source.requests.size >= 2 }
        assertEquals(0 to Target(2960, 1848), source.requests.last())

        rv.layOut(2960, 1848)
        idle()
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        assertTrue(image.drawsOneToOne)
        // Laying out again at the same size asks for nothing new.
        assertEquals(2, source.requests.size)
        adapter.release()
    }

    @Test
    fun cachedSizeIsNotDecodedAgain() {
        val source = RecordingSource(1)
        val adapter = adapter(source)
        val rv = recycler(adapter, 1848, 2960)
        idle { source.requests.isNotEmpty() }
        adapter.notifyItemChanged(0)
        rv.layOut(1848, 2960)
        idle()
        assertEquals(1, source.requests.size)
        adapter.release()
    }
}
