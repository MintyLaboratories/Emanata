package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** The one-pixel rim strips [BitmapUtils.sharpen] makes are all freed, after any native job ends, however sharpening stops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RcasRimTest {

    @Before
    @After
    fun reset() {
        NativeResampler.kernels = null
        NativeResampler.engine = PixelEngine.NATIVE
    }

    private val w = 40
    private val h = 30

    /** A window inside the source on every side, shown at 1:1: four strips, top, bottom, left, right. */
    private val window = Resampler.Window(8.0, 6.0, 16.0, 12.0)

    /** Opaque mid grays in a checkerboard: sharpening changes every pixel. */
    private fun checker(bw: Int, bh: Int): Bitmap =
        Bitmap.createBitmap(IntArray(bw * bh) { i -> gray(if ((i % bw + i / bw) % 2 == 0) 100 else 140) }, bw, bh, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, true)

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Makes strips as production does, keeping each; throws [failAt]'s error when making strip number [failAt] (from 1). */
    private class Strips(val failAt: Int = 0) : (Int, Int) -> Bitmap {
        val made = ArrayList<Bitmap>()
        override fun invoke(sw: Int, sh: Int): Bitmap {
            if (made.size + 1 == failAt) throw OutOfMemoryError("strip ${made.size + 1}")
            return Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888, true).also { made += it }
        }
    }

    /** Fills each strip with opaque gray, counting calls. */
    private class Fill : (Resampler.Window, Bitmap) -> Unit {
        var calls = 0
        override fun invoke(win: Resampler.Window, strip: Bitmap) {
            calls++
            strip.eraseColor(0xFF787878.toInt())
        }
    }

    private fun sharpen(out: Bitmap, strips: Strips, fill: (Resampler.Window, Bitmap) -> Unit = Fill(), canceled: () -> Boolean = { false }) =
        BitmapUtils.sharpen(checker(w, h), out, window, 1f, canceled, strips, fill)

    private fun assertAllFreed(strips: Strips, count: Int) {
        assertEquals(count, strips.made.size)
        assertTrue("every strip recycled", strips.made.all { it.isRecycled })
    }

    @Test
    fun canceledBeforeTheKotlinPassFreesEveryStrip() {
        val out = checker(16, 12)
        val before = pixels(out)
        val strips = Strips()
        val fill = Fill()
        val thrown = runCatching { sharpen(out, strips, fill, canceled = { true }) }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(4, fill.calls)
        assertAllFreed(strips, 4)
        assertArrayEquals("no band ran", before, pixels(out))
    }

    @Test
    fun canceledDuringTheKotlinPassFreesEveryStrip() {
        val out = checker(16, 12)
        val before = pixels(out)
        val strips = Strips()
        val asks = AtomicInteger()
        // The first band asked runs; every later ask cancels.
        val thrown = runCatching { sharpen(out, strips, canceled = { asks.incrementAndGet() > 1 }) }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        assertAllFreed(strips, 4)
        val after = pixels(out)
        assertTrue("one band sharpened", after.indices.any { after[it] != before[it] })
        assertTrue("the others not", after.indices.any { after[it] == before[it] })
    }

    @Test
    fun runningOutOfMemoryForTheSecondStripFreesTheFirst() {
        val strips = Strips(failAt = 2)
        val fill = Fill()
        val thrown = runCatching { sharpen(checker(16, 12), strips, fill) }.exceptionOrNull()
        assertTrue("$thrown", thrown is OutOfMemoryError)
        assertEquals(1, fill.calls)
        assertAllFreed(strips, 1)
    }

    @Test
    fun aFillThatThrowsFreesEveryStripMadeSoFar() {
        val strips = Strips()
        var calls = 0
        val thrown = runCatching {
            sharpen(checker(16, 12), strips, fill = { _, _ -> if (++calls == 3) throw IllegalStateException("fill 3") })
        }.exceptionOrNull()
        assertTrue("$thrown", thrown is IllegalStateException)
        // The strip being filled counts too.
        assertAllFreed(strips, 3)
    }

    /** Native sharpening as the JVM can have it: refuses, fails or throws as told; notes whether the strips were still live when its job ended. */
    private class FakeKernels(val refuse: Boolean = false, val bandFails: Boolean = false, val bandThrows: Boolean = false) : PixelKernels {
        override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean) = 0L
        override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis) = false
        override fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long) = false
        override fun end(job: Long) {}

        var rim: Rcas.Rim<Bitmap>? = null
        val bands = AtomicInteger()
        var ended = 0
        var liveAtEnd = false

        override fun sharpenBegin(dst: Bitmap, rim: Rcas.Rim<Bitmap>, amount: Float, bands: Int, workers: Int): Long {
            if (refuse) return 0L
            this.rim = rim
            return 7L
        }

        override fun sharpenBand(job: Long, worker: Int, band: Int, cores: Long): Boolean {
            bands.incrementAndGet()
            if (bandThrows) throw IllegalStateException("band $band")
            return !bandFails
        }

        override fun sharpenEnd(job: Long) {
            ended++
            val r = rim!!
            liveAtEnd = listOfNotNull(r.top, r.bottom, r.left, r.right).let { it.size == 4 && it.none(Bitmap::isRecycled) }
        }
    }

    @Test
    fun aJobNativeCodeRefusesIsSharpenedInKotlinThenEveryStripFreed() {
        val k = FakeKernels(refuse = true)
        NativeResampler.kernels = k
        val out = checker(16, 12)
        val before = pixels(out)
        val strips = Strips()
        sharpen(out, strips)
        assertEquals(0, k.ended)
        assertAllFreed(strips, 4)
        val after = pixels(out)
        assertTrue("sharpened by Kotlin", after.indices.all { after[it] != before[it] })
    }

    @Test
    fun aFailedNativeBandEndsTheJobBeforeEveryStripIsFreed() {
        val k = FakeKernels(bandFails = true)
        NativeResampler.kernels = k
        val out = checker(16, 12)
        val before = pixels(out)
        val strips = Strips()
        sharpen(out, strips)
        assertTrue(k.bands.get() > 0)
        assertEquals(1, k.ended)
        assertTrue("strips live while the job held them", k.liveAtEnd)
        assertAllFreed(strips, 4)
        assertArrayEquals("no Kotlin pass after native code took the job", before, pixels(out))
    }

    @Test
    fun aNativeBandThatThrowsEndsTheJobBeforeEveryStripIsFreed() {
        val k = FakeKernels(bandThrows = true)
        NativeResampler.kernels = k
        val strips = Strips()
        val thrown = runCatching { sharpen(checker(16, 12), strips) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IllegalStateException)
        assertEquals(1, k.ended)
        assertTrue("strips live while the job held them", k.liveAtEnd)
        assertAllFreed(strips, 4)
    }

    @Test
    fun canceledDuringTheNativeJobEndsItBeforeEveryStripIsFreed() {
        val k = FakeKernels()
        NativeResampler.kernels = k
        val strips = Strips()
        val asks = AtomicInteger()
        val thrown = runCatching { sharpen(checker(16, 12), strips, canceled = { asks.incrementAndGet() > 1 }) }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        assertEquals("one band ran", 1, k.bands.get())
        assertEquals(1, k.ended)
        assertTrue("strips live while the job held them", k.liveAtEnd)
        assertAllFreed(strips, 4)
        assertFalse(k.rim!!.isEmpty)
    }
}
