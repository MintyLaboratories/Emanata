package com.infinity.comicreader.reader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformPagesTest {
    private val tablet = CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024))
    private val calls = ArrayList<Long>()

    /** What a pinned decode ends with: every core, not the 0xFF the fake pin says the thread had. */
    private val every = -1L

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
        PlatformPages.readAhead = true
        PlatformPages.jpegPinned = true
    }

    @Test
    fun decodesRunPinnedThenPutBack() {
        use(tablet, CoreSet.BIG)
        assertEquals(7, PlatformPages.placed(pinned = true) { 7 })
        assertEquals(listOf(0b1111_0000L, every), calls)
        calls.clear()
        use(tablet, CoreSet.PRIME)
        PlatformPages.placed(pinned = true) { }
        assertEquals(listOf(0b1000_0000L, every), calls)
    }

    @Test
    fun putBackEvenWhenTheDecodeThrows() {
        use(tablet, CoreSet.BIG)
        runCatching { PlatformPages.placed(pinned = true) { error("bad page") } }
        assertEquals(listOf(0b1111_0000L, every), calls)
    }

    @Test
    fun nothingToPinLeavesTheThreadAlone() {
        use(tablet, CoreSet.ANY)
        PlatformPages.placed(pinned = true) { }
        // Every core large: no mask.
        use(CpuCores(intArrayOf(800, 800, 800, 800)), CoreSet.BIG)
        PlatformPages.placed(pinned = true) { }
        assertTrue(calls.isEmpty())
        // Refused: nothing to put back.
        use(tablet, CoreSet.BIG, refuse = true)
        PlatformPages.placed(pinned = true) { }
        assertEquals(listOf(0b1111_0000L), calls)
    }

    @Test
    fun theReadersPagesArePinnedShownOrReadAhead() {
        use(tablet, CoreSet.BIG)
        // Outside the reader (covers, scans) and previews: left to the system.
        PlatformPages.placed { }
        Urgency.later { PlatformPages.placed { } }
        assertTrue(calls.isEmpty())
        Urgency.during({ false }) { PlatformPages.placed { } }
        assertEquals(listOf(0b1111_0000L, every), calls)
    }

    @Test
    fun jpegPagesStayWithTheSystemWhereCalibrationSaysSo() {
        use(tablet, CoreSet.BIG)
        PlatformPages.jpegPinned = false
        Urgency.during({ true }) {
            PlatformPages.placed(PlatformPages.wanted(jpeg = true)) { }
            assertTrue(calls.isEmpty())
            // Other formats still pinned.
            PlatformPages.placed(PlatformPages.wanted(jpeg = false)) { }
        }
        assertEquals(listOf(0b1111_0000L, every), calls)
        calls.clear()
        PlatformPages.jpegPinned = true
        Urgency.during({ true }) { PlatformPages.placed(PlatformPages.wanted(jpeg = true)) { } }
        assertEquals(listOf(0b1111_0000L, every), calls)
    }

    @Test
    fun withoutReadAheadOnlyPagesWaitedOnArePinned() {
        use(tablet, CoreSet.BIG)
        PlatformPages.readAhead = false
        var shown = false
        Urgency.during({ shown }) { PlatformPages.placed { } }
        assertTrue(calls.isEmpty())
        shown = true
        Urgency.during({ shown }) { PlatformPages.placed { } }
        assertEquals(listOf(0b1111_0000L, every), calls)
    }

    @Test
    fun waitedAndPageOnlyInsideAMarkThatSaysSo() {
        assertFalse(Urgency.waited())
        assertTrue(Urgency.during({ true }) { Urgency.waited() })
        assertFalse(Urgency.later { Urgency.waited() })
        assertFalse(Urgency.page())
        assertTrue(Urgency.during({ false }) { Urgency.page() })
        assertFalse(Urgency.later { Urgency.page() })
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
