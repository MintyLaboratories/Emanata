package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * [NativeResampler] on [FakeKernels], which stand in for the native code with [Resampler]'s
 * premultiplied model of it; what that model computes is checked in [ResamplerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeResamplerTest {

    /**
     * Native code as the JVM can have it: a job's bands written from [Resampler]'s
     * premultiplied model, every call recorded. Begins on the GPU only when [gpu], fails
     * the band [failAt] when set.
     */
    private class FakeKernels(val gpu: Boolean = true, val failAt: Int = -1) : PixelKernels {
        class Job(val src: Bitmap, val dst: Bitmap, val linear: Boolean, val workers: Int, val gpu: Boolean) {
            val axes = arrayOfNulls<Resampler.Axis>(2)
            var ended = false
            val bands: MutableList<Triple<Int, Int, Int>> = Collections.synchronizedList(ArrayList())
            val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
            val cores: MutableList<Long> = Collections.synchronizedList(ArrayList())
            val threadIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
            lateinit var result: IntArray
        }

        val jobs = ArrayList<Job>()

        override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long {
            if (gpu && !this.gpu) return 0
            jobs += Job(src, dst, linear, workers, gpu)
            return jobs.size.toLong()
        }

        override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean {
            val j = jobs[job.toInt() - 1]
            j.axes[if (vertical) 1 else 0] = axis
            if (vertical) {
                val pixels = IntArray(j.src.width * j.src.height)
                j.src.getPixels(pixels, 0, j.src.width, 0, 0, j.src.width, j.src.height)
                j.result = model(pixels, j.src.width, j.src.height, j.axes[0]!!, axis, j.linear)
            }
            return true
        }

        override fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean {
            val j = jobs[job.toInt() - 1]
            check(!j.ended && worker in 0 until j.workers)
            if (j.bands.size == failAt) return false
            j.bands += Triple(worker, y0, y1)
            j.cores += cores
            j.threads += Thread.currentThread().name
            j.threadIds += Thread.currentThread().id
            j.dst.setPixels(j.result, y0 * j.dst.width, j.dst.width, 0, y0, j.dst.width, y1 - y0)
            return true
        }

        override fun end(job: Long) {
            val j = jobs[job.toInt() - 1]
            check(!j.ended)
            j.ended = true
        }

        /** The whole output as the model has it, for the window the axes were made for. */
        private fun model(src: IntArray, w: Int, h: Int, ax: Resampler.Axis, ay: Resampler.Axis, linear: Boolean): IntArray =
            windows.getValue(ax.start.size to ay.start.size).let { (window, scaling) ->
                Resampler.resample(src, w, h, window, ax.start.size, ay.start.size, linear, scaling, premultiplied = true)
            }
    }

    private fun use(k: FakeKernels) = k.also { NativeResampler.kernels = it }

    @Before
    fun setUp() {
        // The reference tablet's cores: four little, four large.
        NativeResampler.cores = CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024))
        NativeResampler.threads = 4
        NativeResampler.laterThreads = 1
        NativeResampler.pinned = true
        NativeResampler.laterOnLittle = false
    }

    @After
    fun tearDown() {
        NativeResampler.kernels = null
        NativeResampler.cores = null
        NativeResampler.engine = PixelEngine.NATIVE
        windows.clear()
    }

    private fun noise(w: Int, h: Int): Bitmap {
        val r = Random(w * 7 + h)
        return Bitmap.createBitmap(IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }, w, h, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun resample(
        src: Bitmap, w: Int, h: Int, window: Resampler.Window, scaling: Scaling, engine: PixelEngine = PixelEngine.NATIVE,
        canceled: () -> Boolean = { false }, urgent: () -> Boolean = { true }
    ): Pair<Boolean, Bitmap> {
        windows[w to h] = window to scaling
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return NativeResampler.resample(src, dst, window, false, scaling, canceled, engine, urgent = urgent) to dst
    }

    @Test
    fun waitedOnWorkRunsOnTheLargeCores() {
        val k = use(FakeKernels())
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        val job = k.jobs.single()
        assertEquals(4, job.workers)
        assertEquals(setOf(0b1111_0000L), job.cores.toSet())
        // The calling thread is one of them.
        assertTrue(Thread.currentThread().id in job.threadIds)
    }

    @Test
    fun laterWorkRunsOnItsOwnBudgetOnTheLargeCores() {
        val k = use(FakeKernels())
        NativeResampler.laterThreads = 1
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT, urgent = { false }).first)
        val job = k.jobs.single()
        assertEquals(setOf(0b1111_0000L), job.cores.toSet())
        // One worker: the calling thread alone.
        assertEquals(setOf(0), job.bands.map { it.first }.toSet())
        assertEquals(setOf(Thread.currentThread().id), job.threadIds)
    }

    @Test
    fun laterWorkCanBeSentToTheLittleCores() {
        val k = use(FakeKernels())
        NativeResampler.laterOnLittle = true
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT, urgent = { false }).first)
        assertEquals(setOf(0b0000_1111L), k.jobs.single().cores.toSet())
        // Waited-on work stays on the large cores.
        assertTrue(resample(noise(64, 64), 40, 401, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        assertEquals(setOf(0b1111_0000L), k.jobs.last().cores.toSet())
    }

    @Test
    fun workReadAheadThatComesOnScreenGrowsItsWorkers() {
        val k = use(FakeKernels())
        NativeResampler.laterThreads = 1
        val polls = AtomicInteger()
        // On screen after a few polls: the first bands ran on the calling thread alone.
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT, urgent = { polls.incrementAndGet() > 4 }).first)
        val job = k.jobs.single()
        assertEquals(0, job.bands.first().first)
        assertTrue(job.bands.map { it.first }.toSet().size > 1)
    }

    @Test
    fun unpinnedWorkersGoAnywhere() {
        val k = use(FakeKernels())
        NativeResampler.pinned = false
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        assertEquals(setOf(0L), k.jobs.single().cores.toSet())
    }

    @Test
    fun oneKindOfCorePinsNothing() {
        val k = use(FakeKernels())
        NativeResampler.cores = CpuCores(IntArray(8) { 1024 })
        NativeResampler.laterOnLittle = true
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT, urgent = { false }).first)
        assertEquals(setOf(0L), k.jobs.single().cores.toSet())
    }

    @Test
    fun cpuJobWritesEveryRowOnceAndEnds() {
        val k = use(FakeKernels())
        val src = noise(300, 200)
        val window = Resampler.Window(10.0, 5.5, 270.0, 190.0)
        val scaling = Scaling(ScaleFilter.LANCZOS6, 20, 50)
        val (done, dst) = resample(src, 123, 87, window, scaling)
        assertTrue(done)
        val job = k.jobs.single()
        assertTrue(job.ended && !job.gpu && job.workers == NativeResampler.threads)
        val rows = IntArray(87)
        job.bands.forEach { (_, y0, y1) -> for (y in y0 until y1) rows[y]++ }
        assertTrue(rows.all { it == 1 })
        // The taps are the resampler's own, for the window asked.
        val ax = Resampler.axis(300, 10.0, 270.0, 123, scaling)
        assertArrayEquals(ax.start, job.axes[0]!!.start)
        assertArrayEquals(ax.weights, job.axes[0]!!.weights, 0f)
        assertArrayEquals(ax.ringLo, job.axes[0]!!.ringLo)
        assertArrayEquals(Resampler.axis(200, 5.5, 190.0, 87, scaling).start, job.axes[1]!!.start)
        val out = IntArray(123 * 87).also { dst.getPixels(it, 0, 123, 0, 0, 123, 87) }
        assertArrayEquals(job.result, out)
    }

    @Test
    fun gpuJobRunsBandsInTurnOnTheCallingThread() {
        val k = use(FakeKernels())
        val (done, _) = resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU)
        assertTrue(done)
        val job = k.jobs.single()
        assertTrue(job.gpu && job.ended && job.workers == 1)
        assertTrue(job.bands.all { it.first == 0 })
        assertEquals(setOf(0L), job.cores.toSet())
        assertEquals(setOf(Thread.currentThread().name), job.threads)
    }

    @Test
    fun noGpuFallsBackToTheCpu() {
        val k = use(FakeKernels(gpu = false))
        val (done, _) = resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU)
        assertTrue(done)
        assertFalse(k.jobs.single().gpu)
    }

    @Test
    fun aFailedBandLeavesItToKotlin() {
        val k = use(FakeKernels(failAt = 2))
        val (done, _) = resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT)
        assertFalse(done)
        assertTrue(k.jobs.single().ended)
    }

    @Test
    fun cancelingStopsTheJobAndEndsIt() {
        val k = use(FakeKernels())
        var polls = 0
        assertThrows(CancellationException::class.java) {
            resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, canceled = { ++polls > 2 })
        }
        assertTrue(k.jobs.single().ended)
        assertTrue(k.jobs.single().bands.size < 12)
    }

    @Test
    fun opaqueBitmapsGoNativeThoughNotReportedPremultiplied() {
        val k = use(FakeKernels())
        val src = noise(64, 64).copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(false) }
        assertTrue(resample(src, 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        assertTrue(k.jobs.single().ended)
    }

    @Test
    fun kotlinEngineAndUnfitBitmapsLeaveItToKotlin() {
        val k = use(FakeKernels())
        assertFalse(resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.KOTLIN).first)
        val rgb565 = noise(64, 64).copy(Bitmap.Config.RGB_565, false)
        assertFalse(resample(rgb565, 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        val unpremultiplied = noise(64, 64).copy(Bitmap.Config.ARGB_8888, true).apply { isPremultiplied = false }
        assertFalse(resample(unpremultiplied, 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        assertTrue(k.jobs.isEmpty())
    }

    @Test
    fun withoutNativeCodeBitmapsGoThroughKotlin() {
        // No kernels on the JVM: BitmapUtils resamples through Resampler, as before.
        NativeResampler.kernels = null
        val src = noise(90, 60)
        val out = BitmapUtils.resample(src, Plan(30, 20, 0.0, 0.0, 90.0, 60.0), linear = false)
        val pixels = IntArray(90 * 60).also { src.getPixels(it, 0, 90, 0, 0, 90, 60) }
        val expected = Resampler.resample(pixels, 90, 60, Resampler.Window.of(90, 60), 30, 20)
        assertArrayEquals(expected, IntArray(30 * 20).also { out.getPixels(it, 0, 30, 0, 0, 30, 20) })
    }

    @Test
    fun bitmapsGoThroughNativeCodeWhereItLoads() {
        val k = use(FakeKernels())
        BitmapUtils.resample(noise(90, 60).also { windows[30 to 20] = Resampler.Window.of(90, 60) to Scaling.DEFAULT }, Plan(30, 20, 0.0, 0.0, 90.0, 60.0), false, Scaling.DEFAULT)
        assertTrue(k.jobs.single().ended)
    }

    private companion object {
        /** The window and scaling of each output size asked, for [FakeKernels]' model. */
        val windows = HashMap<Pair<Int, Int>, Pair<Resampler.Window, Scaling>>()
    }
}
