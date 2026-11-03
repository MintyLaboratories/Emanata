package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.ResampleRuns.Cores
import com.infinity.comicreader.debug.ResampleRuns.Draw
import com.infinity.comicreader.debug.ResampleRuns.Setup
import com.infinity.comicreader.reader.CpuCores
import com.infinity.comicreader.reader.PixelEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun setupsReadAsEngineThreadsCores() {
        assertEquals(Setup(PixelEngine.NATIVE, 4, Cores.BIG), Setup.of("native/4/big", 2))
        assertEquals(Setup(PixelEngine.NATIVE, 1, Cores.LITTLE), Setup.of(" native/1/little ", 2))
        assertEquals(Setup(PixelEngine.NATIVE, 2, Cores.ANY), Setup.of("native", 2))
        assertEquals(Setup(PixelEngine.KOTLIN, 8, Cores.ANY), Setup.of("kotlin/8", 2))
        assertEquals(Setup(PixelEngine.GPU, 1, Cores.ANY), Setup.of("gpu/4/big", 2))
        assertEquals("native/4/big", Setup.of("native/4/big", 2)!!.key)
        assertEquals("gpu", Setup.of("gpu", 2)!!.key)
        assertEquals(Setup(PixelEngine.NATIVE, 2, Cores.PRIME), Setup.of("native/2/prime", 4))
        assertEquals("native/1/prime", Setup.of("native/1/prime", 4)!!.key)
        for (bad in listOf("cuda", "native/x", "native/0", "native/4/mid", "kotlin/4/big", "kotlin/8/prime")) assertNull(bad, Setup.of(bad, 2))
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
    fun roundsAlternateTheOrder() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(3, 2, 1), listOf(1, 2, 3)), ResampleRuns.interleaved(listOf(1, 2, 3), 3))
    }

    @Test
    fun energyIsTheSignedDrainAboveIdle() {
        // Idle drains 400 mA, busy 900 mA: 500 mA more at 4 V for 10 s over 20 pages = 1000 mJ each.
        val mj = ResampleRuns.millijoulesPerPage(Draw(listOf(-390.0, -410.0)), Draw(listOf(-850.0, -950.0)), 4.0, 10.0, 20)
        assertEquals(1000.0, mj!!, 1e-9)
    }

    @Test
    fun aChargerRefusesTheEstimate() {
        val busy = Draw(listOf(-900.0, -900.0))
        // Charging while idle: the sign flips between the two.
        assertNull(ResampleRuns.millijoulesPerPage(Draw(listOf(200.0, 150.0)), busy, 4.0, 10.0, 20))
        // A single charging sample is enough.
        assertNull(ResampleRuns.millijoulesPerPage(Draw(listOf(-400.0, 30.0)), busy, 4.0, 10.0, 20))
        assertNull(ResampleRuns.millijoulesPerPage(Draw(listOf(-400.0)), Draw(listOf(-900.0, 0.0)), 4.0, 10.0, 20))
        assertNull(ResampleRuns.millijoulesPerPage(Draw(emptyList()), busy, 4.0, 10.0, 20))
        assertNull(ResampleRuns.millijoulesPerPage(Draw(listOf(-400.0)), busy, 4.0, 10.0, 0))
        // A charger that cannot keep up: draining throughout, yet it supplies part of the work.
        assertNull(ResampleRuns.millijoulesPerPage(Draw(listOf(-400.0)), busy, 4.0, 10.0, 20, charging = true))
    }
}
