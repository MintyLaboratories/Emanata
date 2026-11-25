package minty.emanata.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.reader.PageSource
import minty.emanata.reader.Target
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/** A page is decoded at the size it shows at once the screen turned for a wide page, or back: turning re-decodes none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterTurnTest {

    private class Recording(override val pageCount: Int) : PageSource {
        val loads: MutableList<Pair<Int, Target>> = Collections.synchronizedList(ArrayList())

        override fun loadPage(index: Int, target: Target): Bitmap {
            loads += index to target
            return Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun layOut(rv: RecyclerView, w: Int, h: Int) {
        rv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        rv.layout(0, 0, w, h)
    }

    /**
     * Idles and lays [rv] out at [w] x [h], as a window would after items change, until nothing
     * more is due: no decode under way, no main-thread work, no layout asked for.
     */
    private fun settle(rv: RecyclerView, w: Int, h: Int) =
        idleUntil("decodes and layouts to settle", step = { layOut(rv, w, h) }) {
            !(rv.adapter as PageAdapter).decoding && shadowOf(Looper.getMainLooper()).isIdle && !rv.isLayoutRequested
        }

    private fun sizes(loads: List<Pair<Int, Target>>, page: Int) = loads.filter { it.first == page }.map { it.second.width to it.second.height }

    @Test
    fun aWidePageAheadDecodesTurnedAndTurningForItAndBackDecodesNothing() {
        val source = Recording(6)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 2)
        adapter.turnsFor = { it == 2 }
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            this.adapter = adapter
        }
        layOut(rv, 1200, 1800)
        rv.scrollToPosition(1)
        settle(rv, 1200, 1800)
        // Page 2, which the screen turns for, is read ahead turned; the others upright.
        assertEquals(listOf(1800 to 1200), sizes(source.loads, 2))
        assertEquals(listOf(1200 to 1800), sizes(source.loads, 1))
        assertEquals(listOf(1200 to 1800), sizes(source.loads, 3))

        val before = source.loads.size
        rv.scrollToPosition(2)
        adapter.turned = true
        settle(rv, 1800, 1200)
        adapter.turned = false
        rv.scrollToPosition(3)
        settle(rv, 1200, 1800)
        val after = source.loads.drop(before)
        assertTrue(after.toString(), after.none { it.first in 1..3 })
        adapter.release()
    }

    @Test
    fun aPageFoundWideAfterItsItemBoundTurnsAndDecodesTurned() {
        val source = Recording(6)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val wide = HashSet<Int>()
        adapter.turnsImage = { it in wide }
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            this.adapter = adapter
        }
        layOut(rv, 1200, 1800)
        rv.scrollToPosition(2)
        settle(rv, 1200, 1800)
        val holder = rv.findViewHolderForAdapterPosition(2) as PageAdapter.PageHolder
        assertEquals(0, holder.turner!!.turn)
        assertEquals(listOf(1200 to 1800), sizes(source.loads, 2))
        wide += 2
        adapter.refreshTurns()
        settle(rv, 1200, 1800)
        val now = rv.findViewHolderForAdapterPosition(2) as PageAdapter.PageHolder
        assertEquals(1, now.turner!!.turn)
        assertEquals(1, now.images[0].turn)
        assertEquals(listOf(1200 to 1800, 1800 to 1200), sizes(source.loads, 2))
        // Nothing else changed: nothing else binds or decodes again.
        val before = source.loads.size
        adapter.refreshTurns()
        settle(rv, 1200, 1800)
        assertEquals(before, source.loads.size)
        adapter.release()
    }

    @Test
    fun theOtherHandOrTheSameAnswerAgainDecodesNothing() {
        val source = Recording(6)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        adapter.turnsImage = { it == 2 }
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            this.adapter = adapter
        }
        layOut(rv, 1200, 1800)
        rv.scrollToPosition(2)
        settle(rv, 1200, 1800)
        assertEquals(listOf(1800 to 1200), sizes(source.loads, 2))
        val before = source.loads.size
        val changed = ArrayList<Pair<Int, Int>>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changed += positionStart to itemCount
            }
        })
        adapter.turnsImage = { it == 2 }
        adapter.imageTurn = -1
        adapter.refreshTurns()
        settle(rv, 1200, 1800)
        // Turned the other way at the same size, binding that item alone again.
        assertEquals(-1, (rv.findViewHolderForAdapterPosition(2) as PageAdapter.PageHolder).turner!!.turn)
        assertEquals(listOf(2 to 1), changed)
        assertEquals(before, source.loads.size)
        // Turning stops: the page is decoded upright.
        adapter.turnsImage = null
        settle(rv, 1200, 1800)
        assertEquals(0, (rv.findViewHolderForAdapterPosition(2) as PageAdapter.PageHolder).turner!!.turn)
        assertEquals(listOf(1800 to 1200, 1200 to 1800), sizes(source.loads, 2))
        adapter.release()
    }

    @Test
    fun aScreenThatStaysTurnedShowsItsOwnSizeSoon() {
        val source = Recording(6)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 1)
        adapter.turnsFor = { it == 2 }
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            this.adapter = adapter
        }
        layOut(rv, 1200, 1800)
        rv.scrollToPosition(2)
        adapter.turned = true
        settle(rv, 1800, 1200)
        // Turned back, but the screen stays in landscape: once the wait ends, pages show at its size.
        adapter.turned = false
        rv.scrollToPosition(3)
        settle(rv, 1800, 1200)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_000))
        settle(rv, 1800, 1200)
        assertEquals(1800 to 1200, sizes(source.loads, 3).last())
        adapter.release()
    }
}
