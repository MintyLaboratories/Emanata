package com.infinity.comicreader.reader

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PixelKernels]' defaults for GPU bands and trimming, and [LibPixels] where native code cannot load. */
class PixelKernelsTest {

    /** Kernels that only record bands, each succeeding when [ok]. */
    private class Bands(val ok: Boolean = true) : PixelKernels {
        val bands = ArrayList<List<Long>>()

        override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long = 1

        override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean = true

        override fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean {
            bands += listOf(job, worker.toLong(), y0.toLong(), y1.toLong(), cores)
            return ok
        }

        override fun end(job: Long) {}
    }

    @Test
    fun gpuBandRunsOneBandAsWorkerZero() {
        val k = Bands()
        assertEquals(PixelKernels.BAND_DONE, k.gpuBand(7, 10, 20, 0xF0, null))
        assertEquals(listOf(listOf(7L, 0L, 10L, 20L, 0xF0L)), k.bands)
    }

    @Test
    fun gpuBandAsksStopFirst() {
        val k = Bands()
        var asked = 0
        assertEquals(PixelKernels.BAND_CANCELED, k.gpuBand(1, 0, 5, 0) { asked++; true })
        assertEquals(1, asked)
        assertTrue(k.bands.isEmpty())
        assertEquals(PixelKernels.BAND_DONE, k.gpuBand(1, 0, 5, 0) { asked++; false })
        assertEquals(2, asked)
        assertEquals(1, k.bands.size)
    }

    @Test
    fun gpuBandReportsFailedBand() {
        assertEquals(PixelKernels.BAND_FAILED, Bands(ok = false).gpuBand(1, 0, 5, 0, null))
    }

    @Test
    fun gpuBandPassesOnWhatStopThrows() {
        val k = Bands()
        val thrown = IllegalStateException("stop")
        val caught = runCatching { k.gpuBand(1, 0, 5, 0) { throw thrown } }.exceptionOrNull()
        assertSame(thrown, caught)
        assertTrue(k.bands.isEmpty())
    }

    /** Without the guard on [LibPixels.loaded], each call would throw [UnsatisfiedLinkError]. */
    @Test
    fun trimAndTraceSkipUnloadedLibrary() {
        assertFalse(LibPixels.loaded)
        LibPixels.trim()
        LibPixels.trace(true)
        LibPixels.trace(false)
    }
}
