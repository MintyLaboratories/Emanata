package com.infinity.comicreader.reader

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
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

/** [NativeResampler.trim] and [NativeResampler.onTrimMemory]: which levels free the GPU path's buffers, on the calling thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResampleTrimTest {

    /** Kernels that only record the threads their trims ran on. */
    private class Trims : PixelKernels {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long = 0

        override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean = false

        override fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean = false

        override fun end(job: Long) {}

        override fun trim() {
            threads += Thread.currentThread().name
        }
    }

    @After
    fun tearDown() {
        NativeResampler.kernels = null
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
        assertEquals(levels.size, k.threads.size)
    }

    @Suppress("DEPRECATION")
    @Test
    fun keepsBuffersWhileMemoryIsModerate() {
        val k = Trims().also { NativeResampler.kernels = it }
        assertFalse(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(0, k.threads.size)
    }

    @Test
    fun trimsOnTheCallingThread() {
        val k = Trims().also { NativeResampler.kernels = it }
        assertTrue(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(listOf(Thread.currentThread().name), k.threads)
    }

    @Test
    fun trimRunsOnTheCallingThread() {
        val k = Trims().also { NativeResampler.kernels = it }
        NativeResampler.trim()
        assertEquals(listOf(Thread.currentThread().name), k.threads)
    }

    @Test
    fun trimsNothingWithoutNativeCode() {
        assertNull(NativeResampler.kernels)
        NativeResampler.trim()
        assertTrue(NativeResampler.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }
}
