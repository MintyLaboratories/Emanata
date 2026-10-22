package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pages are requested at their exact on-screen size, and a size change requests them again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterTargetTest {

    /** Records every request; returns a bitmap of the size a fitted 2:3 page would have. */
    private class RecordingSource(override val pageCount: Int) : PageSource {
        val requests: MutableList<Pair<Int, Target>> = CopyOnWriteArrayList()
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

    @Test
    fun aNewFitDropsTheOldSizeOfPagesOffScreenOnly() {
        val source = RecordingSource(6)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 3)
        val rv = recycler(adapter, 1848, 2960)
        val screen = Target(1848, 2960)
        val height = Target(0, 2960)
        // Page 0 shows, pages 1 to 3 are preloaded; each step waits for its decodes to be put away.
        idle { 3 to screen in source.requests && !adapter.decoding }

        adapter.fitMode = ZoomImageView.FitMode.HEIGHT
        rv.layOut(1848, 2960)
        idle { 3 to height in source.requests && !adapter.decoding }
        adapter.fitMode = ZoomImageView.FitMode.SCREEN
        rv.layOut(1848, 2960)
        idle { source.requests.count { it == 3 to screen } == 2 && !adapter.decoding }
        // The page shown kept its bitmap at fit screen; one far off screen is preloaded again.
        assertEquals(1, source.requests.count { it == 0 to screen })
        assertEquals(2, source.requests.count { it == 3 to screen })
        adapter.release()
    }

    @Test
    fun anAdapterThatReplacesAnotherShowsItsPagesWithoutDecoding() {
        val source = RecordingSource(3)
        val first = adapter(source)
        recycler(first, 1848, 2960)
        idle { source.requests.isNotEmpty() && !first.decoding }
        val decoded = first.decoded(source)
        first.release(closeSources = false)
        val second = adapter(source)
        second.adopt(decoded)
        recycler(second, 1848, 2960)
        idle()
        assertEquals(1, source.requests.size)
        // Pages of another source are not handed over.
        assertTrue(second.decoded(RecordingSource(3)).isEmpty())
        second.release()
    }

    @Test
    fun theHalvesOfOnePictureShareOneViewArea() {
        val source = RecordingSource(4)
        // One page a screen, pages 1 and 2 merged as one picture.
        val layout = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3)), spread = true, rtl = false, joined = setOf(1))
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0, pageLayout = layout
        )
        val rv = recycler(adapter, 2960, 1848)
        rv.scrollToPosition(1)
        rv.layOut(2960, 1848)
        idle { source.requests.count { it.first in 1..2 } >= 2 && !adapter.decoding }
        val holder = rv.findViewHolderForAdapterPosition(1) as PageAdapter.PageHolder
        assertTrue(holder.joined)
        // Both views cover the whole item; each page is still decoded for its half.
        holder.images.forEach { assertEquals(2960, it.width) }
        assertTrue(1 to Target(1480, 1848) in source.requests)
        assertTrue(2 to Target(1480, 1848) in source.requests)
        rv.layOut(2960, 1848)
        idle()
        val left = FloatArray(9).also { holder.images[0].imageMatrix.getValues(it) }
        val right = FloatArray(9).also { holder.images[1].imageMatrix.getValues(it) }
        val page = Target(1480, 1848).plan(2000.0, 3000.0)
        // Side by side, centered as one, meeting at the gutter.
        assertEquals((2960 - 2 * page.width) / 2f, left[Matrix.MTRANS_X], 1f)
        assertEquals(left[Matrix.MTRANS_X] + page.width, right[Matrix.MTRANS_X], 1f)
        adapter.release()
    }

    /** Like [RecordingSource], but every decode after the first waits for [gate]. */
    private class GatedSource(override val pageCount: Int) : PageSource {
        val requests: MutableList<Pair<Int, Target>> = CopyOnWriteArrayList()
        val gate = CountDownLatch(1)
        override fun loadPage(index: Int, target: Target): Bitmap {
            requests += index to target
            if (requests.size > 1) gate.await(5, TimeUnit.SECONDS)
            val plan = target.plan(2000.0, 3000.0)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    private fun timingLogs() = ShadowLog.getLogsForTag("ResizeTiming").map { it.msg }

    private fun shownBitmap(image: ZoomImageView) = (image.drawable as BitmapDrawable).bitmap

    @Test
    fun newFitShowsTheBitmapAtOnceThenSwapsInItsExactSize() {
        val source = GatedSource(1)
        val adapter = adapter(source)
        val rv = recycler(adapter, 1848, 2960)
        idle { source.requests.isNotEmpty() }
        val holder = rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder
        val image = holder.images[0]
        idle { image.drawable != null }
        val screen = shownBitmap(image)
        assertEquals(1848, screen.width)

        // Fit height: the same bitmap, on the same holder, enlarged at once while the exact one decodes.
        ShadowLog.clear()
        adapter.fitMode = ZoomImageView.FitMode.HEIGHT
        rv.layOut(1848, 2960)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        idle { source.requests.size >= 2 }
        // The first frame is timed, the page standing in until the exact bitmap arrives.
        assertTrue(timingLogs().single().matches(Regex("""Resize shown in \d+ ms, 1 page\(s\) standing in""")))
        assertSame(holder, rv.findViewHolderForAdapterPosition(0))
        assertSame(screen, shownBitmap(image))
        assertFalse(image.drawsOneToOne)
        val m = FloatArray(9).also { image.imageMatrix.getValues(it) }
        assertEquals(2960f / screen.height, m[Matrix.MSCALE_Y], 1e-4f)

        source.gate.countDown()
        idle { shownBitmap(image) !== screen }
        assertEquals(0 to Target(0, 2960), source.requests.last())
        assertEquals(2960, shownBitmap(image).height)
        assertTrue(image.drawsOneToOne)
        assertTrue(timingLogs().last().matches(Regex("""Resize exact in \d+ ms""")))

        // Back to fit screen: still cached, so nothing decodes.
        adapter.fitMode = ZoomImageView.FitMode.SCREEN
        rv.layOut(1848, 2960)
        idle()
        assertSame(screen, shownBitmap(image))
        assertTrue(image.drawsOneToOne)
        assertEquals(2, source.requests.size)
        adapter.release()
    }

    /** Shows page 0 at fit screen on a portrait screen, the next decodes held by [GatedSource.gate]. */
    private fun shownAtFitScreen(source: GatedSource, adapter: PageAdapter): Pair<RecyclerView, ZoomImageView> {
        val rv = recycler(adapter, 1848, 2960)
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        idle { image.drawable != null }
        return rv to image
    }

    @Test
    fun aNewPageGapRescalesTheShownBitmapThenSwapsInItsExactSize() {
        val source = GatedSource(1)
        val adapter = adapter(source)
        val (rv, image) = shownAtFitScreen(source, adapter)
        val holder = rv.findViewHolderForAdapterPosition(0)
        val screen = shownBitmap(image)

        adapter.spacingPx = 20
        rv.layOut(1848, 2960)
        idle { source.requests.size >= 2 }
        assertSame(holder, rv.findViewHolderForAdapterPosition(0))
        assertSame(screen, shownBitmap(image))
        assertEquals(0 to Target(1808, 2920), source.requests.last())

        source.gate.countDown()
        idle { shownBitmap(image) !== screen }
        assertEquals(1808, shownBitmap(image).width)
        assertTrue(image.drawsOneToOne)
        adapter.release()
    }

    @Test
    fun rotatingRescalesTheShownBitmapThenSwapsInItsExactSize() {
        val source = GatedSource(1)
        val adapter = adapter(source)
        val (rv, image) = shownAtFitScreen(source, adapter)
        val holder = rv.findViewHolderForAdapterPosition(0)
        val portrait = shownBitmap(image)

        rv.layOut(2960, 1848)
        idle()
        rv.layOut(2960, 1848)
        idle { source.requests.size >= 2 }
        assertSame(holder, rv.findViewHolderForAdapterPosition(0))
        assertSame(portrait, shownBitmap(image))
        assertEquals(0 to Target(2960, 1848), source.requests.last())

        source.gate.countDown()
        idle { shownBitmap(image) !== portrait }
        assertEquals(1848, shownBitmap(image).height)
        assertTrue(image.drawsOneToOne)
        adapter.release()
    }

    @Test
    fun aNewFitLeavesAColumnAsItIs() {
        val source = RecordingSource(2)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.CONTINUOUS, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            layOut(1848, 2960)
        }
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        idle { image.drawable != null }
        rv.layOut(1848, 2960)
        idle()
        val shown = shownBitmap(image)
        val requests = source.requests.toList()

        adapter.fitMode = ZoomImageView.FitMode.HEIGHT
        rv.layOut(1848, 2960)
        idle()
        assertSame(shown, shownBitmap(image))
        assertEquals(requests, source.requests.toList())
        assertTrue(requests.all { it.second == Target(1848, 0) })
        adapter.release()
    }

    @Test
    fun aColumnLeavesThePageGapBelowEachPage() {
        val adapter = PageAdapter(source = RecordingSource(2), mode = PageAdapter.Mode.CONTINUOUS, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        adapter.spacingPx = 24
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            layOut(2000, 3000)
        }
        val holder = rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder
        idle { holder.images[0].drawable != null }
        rv.layOut(2000, 3000)
        assertEquals(3000 + 24, holder.container.layoutParams.height)
        assertEquals(24, holder.container.paddingBottom)
        assertEquals(0, holder.container.paddingTop)
        adapter.release()
    }
}
