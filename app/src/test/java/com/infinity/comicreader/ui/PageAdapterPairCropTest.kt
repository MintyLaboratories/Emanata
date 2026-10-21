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
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Auto-crop of two pages side by side: one top and bottom for both, one height, meeting at the gutter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAdapterPairCropTest {

    /** Pages encoded as PNG, decoded for real so auto-crop runs; records every request. */
    private class PngSource(private val pages: List<ByteArray>) : PageSource {
        val requests: MutableList<Pair<Int, Target>> = CopyOnWriteArrayList()
        override val pageCount: Int get() = pages.size
        override fun loadPage(index: Int, target: Target): Bitmap {
            requests += index to target
            return Decoders.decode(pages[index], target)
        }

        override fun close() {}
    }

    /** A 400 x 600 white page with a black block over columns [x0, x1) and rows [y0, y1). */
    private fun page(x0: Int, y0: Int, x1: Int, y1: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in y0 until y1) for (x in x0 until x1) bitmap.setPixel(x, y, Color.BLACK)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /** Idles until [done], laying [rv] out again meanwhile, as a window would after items change. */
    private fun idle(rv: RecyclerView, done: () -> Boolean = { false }) {
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
            rv.measure(View.MeasureSpec.makeMeasureSpec(rv.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(rv.height, View.MeasureSpec.EXACTLY))
            rv.layout(0, 0, rv.width, rv.height)
        } while (!done() && System.currentTimeMillis() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun recycler(adapter: PageAdapter, w: Int, h: Int): RecyclerView =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }

    private fun RecyclerView.images(): List<ZoomImageView> = (findViewHolderForAdapterPosition(0) as PageAdapter.PageHolder).images

    private fun ZoomImageView.bitmap(): Bitmap? = (drawable as? BitmapDrawable)?.bitmap

    @Test
    fun twoPagesShareTheirTopAndBottomCropAndShowAtOneHeight() {
        // The left page's art runs taller than the right one's, and is narrower.
        val source = PngSource(listOf(page(40, 60, 360, 540), page(20, 120, 380, 480)))
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, autoCrop = true, preload = 0
        )
        val rv = recycler(adapter, 1200, 800)
        idle(rv) { source.requests.count { it.second.box != null } >= 2 && rv.images().all { it.bitmap() != null && it.bitmap()!!.width > 16 } }
        val shown = source.requests.filter { it.second.box != null }.associate { it.first to it.second }
        val left = shown.getValue(0)
        val right = shown.getValue(1)
        // One top and bottom for both, the union of the two pages' content.
        assertEquals(left.box!!.top, right.box!!.top, 0.0)
        assertEquals(left.box!!.bottom, right.box!!.bottom, 0.0)
        assertEquals(56.0 / 600, left.box!!.top, 1e-9)
        assertEquals(544.0 / 600, left.box!!.bottom, 1e-9)
        // Each keeps its own sides.
        assertNotEquals(left.box!!.left, right.box!!.left)
        // Both at the one height the wider page allows in its half.
        assertEquals(left.height, right.height)
        val (l, r) = rv.images()
        assertEquals(left.height, l.bitmap()!!.height)
        assertEquals(l.bitmap()!!.height, r.bitmap()!!.height)
        // Fitted 1:1 into the pair's box, and meeting at the gutter.
        assertArrayEquals(intArrayOf(left.width, left.height), l.fitBox)
        assertEquals(1f, l.alignX)
        assertEquals(0f, r.alignX)
        adapter.release()
    }

    @Test
    fun withoutAutoCropPagesFitTheirHalvesAndMeetAtTheGutter() {
        val source = PngSource(listOf(page(40, 60, 360, 540), page(20, 120, 380, 480)))
        val adapter = PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, twoPage = true, preload = 0)
        val rv = recycler(adapter, 1200, 800)
        idle(rv) { rv.images().all { it.bitmap() != null } }
        assertEquals(listOf(0 to Target(600, 800), 1 to Target(600, 800)), source.requests.sortedBy { it.first })
        val (l, r) = rv.images()
        assertEquals(null, l.fitBox)
        assertEquals(1f, l.alignX)
        assertEquals(0f, r.alignX)
        adapter.release()
    }

    @Test
    fun aPageAloneIsCroppedAloneAndCentered() {
        val source = PngSource(listOf(page(40, 60, 360, 540)))
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, autoCrop = true, preload = 0
        )
        val rv = recycler(adapter, 1200, 800)
        idle(rv) { rv.images().first().bitmap() != null }
        assertEquals(listOf(0 to Target(1200, 800, autoCrop = true)), source.requests.toList())
        assertEquals(0.5f, rv.images().first().alignX)
        adapter.release()
    }

    @Test
    fun theNextPairIsPreloadedOnceMeasured() {
        val pages = List(4) { page(40, 60 + it * 10, 360, 540 - it * 10) }
        val source = PngSource(pages)
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, autoCrop = true, preload = 2
        )
        val rv = recycler(adapter, 1200, 800)
        // Only the first screen is bound; the pages of the next are decoded for the screen anyway.
        idle(rv) { (0 until 4).all { p -> source.requests.any { it.first == p && it.second.box != null } } }
        for (p in 0 until 4) assertTrue("page $p", source.requests.any { it.first == p && it.second.box != null })
        // A measure works on a sampled decode, never on the page at full size.
        val measures = source.requests.filter { it.second.width == 16 }
        assertTrue(measures.isNotEmpty())
        assertTrue(measures.all { it.second.capPixels < Target.DEFAULT_CAP_PIXELS })
        adapter.release()
    }

    @Test
    fun rightToLeftPairsShareTheirCropWithTheFirstPageOnTheRight() {
        val pages = listOf(page(40, 60, 360, 540), page(20, 120, 380, 480))
        val source = PngSource(pages)
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false,
            twoPage = true, rtl = true, autoCrop = true, preload = 0
        )
        val rv = recycler(adapter, 1200, 800)
        idle(rv) { source.requests.count { it.second.box != null } >= 2 && rv.images().all { it.bitmap() != null && it.bitmap()!!.width > 16 } }
        val shown = source.requests.filter { it.second.box != null }.associate { it.first to it.second }
        val first = shown.getValue(0)
        val second = shown.getValue(1)
        assertEquals(first.box!!.top, second.box!!.top, 0.0)
        assertEquals(first.box!!.bottom, second.box!!.bottom, 0.0)
        assertEquals(56.0 / 600, first.box!!.top, 1e-9)
        assertEquals(first.height, second.height)
        // The second page on the left, against the gutter; the first on the right.
        val (l, r) = rv.images()
        assertEquals(Decoders.decode(pages[1], second).width, l.bitmap()!!.width)
        assertEquals(Decoders.decode(pages[0], first).width, r.bitmap()!!.width)
        assertEquals(1f, l.alignX)
        assertEquals(0f, r.alignX)
        adapter.release()
    }
}
