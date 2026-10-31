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
    fun setupsReadAsEngineThreadsCores() {
        assertEquals(Setup(Engine.OWN, 4, Cores.BIG), Setup.of("own/4/big", 2))
        assertEquals(Setup(Engine.PAGE, 8, Cores.ANY), Setup.of(" page/8 ", 2))
        assertEquals(Setup(Engine.PAGE, 2, Cores.ANY), Setup.of("page", 2))
        assertEquals(Setup(Engine.CODER, 1, Cores.ANY), Setup.of("coder", 2))
        assertEquals(Setup(Engine.PLATFORM, 1, Cores.ANY), Setup.of("platform", 2))
        assertEquals("own/4/little", Setup.of("own/4/little", 2)!!.key)
        assertEquals("factory", Setup.of("factory", 2)!!.key)
        for (bad in listOf("djxl", "page/x", "page/0", "page/17", "own/4/mid", "coder/4", "platform/1/big")) assertNull(bad, Setup.of(bad, 2))
    }

    @Test
    fun jpegXlSetupsTakeOnlyJpegXl() {
        assertTrue(Setup.of("own", 4)!!.takes(jxl = true))
        assertFalse(Setup.of("coder", 4)!!.takes(jxl = false))
        assertTrue(Setup.of("platform", 4)!!.takes(jxl = false))
        assertFalse(Setup.of("factory", 4)!!.takes(jxl = true))
    }
}
