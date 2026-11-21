package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.DecodeRuns.Engine
import com.infinity.comicreader.debug.DecodeRuns.Setup
import com.infinity.comicreader.debug.ResampleRuns.Cores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeRunsTest {
    @Test
    fun sustainMeasuresFourKPagesOnly() {
        assertTrue(DecodeRuns.sustained("synthetic-4k-jpg"))
        assertTrue(DecodeRuns.sustained("pages-a"))
        assertFalse(DecodeRuns.sustained("synthetic-2k-jxl"))
    }

    @Test
    fun setupsReadAsEngineThreadsCores() {
        assertEquals(Setup(Engine.PAGE, 4, Cores.BIG), Setup.of("page/4/big", 2))
        assertEquals(Setup(Engine.PAGE, 8, Cores.ANY), Setup.of(" page/8 ", 2))
        assertEquals(Setup(Engine.PAGE, 2, Cores.ANY), Setup.of("page", 2))
        assertEquals(Setup(Engine.CODER, 1, Cores.ANY), Setup.of("coder", 2))
        assertEquals(Setup(Engine.PLATFORM, 1, Cores.ANY), Setup.of("platform", 2))
        assertEquals("page/4/little", Setup.of("page/4/little", 2)!!.key)
        assertEquals("factory", Setup.of("factory", 2)!!.key)
        assertEquals(Setup(Engine.CODER, 1, Cores.BIG), Setup.of("coder/big", 2))
        assertEquals("coder/big", Setup.of("coder/big", 2)!!.key)
        assertEquals("coder", Setup.of("coder/any", 2)!!.key)
        assertEquals(Setup(Engine.PLATFORM, 1, Cores.PRIME), Setup.of("platform/prime", 2))
        assertEquals("factory/big", Setup.of("factory/big", 2)!!.key)
        for (bad in listOf("djxl", "page/x", "page/0", "page/17", "page/4/mid", "coder/4", "coder/1/big", "platform/1/big", "platform/mid")) assertNull(bad, Setup.of(bad, 2))
    }

    @Test
    fun aFolderIsHeldOnlyAsFarAsTheBudget() {
        assertEquals(3, DecodeRuns.fitting(listOf(10L, 10L, 10L), 30))
        assertEquals(2, DecodeRuns.fitting(listOf(10L, 10L, 11L, 1L), 30))
        // One page always, however large.
        assertEquals(1, DecodeRuns.fitting(listOf(50L, 1L), 30))
        assertEquals(0, DecodeRuns.fitting(emptyList(), 30))
    }

    @Test
    fun jpegXlChecksRunWhereTheReadersThreadsRun() {
        assertEquals(Cores.BIG, DecodeRuns.jxlCores(pinned = true))
        assertEquals(Cores.ANY, DecodeRuns.jxlCores(pinned = false))
        assertEquals("page/8/any", Setup(Engine.PAGE, 8, DecodeRuns.jxlCores(pinned = false)).key)
    }

    @Test
    fun defaultSetupsAreEachTimedOnce() {
        // The reader's 8 threads anywhere are the 8-thread setup: once, with the large cores' 2 to compare.
        assertEquals(
            listOf("coder", "page/8/any", "page/2/big", "platform"),
            DecodeRuns.defaultSetups(threads = 8, pinned = false, bigThreads = 2).map { it.key }
        )
        assertEquals(
            listOf("coder", "page/4/big", "page/8/any", "platform"),
            DecodeRuns.defaultSetups(threads = 4, pinned = true, bigThreads = 4).map { it.key }
        )
        // No large cores: nothing pinned.
        assertEquals(listOf("coder", "page/8/any", "platform"), DecodeRuns.defaultSetups(threads = 8, pinned = false, bigThreads = 0).map { it.key })
    }

    @Test
    fun jpegXlSetupsTakeOnlyJpegXl() {
        assertTrue(Setup.of("page", 4)!!.takes(jxl = true))
        assertFalse(Setup.of("coder", 4)!!.takes(jxl = false))
        assertTrue(Setup.of("platform", 4)!!.takes(jxl = false))
        assertFalse(Setup.of("factory", 4)!!.takes(jxl = true))
    }
}
