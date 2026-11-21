package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.ResampleRuns.Cores
import com.infinity.comicreader.debug.ResampleRuns.GpuTrim
import com.infinity.comicreader.debug.ResampleRuns.Setup
import com.infinity.comicreader.reader.CpuCores
import com.infinity.comicreader.reader.PixelEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResampleRunsTest {
    @Test
    fun timingSizeIsAskedTheScreensOrTheSameEverywhere() {
        assertEquals(1200 to 1800, ResampleRuns.size("1200x1800", 1848, 2960))
        assertEquals(1848 to 2772, ResampleRuns.size(" 1848X2772 ", 1200, 2000))
        // Absent: the same on every screen.
        assertEquals(1848 to 2772, ResampleRuns.size(null, 1200, 2000))
        assertEquals(1848 to 2772, ResampleRuns.size("", 2000, 1200))
        assertEquals(1200 to 1800, ResampleRuns.size("screen", 2000, 1200))
        assertEquals(1848 to 2772, ResampleRuns.size(" Screen ", 1848, 2960))
        for (bad in listOf("1848", "x2772", "1848x", "0x100", "-5x100", "axb", "1x2x3", "99999x10")) assertNull(bad, ResampleRuns.size(bad, 1848, 2960))
    }

    @Test
    fun sustainSourceIsAskedOrTheStandardPage() {
        assertEquals(4000 to 6000, ResampleRuns.source("4000x6000"))
        assertEquals(2000 to 3000, ResampleRuns.source(null))
        assertEquals(2000 to 3000, ResampleRuns.source(" "))
        for (bad in listOf("screen", "4000", "0x6000", "99999x10")) assertNull(bad, ResampleRuns.source(bad))
    }

    @Test
    fun setupsReadAsEngineThreadsCores() {
        assertEquals(Setup(PixelEngine.NATIVE, 4, Cores.BIG), Setup.of("native/4/big", 2))
        assertEquals(Setup(PixelEngine.NATIVE, 1, Cores.LITTLE), Setup.of(" native/1/little ", 2))
        assertEquals(Setup(PixelEngine.NATIVE, 2, Cores.ANY), Setup.of("native", 2))
        assertEquals(Setup(PixelEngine.KOTLIN, 8, Cores.ANY), Setup.of("kotlin/8", 2))
        assertEquals(Setup(PixelEngine.GPU, 1, Cores.BIG), Setup.of("gpu/4/big", 2))
        assertEquals(Setup(PixelEngine.GPU, 1, Cores.LITTLE), Setup.of("gpu/little", 2))
        // A thread count alone, as earlier steps' commands had it: the GPU left to the system.
        assertEquals(Setup(PixelEngine.GPU, 1, Cores.ANY), Setup.of("gpu/1", 2))
        assertEquals(Setup(PixelEngine.GPU, 1, Cores.ANY), Setup.of("gpu/4", 2))
        assertEquals("native/4/big", Setup.of("native/4/big", 2)!!.key)
        assertEquals("gpu", Setup.of("gpu", 2)!!.key)
        assertEquals("gpu", Setup.of("gpu/1/any", 2)!!.key)
        assertEquals("gpu/big", Setup.of("gpu/1/big", 2)!!.key)
        assertEquals(Setup(PixelEngine.NATIVE, 2, Cores.PRIME), Setup.of("native/2/prime", 4))
        assertEquals("native/1/prime", Setup.of("native/1/prime", 4)!!.key)
        for (bad in listOf("cuda", "native/x", "native/0", "native/4/mid", "kotlin/4/big", "kotlin/8/prime", "gpu/mid", "gpu/big/any")) assertNull(bad, Setup.of(bad, 2))
    }

    @Test
    fun coresMapToTheirMasks() {
        // Four little cores, two large, two fastest.
        val c = CpuCores(intArrayOf(158, 158, 158, 158, 763, 763, 1024, 1024))
        assertEquals(0b1111_0000L, ResampleRuns.mask(Cores.BIG, c))
        assertEquals(0b0000_1111L, ResampleRuns.mask(Cores.LITTLE, c))
        assertEquals(0b1100_0000L, ResampleRuns.mask(Cores.PRIME, c))
        assertEquals(0L, ResampleRuns.mask(Cores.ANY, c))
    }

    @Test
    fun aRunCountsOnlyWhileItStaysCool() {
        val heat = ResampleRuns.Heat()
        assertEquals(-1, heat.peak)
        assertTrue("nothing seen", heat.cool)
        heat.see(-1)
        assertTrue("unknown", heat.cool)
        heat.see(0)
        assertTrue(heat.cool)
        // Light throttling mid-run, cool again by the end: the run does not count.
        heat.see(1)
        heat.see(0)
        assertEquals(1, heat.peak)
        assertFalse(heat.cool)
        // A run that starts warm does not count either.
        assertFalse(ResampleRuns.Heat().apply { see(2); see(0) }.cool)
    }

    @Test
    fun roundsAlternateTheOrder() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(3, 2, 1), listOf(1, 2, 3)), ResampleRuns.interleaved(listOf(1, 2, 3), 3))
    }

    @Test
    fun gpuTrimLinesReadAsWhenAndPrograms() {
        val lines = listOf(
            "--------- beginning of main",
            "11-11 10:00:00.000  100  200 I ResampleGpu: trim at close freed=41.0MB programs=2 in 0.31 ms",
            "trim now freed=0.0MB programs=0 in 0.02 ms",
            "trim after failure freed=12.5MB programs=0 in 0.10 ms",
            "job 600x800 rows=800 lock=0.01 compile=0.00",
        )
        assertEquals(
            listOf(GpuTrim("at close", 2), GpuTrim("now", 0), GpuTrim("after failure", 0)),
            ResampleRuns.gpuTrims(lines),
        )
        assertTrue(ResampleRuns.gpuTrims(emptyList()).isEmpty())
    }

    @Test
    fun aTrimAtCloseCountsOnlyAloneAndWithItsPrograms() {
        assertTrue(ResampleRuns.trimmedAtClose(listOf(GpuTrim("at close", 3))))
        // Freed at once: the job did not hold the lock, or the close path did not run.
        assertFalse(ResampleRuns.trimmedAtClose(listOf(GpuTrim("now", 3))))
        assertFalse(ResampleRuns.trimmedAtClose(listOf(GpuTrim("at close", 3), GpuTrim("now", 0))))
        // Buffers alone: the programs the trim asked for stayed.
        assertFalse(ResampleRuns.trimmedAtClose(listOf(GpuTrim("at close", 0))))
        assertFalse(ResampleRuns.trimmedAtClose(listOf(GpuTrim("at close", 1), GpuTrim("at close", 1))))
        assertFalse(ResampleRuns.trimmedAtClose(emptyList()))
    }
}
