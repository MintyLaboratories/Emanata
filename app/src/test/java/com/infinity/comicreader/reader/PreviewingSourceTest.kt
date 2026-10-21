package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewingSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Counts the writes it runs, inline. */
    private class CountingWriter : Executor {
        var writes = 0
        override fun execute(command: Runnable) {
            writes++
            command.run()
        }
    }

    private val writer = CountingWriter()
    private val store by lazy { PreviewStore(File(temp.root, "previews"), writer) }

    /** A [w] x [h] page: a white margin around a dark block, as PNG. */
    private fun framedPage(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawRect(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f, Paint().apply { color = Color.rgb(20, 20, 60) })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Pages decoded from encoded bytes, as archives decode them. */
    private class EncodedPages(private val pages: List<ByteArray>, private val quick: Bitmap? = null) : PageSource {
        var quickAsked = 0
        override val pageCount get() = pages.size
        override fun loadPage(index: Int, target: Target): Bitmap = Decoders.decode(pages[index], target)
        override fun loadPreview(index: Int): Bitmap? = quick.also { quickAsked++ }
        override fun pageName(index: Int) = "page$index.png"
        override fun close() = Unit
    }

    /** Pages rendered straight at the target size, as PDFs are. */
    private class RenderedPages : PageSource {
        override val pageCount get() = 1
        override fun loadPage(index: Int, target: Target): Bitmap {
            val plan = target.plan(600.0, 900.0)
            return Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        }
        override fun close() = Unit
    }

    private fun key(index: Int, name: String? = "page$index.png") = PreviewStore.keyOf("book", index, name)

    @Test
    fun croppedTileStillLeavesWholePagePreview() {
        val source = PreviewingSource(EncodedPages(listOf(framedPage(400, 600))), store, "book")
        val tile = source.loadPage(0, Target(50, 50, Fit.COVER, autoCrop = true))
        assertEquals(50, tile.width)
        val preview = store.get(key(0))
        assertNotNull(preview)
        assertArrayEquals(intArrayOf(171, 256), intArrayOf(preview!!.width, preview.height))
        // The white margin is in the preview: it was made before the crop.
        assertTrue(Color.red(preview.getPixel(2, 2)) > 240)
    }

    @Test
    fun storedPreviewIsNotMadeAgain() {
        val source = PreviewingSource(EncodedPages(listOf(framedPage(400, 600))), store, "book")
        source.loadPage(0, Target(100, 100))
        val first = writer.writes
        source.loadPage(0, Target(200, 200))
        source.loadDetail(0, Target(200, 200), Long.MAX_VALUE)
        assertEquals(1, first)
        assertEquals(first, writer.writes)
    }

    @Test
    fun detailLoadsLeavePreviews() {
        val source = PreviewingSource(EncodedPages(listOf(framedPage(300, 450))), store, "book")
        source.loadDetail(0, Target(100, 100), Long.MAX_VALUE).release()
        assertNotNull(store.get(key(0)))
    }

    @Test
    fun renderedWholePageLeavesPreviewButCroppedOneDoesNot() {
        val source = PreviewingSource(RenderedPages(), store, "book")
        source.loadPage(0, Target(60, 60, Fit.COVER))
        assertNull(store.get(key(0, null)))
        source.loadPage(0, Target(300, 300))
        val preview = store.get(key(0, null))
        assertNotNull(preview)
        assertEquals(256, preview!!.height)
    }

    @Test
    fun quickPreviewIsStoredAndThenServedFromStore() {
        val quick = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val inner = EncodedPages(listOf(framedPage(200, 300)), quick)
        val source = PreviewingSource(inner, store, "book")
        assertSame(quick, source.loadPreview(0))
        val again = source.loadPreview(0)
        assertNotNull(again)
        assertEquals(20, again!!.width)
        assertEquals(1, inner.quickAsked)
    }

    @Test
    fun noPreviewWithoutQuickPathOrStore() {
        val source = PreviewingSource(EncodedPages(listOf(framedPage(200, 300))), store, "book")
        assertNull(source.loadPreview(0))
    }

    @Test
    fun captureTakesOnlyTheFirstDecodeAndRestoresTheOuterSink() {
        val png = framedPage(40, 60)
        val seen = ArrayList<String>()
        Decoders.capturing({ seen += "outer ${it.width}" }) {
            Decoders.capturing({ seen += "inner ${it.width}" }) {
                Decoders.decode(png, Target(10, 10))
                Decoders.decode(png, Target(10, 10))
            }
            Decoders.decode(png, Target(10, 10))
        }
        Decoders.decode(png, Target(10, 10))
        assertEquals(listOf("inner 40", "outer 40"), seen)
    }
}
