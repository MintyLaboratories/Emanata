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
        override val byteCount = 0L
        override fun render(region: Region, width: Int, height: Int, linear: Boolean): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        override fun release() {
            released = true
        }
    }

    private class DetailSource(override val pageCount: Int) : PageSource {
        val details: MutableList<Detail> = Collections.synchronizedList(ArrayList())
        val budgets: MutableList<Pair<Target, Long>> = Collections.synchronizedList(ArrayList())
        val loads: MutableList<Target> = Collections.synchronizedList(ArrayList())

        override fun loadPage(index: Int, target: Target): Bitmap {
            loads += target
            val plan = target.plan(2000.0, 3000.0)
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
        assertTrue(source.budgets.all { it.first == Target(1480, 1848) })
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
        assertEquals(listOf(Target(1848, 2960)), source.loads.toList())
        assertEquals(1, source.details.size)
        assertTrue(image.drawsOneToOne)
        adapter.release()
    }
}
