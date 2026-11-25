package minty.emanata.reader

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import minty.emanata.App
import minty.emanata.testing.Waits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [NativeResampler.trim] and its callers: which memory trims, engine changes and ends of
 * calibration free the GPU path's buffers, on the idle timer's thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResampleTrimTest {

    /**
     * Kernels that only record the threads their trims ran on, and whether each freed the shaders.
     * With a [gate], each trim first waits for the test to open it, holding the idle timer's thread.
     */
    private class Trims(private val gate: CountDownLatch? = null) : PixelKernels {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val programs: MutableList<Boolean> = Collections.synchronizedList(ArrayList())

        override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long = 0

        override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean = false

        override fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean = false

        override fun end(job: Long) {}

        override fun trim(programs: Boolean) {
            if (gate != null && !gate.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)) throw AssertionError("Gave up waiting for the test to free the idle timer")
            this.programs += programs
            threads += Thread.currentThread().name
        }
    }

    @After
    fun tearDown() {
        NativeResampler.kernels = null
        NativeResampler.engine = PixelEngine.NATIVE
        NativeResampler.idleTrimMs = idleTrimMs
    }

    private val idleTrimMs = NativeResampler.idleTrimMs

    private fun gpuJob(engine: PixelEngine = PixelEngine.GPU) {
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        NativeResampler.resample(src, dst, Resampler.Window.of(8, 8), false, Scaling.DEFAULT, engine = engine, fallback = false)
    }

    @Suppress("DEPRECATION")
    @Test
    fun trimsOnceMemoryRunsLowOrTheUiHides() {
        val k = Trims().also { NativeResampler.kernels = it }
        val levels = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        )
        for (level in levels) assertTrue(NativeResampler.onTrimMemory(level))
        NativeResampler.awaitTrims()
        assertEquals(levels.size, k.threads.size)
    }

    @Suppress("DEPRECATION")
    @Test
    fun keepsBuffersWhileMemoryIsModerate() {
        val k = Trims().also { NativeResampler.kernels = it }
        assertFalse(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        NativeResampler.awaitTrims()
        assertEquals(0, k.threads.size)
    }

    @Test
    fun memoryTrimsRunOffTheCallingThread() {
        val k = Trims().also { NativeResampler.kernels = it }
        assertTrue(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        NativeResampler.awaitTrims()
        assertEquals(listOf("resample-idle"), k.threads)
    }

    @Test
    fun trimRunsOffTheCallingThread() {
        val k = Trims().also { NativeResampler.kernels = it }
        NativeResampler.trim()
        NativeResampler.awaitTrims()
        assertEquals(listOf("resample-idle"), k.threads)
    }

    @Test
    fun appTrimsOnceTheUiHides() {
        val k = Trims().also { NativeResampler.kernels = it }
        App().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        NativeResampler.awaitTrims()
        assertEquals(1, k.threads.size)
    }

    @Test
    fun trimsUnlessPagesUseTheGpu() {
        val k = Trims().also { NativeResampler.kernels = it }
        NativeResampler.engine = PixelEngine.NATIVE
        NativeResampler.trimUnlessGpu()
        NativeResampler.awaitTrims()
        assertEquals(1, k.threads.size)
        NativeResampler.engine = PixelEngine.GPU
        NativeResampler.trimUnlessGpu()
        NativeResampler.awaitTrims()
        assertEquals(1, k.threads.size)
    }

    @Test
    fun trimsOnceTheEngineLeavesTheGpu() {
        val k = Trims().also { NativeResampler.kernels = it }
        NativeResampler.engine = PixelEngine.NATIVE
        NativeResampler.engine = PixelEngine.GPU
        NativeResampler.engine = PixelEngine.GPU
        NativeResampler.awaitTrims()
        assertEquals(0, k.threads.size)
        NativeResampler.engine = PixelEngine.KOTLIN
        NativeResampler.awaitTrims()
        assertEquals(1, k.threads.size)
        NativeResampler.engine = PixelEngine.NATIVE
        NativeResampler.awaitTrims()
        assertEquals(1, k.threads.size)
    }

    @Test
    fun trimsOnceGpuJobsPause() {
        val k = Trims().also { NativeResampler.kernels = it }
        // A pause longer than the test: a trim queued now has not run once the timer ran what was due.
        NativeResampler.idleTrimMs = TimeUnit.HOURS.toMillis(1)
        gpuJob()
        NativeResampler.awaitTrims()
        assertEquals(0, k.threads.size)
        // The timer held, so both jobs' trims fall due before it runs one: the second job put off the first's.
        val gate = CountDownLatch(1)
        NativeResampler.kernels = Trims(gate)
        NativeResampler.trim()
        NativeResampler.kernels = k
        try {
            NativeResampler.idleTrimMs = 0
            gpuJob()
            gpuJob()
            assertEquals(0, k.threads.size)
        } finally {
            gate.countDown()
        }
        // Runs once every trim that fell due ran.
        NativeResampler.awaitTrims()
        assertEquals(listOf("resample-idle"), k.threads)
        assertEquals("compiled shaders stay", listOf(false), k.programs)
    }

    @Test
    fun memoryTrimsFreeTheShadersToo() {
        val k = Trims().also { NativeResampler.kernels = it }
        assertTrue(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        NativeResampler.engine = PixelEngine.GPU
        NativeResampler.engine = PixelEngine.NATIVE
        NativeResampler.awaitTrims()
        assertEquals(listOf(true, true), k.programs)
    }

    @Test
    fun cpuJobsLeaveNoIdleTrim() {
        val k = Trims().also { NativeResampler.kernels = it }
        // No pause: a trim the job queued would fall due before the timer's next task.
        NativeResampler.idleTrimMs = 0
        gpuJob(PixelEngine.NATIVE)
        NativeResampler.awaitTrims()
        assertEquals(0, k.threads.size)
    }

    @Test
    fun trimsNothingWithoutNativeCode() {
        assertNull(NativeResampler.kernels)
        NativeResampler.trim()
        assertTrue(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }
}
