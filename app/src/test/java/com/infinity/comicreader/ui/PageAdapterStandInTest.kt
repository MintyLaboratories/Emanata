package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Avif
import com.infinity.comicreader.reader.AvifCodec
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.FakeAvifCodec
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A layered AVIF page's first layer standing in while the page decodes ([Decoders.standingIn]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAdapterStandInTest {

    private val layer = Color.rgb(200, 60, 20)
    private val full = Color.rgb(40, 120, 200)

    /** A file [Decoders] takes for AVIF: an `ftyp` box with the `avif` brand. */
    private val avifBytes = byteArrayOf(0, 0, 0, 16) + "ftypavif".toByteArray() + ByteArray(4) + ByteArray(64)

    /** A 1200 x 1800 layered file whose full image renders only after [release]. */
    private class StalledCodec(private val inner: AvifCodec) : AvifCodec by inner {
        val release = CountDownLatch(1)
        val renders = AtomicInteger()

        override fun render(handle: Long, bitmap: Bitmap, sample: Int, threads: Int) {
            if (renders.incrementAndGet() == 2) release.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
            inner.render(handle, bitmap, sample, threads)
        }
    }

    private fun stalled(): StalledCodec {
        val file = FakeAvifCodec.info(1200, 1800)
        return StalledCodec(FakeAvifCodec(file, listOf(FakeAvifCodec.info(300, 450), file), listOf(layer, full))).also {
            Avif.codec = it
        }
    }

    private inner class AvifSource(override val pageCount: Int) : PageSource {
        val loads = AtomicInteger()

        override fun loadPage(index: Int, target: Target): Bitmap {
            loads.incrementAndGet()
            return Decoders.decode(avifBytes, target)
        }

        override fun close() {}
    }

    @After
    fun reset() {
        Avif.codec = null
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun adapter(source: PageSource) =
        PageAdapter(source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = false, preload = 0)

    /** The center color of what [holder] shows, or null. */
    private fun shown(holder: PageAdapter.PageHolder): Int? {
        val bitmap = (holder.images[0].drawable as? BitmapDrawable)?.bitmap ?: return null
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    @Test
    fun firstLayerShowsUntilThePageReplacesIt() {
        val codec = stalled()
        val adapter = adapter(AvifSource(1))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)

        idleUntil("the first layer shown") { shown(holder) != null }
        assertEquals(layer, shown(holder))

        codec.release.countDown()
        idleUntil("the full page shown") { shown(holder) == full }
        assertEquals(full, shown(holder))
        assertTrue(holder.images[0].drawable is BitmapDrawable)
        adapter.release()
    }

    @Test
    fun aSecondViewJoiningTheDecodeShowsTheStandInToo() {
        val codec = stalled()
        val source = AvifSource(1)
        val adapter = adapter(source)
        val first = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(first, 0)
        idleUntil("the first layer shown") { shown(first) != null }
        assertEquals(layer, shown(first))

        // Another view binds the same page: it joins the running decode and shows its stand-in.
        val second = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(second, 0)
        idleUntil("the second view's stand-in shown") { shown(second) != null }
        assertEquals(layer, shown(second))

        codec.release.countDown()
        idleUntil("the full page shown in both") { shown(first) == full && shown(second) == full }
        assertEquals(full, shown(first))
        assertEquals(full, shown(second))
        assertEquals(1, source.loads.get())
        adapter.release()
    }
}
