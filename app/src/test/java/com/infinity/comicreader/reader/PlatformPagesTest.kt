package com.infinity.comicreader.reader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformPagesTest {
    private val tablet = CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024))
    private val calls = ArrayList<Long>()

    private fun use(cores: CpuCores, set: CoreSet, refuse: Boolean = false) {
        PlatformPages.device = cores
        PlatformPages.cores = set
        PlatformPages.pin = { m -> calls += m; if (refuse) 0L else 0xFFL }
    }

    @After
    fun reset() {
        PlatformPages.device = null
        PlatformPages.pin = null
        PlatformPages.cores = CoreSet.BIG
    }

    @Test
    fun decodesRunPinnedThenPutBack() {
        use(tablet, CoreSet.BIG)
        assertEquals(7, PlatformPages.placed(waited = true) { 7 })
        assertEquals(listOf(0b1111_0000L, 0xFFL), calls)
        calls.clear()
        use(tablet, CoreSet.PRIME)
        PlatformPages.placed(waited = true) { }
        assertEquals(listOf(0b1000_0000L, 0xFFL), calls)
    }

    @Test
    fun putBackEvenWhenTheDecodeThrows() {
        use(tablet, CoreSet.BIG)
        runCatching { PlatformPages.placed(waited = true) { error("bad page") } }
        assertEquals(listOf(0b1111_0000L, 0xFFL), calls)
    }

    @Test
    fun nothingToPinLeavesTheThreadAlone() {
        use(tablet, CoreSet.ANY)
        PlatformPages.placed(waited = true) { }
        // Every core large: no mask.
        use(CpuCores(intArrayOf(800, 800, 800, 800)), CoreSet.BIG)
        PlatformPages.placed(waited = true) { }
        assertTrue(calls.isEmpty())
        // Refused: nothing to put back.
        use(tablet, CoreSet.BIG, refuse = true)
        PlatformPages.placed(waited = true) { }
        assertEquals(listOf(0b1111_0000L), calls)
    }

    @Test
    fun onlyDecodesTheReaderWaitsOnArePinned() {
        use(tablet, CoreSet.BIG)
        // Outside the reader (covers, scans): left to the system.
        PlatformPages.placed { }
        Urgency.later { PlatformPages.placed { } }
        var shown = false
        Urgency.during({ shown }) { PlatformPages.placed { } }
        assertTrue(calls.isEmpty())
        shown = true
        Urgency.during({ shown }) { PlatformPages.placed { } }
        assertEquals(listOf(0b1111_0000L, 0xFFL), calls)
    }

    @Test
    fun waitedOnlyInsideAMarkThatSaysSo() {
        assertFalse(Urgency.waited())
        assertTrue(Urgency.during({ true }) { Urgency.waited() })
        assertFalse(Urgency.later { Urgency.waited() })
        // Unmarked work still counts as waited on for resampling.
        assertTrue(Urgency.current()())
    }

    @Test
    fun largeCoresByDefault() {
        assertEquals(CoreSet.BIG, PlatformPages.cores)
        assertEquals(0b1111_0000L, CoreSet.BIG.mask(tablet))
        assertEquals(0L, CoreSet.ANY.mask(tablet))
        assertEquals(CoreSet.PRIME, CoreSet.of("prime"))
    }
}
