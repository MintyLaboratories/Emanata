package minty.emanata.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.reader.DecodeException
import minty.emanata.reader.PageSource
import minty.emanata.reader.Target
import minty.emanata.testing.Waits
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThumbAdapterTest {

    /** Page 1 never decodes; the others are 1x1 bitmaps. */
    private class BrokenPageSource : PageSource {
        override val pageCount: Int = 2
        override fun loadPage(index: Int, target: Target): Bitmap {
            if (index == 1) throw DecodeException("Could not decode this JXL image")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun pageName(index: Int): String = "chapter/page-$index.jxl"
        override fun close() {}
    }

    /** One page whose exact decode waits for [decode]; its preview is ready at once. */
    private class SlowPageSource : PageSource {
        val decode = CountDownLatch(1)
        val preview: Bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        override val pageCount: Int = 1
        override fun loadPage(index: Int, target: Target): Bitmap {
            // Held until the test opens it: only a hang ends it.
            decode.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
            return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        }

        override fun loadPreview(index: Int): Bitmap = preview
        override fun close() {}
    }

    /**
     * Pages whose previews wait for [preview], the last having none; exact decodes are ready
     * at once and counted, those made while [preview] is shut in [early].
     */
    private class SlowPreviewSource : PageSource {
        val preview = CountDownLatch(1)
        val loads = AtomicInteger()
        val early = AtomicInteger()

        /** Pages whose preview is asked for. */
        val asked: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override val pageCount: Int = 3
        override fun loadPage(index: Int, target: Target): Bitmap {
            if (preview.count > 0) early.incrementAndGet()
            loads.incrementAndGet()
            return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        }

        override fun loadPreview(index: Int): Bitmap? {
            asked += index
            // Held until the test opens it: only a hang ends it.
            preview.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
            return if (index == pageCount - 1) null else Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        }

        override fun close() {}
    }

    /** One page with a preview; its exact decodes succeed while [works], else fail. */
    private class FlakyPageSource : PageSource {
        @Volatile
        var works = true
        val preview: Bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val failures = AtomicInteger()
        override val pageCount: Int = 1
        override fun loadPage(index: Int, target: Target): Bitmap {
            if (!works) {
                failures.incrementAndGet()
                throw java.util.zip.ZipException("invalid distance too far back")
            }
            return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        }

        override fun loadPreview(index: Int): Bitmap = preview
        override fun close() {}
    }

    private fun listShowing(adapter: ThumbAdapter): RecyclerView {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Emanata)
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 400, 2000)
        }
    }

    private fun holder(list: RecyclerView, position: Int) = list.findViewHolderForAdapterPosition(position) as ThumbAdapter.Holder

    /** Waits until the thumbnail request of [holder] is over: its result, or its failure, is shown. */
    private fun requestDone(holder: ThumbAdapter.Holder) = idleUntil("the thumbnail request done") { holder.job?.isCompleted == true }

    @Test
    fun previewStandsInUntilExactThumbnail() {
        val source = SlowPageSource()
        val adapter = ThumbAdapter(source, currentPage = 0) {}
        val events = ArrayList<Pair<Int, Boolean>>()
        adapter.onShown = { position, exact -> events += position to exact }
        val list = listShowing(adapter)
        val image = (list.findViewHolderForAdapterPosition(0) as ThumbAdapter.Holder).image
        idleUntil("the preview shown") { image.drawable != null }
        assertSame(source.preview, (image.drawable as BitmapDrawable).bitmap)
        assertEquals(ImageView.ScaleType.CENTER_CROP, image.scaleType)
        source.decode.countDown()
        idleUntil("the exact thumbnail shown") { (image.drawable as? BitmapDrawable)?.bitmap !== source.preview }
        assertEquals(image.width, (image.drawable as BitmapDrawable).bitmap.width)
        assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
        assertEquals(listOf(0 to false, 0 to true), events)
        adapter.release()
    }

    @Test
    fun exactThumbnailsWaitForEveryPreview() {
        val source = SlowPreviewSource()
        val adapter = ThumbAdapter(source, currentPage = 0) {}
        val events = ArrayList<Pair<Int, Boolean>>()
        adapter.onShown = { position, exact -> events += position to exact }
        val list = listShowing(adapter)
        fun image(position: Int) = (list.findViewHolderForAdapterPosition(position) as ThumbAdapter.Holder).image
        // Every page waits on its preview: no exact thumbnail may start before they are in.
        idleUntil("every preview asked for") { source.asked.size == source.pageCount }
        assertEquals(0, source.loads.get())
        source.preview.countDown()
        idleUntil("every exact thumbnail shown") { (0 until source.pageCount).all { (image(it).drawable as? BitmapDrawable)?.bitmap?.width == image(it).width } }
        assertEquals(0, source.early.get())
        assertEquals(source.pageCount, source.loads.get())
        assertEquals(listOf(0 to false, 1 to false), events.take(2).sortedBy { it.first })
        assertEquals(setOf(0 to true, 1 to true, 2 to true), events.drop(2).toSet())
        adapter.release()
    }

    @Test
    fun failedThumbnailKeepsItsPreview() {
        val source = FlakyPageSource().apply { works = false }
        val adapter = ThumbAdapter(source, currentPage = 0) {}
        val list = listShowing(adapter)
        val image = holder(list, 0).image
        idleUntil("the thumbnail failed") { source.failures.get() > 0 }
        requestDone(holder(list, 0))
        assertSame(source.preview, (image.drawable as BitmapDrawable).bitmap)
        adapter.release()
    }

    @Test
    fun failedRedoKeepsTheThumbnailShown() {
        val source = FlakyPageSource()
        val adapter = ThumbAdapter(source, currentPage = 0) {}
        val list = listShowing(adapter)
        val image = (list.findViewHolderForAdapterPosition(0) as ThumbAdapter.Holder).image
        idleUntil("the exact thumbnail shown") { (image.drawable as? BitmapDrawable)?.bitmap?.width == image.width }
        val shown = (image.drawable as BitmapDrawable).bitmap
        source.works = false
        // A new width asks for the thumbnail again.
        list.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 300, 2000)
        idleUntil("the redo failed") { source.failures.get() > 0 }
        requestDone(holder(list, 0))
        assertSame(shown, (image.drawable as BitmapDrawable).bitmap)
        adapter.release()
    }

    @Test
    fun failedThumbnailShowsPlaceholder() {
        val adapter = ThumbAdapter(BrokenPageSource(), currentPage = 0) {}
        val list = listShowing(adapter)
        fun image(position: Int) =
            (list.findViewHolderForAdapterPosition(position) as ThumbAdapter.Holder).image

        idleUntil("both thumbnails shown") { image(0).drawable != null && image(1).drawable != null }
        assertTrue(image(0).drawable is BitmapDrawable)
        assertTrue(image(1).drawable is ErrorPageDrawable)
        adapter.release()
    }
}
