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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
     * the band [failAt] when set. A GPU band runs in chunks of [chunk] rows, asking its stop
     * before each and recording the answer, each chunk recorded as a band; it fails at the
     * chunk [failGpuAt] when set, and throws [gpuThrows] when set. The chunking is the
     * fake's own: the native chunks show on the device, in the "ResampleGpu" trace.
     * [beforeBand] runs as each CPU band is asked for, on the worker's thread.
     */
    private class FakeKernels(
        val gpu: Boolean = true, val failAt: Int = -1, val chunk: Int = 16, val failGpuAt: Int = -1, val gpuThrows: Throwable? = null,
        val usable: Boolean = true, val beforeBand: (worker: Int, job: Job) -> Unit = { _, _ -> }
    ) : PixelKernels {
        override fun gpuUsable() = usable

        class Job(val src: Bitmap, val dst: Bitmap, val linear: Boolean, val workers: Int, val gpu: Boolean) {
            val axes = arrayOfNulls<Resampler.Axis>(2)
            var ended = false
            val bands: MutableList<Triple<Int, Int, Int>> = Collections.synchronizedList(ArrayList())
            val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
            val cores: MutableList<Long> = Collections.synchronizedList(ArrayList())
            val threadIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
            lateinit var result: IntArray

            /** Bands asked for so far, counted as each call starts: workers race to append [bands]. */
            val calls = AtomicInteger()

            /** The rows of each GPU band asked for. */
            val gpuBands: MutableList<Pair<Int, Int>> = Collections.synchronizedList(ArrayList())

            /** What each GPU band's stop answered, in turn; null for a band given none. */
            val stops: MutableList<Boolean?> = Collections.synchronizedList(ArrayList())
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
            beforeBand(worker, j)
            if (j.calls.getAndIncrement() == failAt) return false
            j.bands += Triple(worker, y0, y1)
            j.cores += cores
            j.threads += Thread.currentThread().name
            j.threadIds += Thread.currentThread().id
            j.dst.setPixels(j.result, y0 * j.dst.width, j.dst.width, 0, y0, j.dst.width, y1 - y0)
            return true
        }

        override fun gpuBand(job: Long, y0: Int, y1: Int, cores: Long, stop: PixelKernels.Stop?): Int {
            val j = jobs[job.toInt() - 1]
            check(j.gpu && !j.ended)
            j.gpuBands += y0 to y1
            gpuThrows?.let { throw it }
            var y = y0
            while (y < y1) {
                val stopped = stop?.stop()
                j.stops += stopped
                if (stopped == true) return PixelKernels.BAND_CANCELED
                val end = minOf(y1, (y / chunk + 1) * chunk)
                if (j.bands.size == failGpuAt || !band(job, 0, y, end, cores)) return PixelKernels.BAND_FAILED
                y = end
            }
            return PixelKernels.BAND_DONE
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
        NativeResampler.gpuPinned = true
        NativeResampler.laterOnLittle = false
        NativeResampler.onPrime = false
    }

    @After
    fun tearDown() {
        NativeResampler.kernels = null
        NativeResampler.cores = null
        NativeResampler.engine = PixelEngine.NATIVE
        NativeResampler.gpuPinned = true
        NativeResampler.onPrime = false
        windows.clear()
    }

    private fun noise(w: Int, h: Int): Bitmap {
        val r = Random(w * 7 + h)
        return Bitmap.createBitmap(IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }, w, h, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun resample(
        src: Bitmap, w: Int, h: Int, window: Resampler.Window, scaling: Scaling, engine: PixelEngine = PixelEngine.NATIVE,
        canceled: () -> Boolean = { false }, urgent: () -> Boolean = { true }, fallback: Boolean = true
    ): Pair<Boolean, Bitmap> {
        windows[w to h] = window to scaling
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return NativeResampler.resample(src, dst, window, false, scaling, canceled, engine, fallback, urgent) to dst
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
    fun workCanBeSentToTheFastestCoreAlone() {
        val k = use(FakeKernels())
        NativeResampler.onPrime = true
        assertTrue(resample(noise(64, 64), 40, 400, Resampler.Window.of(64, 64), Scaling.DEFAULT).first)
        assertEquals(setOf(0b1000_0000L), k.jobs.single().cores.toSet())
        assertTrue(resample(noise(64, 64), 40, 401, Resampler.Window.of(64, 64), Scaling.DEFAULT, urgent = { false }).first)
        assertEquals(setOf(0b1000_0000L), k.jobs.last().cores.toSet())
    }

    @Test
    fun workReadAheadThatComesOnScreenGrowsItsWorkers() {
        val polls = AtomicInteger()
        // Once on screen, the calling thread waits for a worker started then to take a band: bands this
        // small could all go to it before a busy pool runs one.
        val othersBanded = AtomicBoolean()
        // One 5 s wait in all, from the first: workers that never grow fail the test soon.
        val until = AtomicLong()
        val k = use(FakeKernels(beforeBand = { worker, _ ->
            if (worker != 0) othersBanded.set(true)
            if (worker == 0 && polls.get() > 4) {
                until.compareAndSet(0, System.currentTimeMillis() + 5_000)
                while (!othersBanded.get() && System.currentTimeMillis() < until.get()) Thread.sleep(1)
            }
        }))
        NativeResampler.laterThreads = 1
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
    fun gpuJobRunsAsOneBandOnTheCallingThread() {
        val k = use(FakeKernels())
        val (done, dst) = resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU)
        assertTrue(done)
        val job = k.jobs.single()
        assertTrue(job.gpu && job.ended && job.workers == 1)
        assertEquals(listOf(0 to 120), job.gpuBands)
        assertTrue(job.stops.isNotEmpty() && null !in job.stops)
        assertEquals(setOf(0b1111_0000L), job.cores.toSet())
        assertEquals(setOf(Thread.currentThread().name), job.threads)
        assertArrayEquals(job.result, IntArray(40 * 120).also { dst.getPixels(it, 0, 40, 0, 0, 40, 120) })
    }

    @Test
    fun theGpuBandStopsAsCancelingSays() {
        val k = use(FakeKernels())
        var polls = 0
        assertThrows(CancellationException::class.java) {
            resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, canceled = { ++polls > 3 })
        }
        // Asked once before the job began, then each time the band asked its stop; no fallback to the CPU.
        val job = k.jobs.single()
        assertEquals(listOf(false, false, true), job.stops)
        assertEquals(1 + job.stops.size, polls)
        assertTrue(job.ended)
    }

    @Test
    fun gpuJobCanceledBeforeItStartsBeginsNothing() {
        val k = use(FakeKernels())
        assertThrows(CancellationException::class.java) {
            resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, canceled = { true })
        }
        assertTrue(k.jobs.isEmpty())
    }

    @Test
    fun whatCancelingThrowsEndsTheGpuJob() {
        val k = use(FakeKernels())
        var polls = 0
        assertThrows(IllegalStateException::class.java) {
            resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, canceled = { check(++polls < 3); false })
        }
        assertEquals(listOf(false), k.jobs.single().stops)
        assertTrue(k.jobs.single().ended)
    }

    @Test
    fun aFailedGpuBandFallsBackToTheCpuUnlessAskedNot() {
        val k = use(FakeKernels(failGpuAt = 2))
        assertTrue(resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU).first)
        assertEquals(listOf(true, false), k.jobs.map { it.gpu })
        assertTrue(k.jobs.all { it.ended })
        val strict = use(FakeKernels(failGpuAt = 2))
        assertFalse(resample(noise(64, 64), 40, 121, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, fallback = false).first)
        assertTrue(strict.jobs.single().ended)
    }

    @Test
    fun aMismatchedGpuEntryPointFallsBackToTheCpuUnlessAskedNot() {
        // As when the native side cannot find the stop callback's method.
        val k = use(FakeKernels(gpuThrows = NoSuchMethodError("stop")))
        assertTrue(resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU).first)
        assertEquals(listOf(true, false), k.jobs.map { it.gpu })
        assertTrue(k.jobs.all { it.ended })
        val strict = use(FakeKernels(gpuThrows = UnsatisfiedLinkError("nGpuBand")))
        assertFalse(resample(noise(64, 64), 40, 121, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, fallback = false).first)
        assertTrue(strict.jobs.single().ended)
    }

    @Test
    fun gpuCallerIsPlacedAsACpuWorkerWouldBe() {
        val k = use(FakeKernels())
        val page = noise(64, 64)
        fun gpu(h: Int, urgent: Boolean = true) =
            assertTrue(resample(page, 40, h, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, urgent = { urgent }).first)
        // Large cores whether waited on or not, as CPU workers.
        gpu(120, urgent = false)
        NativeResampler.laterOnLittle = true
        gpu(121, urgent = false)
        gpu(122)
        NativeResampler.laterOnLittle = false
        NativeResampler.onPrime = true
        gpu(123)
        NativeResampler.onPrime = false
        NativeResampler.gpuPinned = false
        gpu(124)
        assertEquals(listOf(0b1111_0000L, 0b0000_1111L, 0b1111_0000L, 0b1000_0000L, 0L), k.jobs.map { it.cores.toSet().single() })
        // Its own setting, apart from the CPU workers'.
        NativeResampler.gpuPinned = true
        NativeResampler.pinned = false
        gpu(125)
        assertEquals(setOf(0b1111_0000L), k.jobs.last().cores.toSet())
        // Unpinned for one setup's timing, whatever gpuPinned says.
        val dst = Bitmap.createBitmap(40, 125, Bitmap.Config.ARGB_8888)
        windows[40 to 125] = Resampler.Window.of(64, 64) to Scaling.DEFAULT
        assertTrue(NativeResampler.resample(page, dst, Resampler.Window.of(64, 64), false, Scaling.DEFAULT, engine = PixelEngine.GPU, pin = false))
        assertEquals(setOf(0L), k.jobs.last().cores.toSet())
        // One kind of core: nothing to pin to.
        NativeResampler.cores = CpuCores(IntArray(8) { 1024 })
        gpu(126)
        assertEquals(setOf(0L), k.jobs.last().cores.toSet())
    }

    @Test
    fun aGpuPathTurnedOffSendsJobsToTheCpuWithoutTryingIt() {
        val k = use(FakeKernels(usable = false))
        assertFalse(NativeResampler.gpuUsable)
        assertTrue(resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU).first)
        assertEquals(listOf(false), k.jobs.map { it.gpu })
        assertFalse(resample(noise(64, 64), 40, 121, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU, fallback = false).first)
        assertEquals(1, k.jobs.size)
        use(FakeKernels())
        assertTrue(NativeResampler.gpuUsable)
    }

    @Test
    fun noGpuFallsBackToTheCpu() {
        val k = use(FakeKernels(gpu = false))
        val (done, _) = resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU)
        assertTrue(done)
        assertFalse(k.jobs.single().gpu)
    }

    @Test
    fun theCpuFallbackKeepsItsOwnPinning() {
        val k = use(FakeKernels(gpu = false))
        NativeResampler.pinned = false
        assertTrue(resample(noise(64, 64), 40, 120, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU).first)
        assertEquals(setOf(0L), k.jobs.single().cores.toSet())
        NativeResampler.pinned = true
        NativeResampler.gpuPinned = false
        assertTrue(resample(noise(64, 64), 40, 121, Resampler.Window.of(64, 64), Scaling.DEFAULT, PixelEngine.GPU).first)
        assertEquals(setOf(0b1111_0000L), k.jobs.last().cores.toSet())
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
