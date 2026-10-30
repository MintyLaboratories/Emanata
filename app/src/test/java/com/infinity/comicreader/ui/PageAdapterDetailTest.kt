package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.PageDetail
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Region
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/** Both halves of a spread re-render settled zooms from their own page's full detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageAdapterDetailTest {

    private class Detail(val page: Int) : PageDetail {
        @Volatile var released = false

        /** Set to make renders throw, as when memory runs out. */
        @Volatile var failing = false
        override val byteCount = 0L
        override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap {
            if (failing) throw OutOfMemoryError("synthetic")
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        override fun release() {
            released = true
        }
    }

    private class DetailSource(override val pageCount: Int, val srcW: Double = 2000.0, val srcH: Double = 3000.0) : PageSource {
        val details: MutableList<Detail> = Collections.synchronizedList(ArrayList())
        val budgets: MutableList<Pair<Target, Long>> = Collections.synchronizedList(ArrayList())
        val loads: MutableList<Target> = Collections.synchronizedList(ArrayList())

        override fun loadPage(index: Int, target: Target): Bitmap {
            loads += target
            val plan = target.plan(srcW, srcH)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        }

        override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
            budgets += target to maxBytes
            return Detail(index).also { details += it }
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

    private fun RecyclerView.layOut(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    @Test
    fun spreadHalvesRenderTheirOwnPagesAndKeepBoth() {
        val source = DetailSource(4)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = true, twoPage = true, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(2960, 1848)
        }
        val images = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images
        idle { images.all { it.detailSource != null } }

        val shown = arrayOfNulls<Bitmap>(2)
        images.forEachIndexed { i, image ->
            assertNotNull(image.detailSource)
            image.detailSource!!.render(Region(0.25, 0.25, 0.5, 0.5), 1480, 1848) { shown[i] = it }
        }
        idle { shown.all { it != null } }
        assertEquals(1480, shown[0]!!.width)
        assertEquals(1480, shown[1]!!.width)
        assertEquals(setOf(0, 1), source.details.map { it.page }.toSet())
        // Each half has half the budget, and asks for the target its half was decoded for.
        assertTrue(source.budgets.all { it.first == Target(1480, 1848, banded = true) })
        assertEquals(1, source.budgets.map { it.second }.distinct().size)
        assertTrue(source.details.none { it.released })

        // Trimming frees both.
        adapter.trimMemory()
        assertTrue(source.details.all { it.released })
        adapter.release()
    }

    @Test
    fun loneLastPageOfASpreadHasTheWholeBudget() {
        val source = DetailSource(3)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = true, twoPage = true, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(2960, 1848)
        }
        val pair = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images
        idle { pair.all { it.detailSource != null } }
        var shown: Bitmap? = null
        pair[0].detailSource!!.render(Region(0.0, 0.0, 1.0, 1.0), 10, 10) { shown = it }
        idle { shown != null }

        rv.scrollToPosition(1)
        rv.layOut(2960, 1848)
        val lone = (rv.findViewHolderForAdapterPosition(1) as PageAdapter.PageHolder).images
        assertEquals(View.GONE, lone[1].visibility)
        idle { lone[0].detailSource != null }
        shown = null
        lone[0].detailSource!!.render(Region(0.0, 0.0, 1.0, 1.0), 10, 10) { shown = it }
        idle { shown != null }

        val (half, whole) = source.budgets.map { it.second }
        assertEquals(2 * half, whole)
        adapter.release()
    }

    @Test
    fun aNewFitIsRenderedFromTheDetailAZoomHolds() {
        val source = DetailSource(1)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = true, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(1848, 2960)
        }
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        idle { image.detailSource != null }
        var tile: Bitmap? = null
        image.detailSource!!.render(Region(0.25, 0.25, 0.5, 0.5), 100, 100) { tile = it }
        idle { tile != null }

        adapter.fitMode = ZoomImageView.FitMode.HEIGHT
        rv.layOut(1848, 2960)
        idle { (image.drawable as BitmapDrawable).bitmap.height == 2960 }
        // The 1848 x 2772 page at the height of the screen, made without decoding it again.
        assertEquals(1973, (image.drawable as BitmapDrawable).bitmap.width)
        assertEquals(listOf(Target(1848, 2960, banded = true)), source.loads.toList())
        assertEquals(1, source.details.size)
        assertTrue(image.drawsOneToOne)

        // The next zoom, at the new fit, renders from the same detail.
        tile = null
        image.detailSource!!.render(Region(0.25, 0.25, 0.5, 0.5), 100, 100) { tile = it }
        idle { tile != null }
        assertNotNull(tile)
        assertEquals(1, source.details.size)
        assertTrue(source.details.none { it.released })
        adapter.release()
    }

    @Test
    fun aNewFitIsDecodedWhenTheRenderFromTheDetailFails() {
        val source = DetailSource(1)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = true, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(1848, 2960)
        }
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        idle { image.detailSource != null }
        var tile: Bitmap? = null
        image.detailSource!!.render(Region(0.25, 0.25, 0.5, 0.5), 100, 100) { tile = it }
        idle { tile != null }

        source.details.single().failing = true
        adapter.fitMode = ZoomImageView.FitMode.HEIGHT
        rv.layOut(1848, 2960)
        idle { (image.drawable as BitmapDrawable).bitmap.height == 2960 }
        assertEquals(listOf(Target(1848, 2960, banded = true), Target(0, 2960, banded = true)), source.loads.toList())
        assertTrue(image.drawsOneToOne)
        adapter.release()
    }

    @Test
    fun aNewFitRendersBothHalvesOfASpreadFromTheirDetail() {
        val source = DetailSource(2)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = true, twoPage = true, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layOut(2960, 1848)
        }
        val images = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images
        idle { images.all { it.detailSource != null } }
        val tiles = arrayOfNulls<Bitmap>(2)
        images.forEachIndexed { i, image -> image.detailSource!!.render(Region(0.25, 0.25, 0.5, 0.5), 100, 100) { tiles[i] = it } }
        idle { tiles.all { it != null } }

        // Fit width: each 1480 px half shows its 2:3 page 2220 px tall.
        adapter.fitMode = ZoomImageView.FitMode.WIDTH
        rv.layOut(2960, 1848)
        idle { images.all { (it.drawable as BitmapDrawable).bitmap.height == 2220 } }
        assertTrue(images.all { (it.drawable as BitmapDrawable).bitmap.width == 1480 })
        assertTrue(images.all { it.drawsOneToOne })
        assertEquals(listOf(Target(1480, 1848, banded = true), Target(1480, 1848, banded = true)), source.loads.toList())
        assertEquals(2, source.details.size)
        assertTrue(source.details.none { it.released })
        adapter.release()
    }

    @Test
    fun aPageDrawnInBandsTakesHalfTheBudgetAtMost() {
        // 1:29: a column shows it in bands, and may keep the next page's detail beside it.
        val source = DetailSource(2, 1000.0, 25000.0)
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.CONTINUOUS, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            layOut(1848, 2960)
        }
        val image = (rv.findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images[0]
        idle { image.detailSource != null }
        assertTrue(image.detailSource!!.bands)
        image.detailSource!!.renderBand(Region(0.0, 0.0, 1.0, 0.02), 1848, 1024, {}) { }
        idle { source.budgets.isNotEmpty() }
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val budget = DetailStore.budget(android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem)
        assertEquals(budget / 2, source.budgets.first().second)
        adapter.release()
    }
}
