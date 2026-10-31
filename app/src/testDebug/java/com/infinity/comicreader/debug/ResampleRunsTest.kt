package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.ResampleRuns.Cores
import com.infinity.comicreader.debug.ResampleRuns.Draw
import com.infinity.comicreader.debug.ResampleRuns.Setup
import com.infinity.comicreader.reader.PixelEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResampleRunsTest {
    @Test
    fun setupsReadAsEngineThreadsCores() {
        assertEquals(Setup(PixelEngine.NATIVE, 4, Cores.BIG), Setup.of("native/4/big", 2))
        assertEquals(Setup(PixelEngine.NATIVE, 1, Cores.LITTLE), Setup.of(" native/1/little ", 2))
        assertEquals(Setup(PixelEngine.NATIVE, 2, Cores.ANY), Setup.of("native", 2))
        assertEquals(Setup(PixelEngine.KOTLIN, 8, Cores.ANY), Setup.of("kotlin/8", 2))
        assertEquals(Setup(PixelEngine.GPU, 1, Cores.ANY), Setup.of("gpu/4/big", 2))
        assertEquals("native/4/big", Setup.of("native/4/big", 2)!!.key)
        assertEquals("gpu", Setup.of("gpu", 2)!!.key)
        for (bad in listOf("cuda", "native/x", "native/0", "native/4/mid", "native/1/prime", "kotlin/4/big")) assertNull(bad, Setup.of(bad, 2))
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
