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
import minty.emanata.reader.Half
import minty.emanata.reader.PageSource
import minty.emanata.reader.Target
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekPreviewAdapterTest {

    /** A solid book: every page has a [previewW] x [previewH] preview; [loads] counts page reads. */
    private class SolidSource(private val previewW: Int = 20, private val previewH: Int = 30) : PageSource {
        val loads = AtomicInteger()
        override val pageCount: Int = 3
        override val cheapSizes: Boolean = false
        override fun loadPage(index: Int, target: Target): Bitmap {
            loads.incrementAndGet()
            return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        }

        override fun loadPreview(index: Int): Bitmap = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
        override fun close() {}
    }

    /** Page [page]'s cell, its size unknown. */
    private fun cell(page: Int) =
        SeekStrip.Cell(listOf(PageLayout.Part(page, Half.WHOLE)), "${page + 1}", listOf(SeekStrip.DEFAULT_ASPECT), listOf(true))

    private fun stripShowing(adapter: SeekPreviewAdapter): RecyclerView {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_ComicReader)
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            measure(
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            layout(0, 0, 2000, measuredHeight)
        }
    }

    private fun RecyclerView.image(position: Int): ImageView =
        (findViewHolderForAdapterPosition(position) as SeekPreviewAdapter.Holder).slots[0].image

    @Test
    fun aSolidBooksPreviewsAreScaledToTheCellWithoutReadingPages() {
        val source = SolidSource()
        val adapter = SeekPreviewAdapter(source, thumbHeight = 90, linear = false, exact = false) {}
        adapter.show(List(3) { cell(it) }, current = 0)
        val strip = stripShowing(adapter)
        idleUntil("page 0's preview at the cell's height") { (strip.image(0).drawable as? BitmapDrawable)?.bitmap?.height == 90 }
        val bitmap = (strip.image(0).drawable as BitmapDrawable).bitmap
        assertEquals(60, bitmap.width)
        assertEquals(90, bitmap.height)
        assertEquals(ImageView.ScaleType.CENTER, strip.image(0).scaleType)
        assertEquals(0, source.loads.get())
        adapter.release()
    }

    @Test
    fun aCellOfUnknownSizeTakesItsPreviewsShape() {
        val source = SolidSource(previewW = 60, previewH = 30)
        val adapter = SeekPreviewAdapter(source, thumbHeight = 90, linear = false, exact = false) {}
        var resized = 0
        adapter.onResized = { resized++ }
        adapter.show(List(3) { cell(it) }, current = 0)
        val strip = stripShowing(adapter)
        idleUntil("page 0's cell to take its preview's shape") { adapter.cells[0].aspects[0] == 2f }
        assertEquals(listOf(false), adapter.cells[0].guessed)
        assertTrue(resized > 0)
        idleUntil("page 0's preview at its shape's width", step = {
            strip.measure(
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            strip.layout(0, 0, 2000, strip.measuredHeight)
        }) { (strip.image(0).drawable as? BitmapDrawable)?.bitmap?.width == 180 }
        assertEquals(180, strip.image(0).width)
        assertEquals(90, (strip.image(0).drawable as BitmapDrawable).bitmap.height)
        // Shown again, as on the strip's next opening, the shape is kept.
        adapter.show(List(3) { cell(it) }, current = 0)
        assertEquals(2f, adapter.cells[0].aspects[0], 0f)
        adapter.release()
    }

    @Test
    fun aPageThatFailsWithNoPreviewShowsThePlaceholder() {
        val source = object : PageSource {
            override val pageCount: Int = 1
            override fun loadPage(index: Int, target: Target): Bitmap = throw DecodeException("Could not decode this page")
            override fun close() {}
        }
        val adapter = SeekPreviewAdapter(source, thumbHeight = 90, linear = false, exact = true) {}
        adapter.show(listOf(cell(0)), current = 0)
        val strip = stripShowing(adapter)
        idleUntil("page 0's placeholder") { strip.image(0).drawable != null }
        assertTrue(strip.image(0).drawable is ErrorPageDrawable)
        adapter.release()
    }
}
