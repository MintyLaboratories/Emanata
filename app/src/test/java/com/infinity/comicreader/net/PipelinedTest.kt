package com.infinity.comicreader.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PipelinedTest {

    /**
     * A server behind a link of [bytesPerMs], [oneWayMs] each way, taking [serverMs] to
     * start each answer, on a clock of its own: requests reach it after [oneWayMs], answers
     * go out one after another as the link frees, and arrive [oneWayMs] after the last byte
     * left. Serves [size] bytes of `i & 0xFF`. Awaiting an answer moves the clock to its arrival.
     */
    private class Link(
        private val size: Long,
        private val bytesPerMs: Double,
        private val oneWayMs: Double,
        private val serverMs: Double = 0.0,
        private val credits: Int = Int.MAX_VALUE
    ) : Pipelined.Source {
        var clock = 0.0
        private var linkFree = 0.0
        var underWay = 0
        var mostUnderWay = 0
        val asked = ArrayList<Pair<Long, Int>>()
        var failAt: Long = -1

        override fun start(position: Long, length: Int): Pipelined.Pending {
            asked += position to length
            underWay++
            mostUnderWay = maxOf(mostUnderWay, underWay)
            val n = (size - position).coerceIn(0, length.toLong()).toInt()
            val begin = maxOf(clock + oneWayMs + serverMs, linkFree)
            linkFree = begin + n / bytesPerMs
            val arrives = linkFree + oneWayMs
            val fails = position == failAt
            return Pipelined.Pending {
                underWay--
                clock = maxOf(clock, arrives)
                if (fails) throw IOException("Dropped")
                ByteArray(n) { ((position + it) and 0xFF).toByte() }
            }
        }

        override fun room(length: Int): Int = credits - underWay
    }

    private fun expected(at: Long, count: Int) = ByteArray(count) { ((at + it) and 0xFF).toByte() }

    @Test
    fun fillsInOrderInPiecesUpToTheDepth() {
        val link = Link(10_000, 1000.0, 1.0)
        val buffer = ByteArray(5000)
        val n = Pipelined.fill(link, 1234, buffer, 100, 4500, piece = 1000, depth = 3, timeoutMs = 1000)
        assertEquals(4500, n)
        assertArrayEquals(expected(1234, 4500), buffer.copyOfRange(100, 4600))
        assertEquals(listOf(1234L to 1000, 2234L to 1000, 3234L to 1000, 4234L to 1000, 5234L to 500), link.asked)
        assertEquals(3, link.mostUnderWay)
    }

    @Test
    fun stopsShortAtTheEndAndFindsNothingPastIt() {
        val link = Link(2500, 1000.0, 1.0)
        val buffer = ByteArray(4000)
        assertEquals(1500, Pipelined.fill(link, 1000, buffer, 0, 4000, piece = 1000, depth = 4, timeoutMs = 1000))
        assertArrayEquals(expected(1000, 1500), buffer.copyOf(1500))
        assertEquals(-1, Pipelined.fill(link, 2500, buffer, 0, 100, piece = 1000, depth = 4, timeoutMs = 1000))
        assertEquals(0, Pipelined.fill(link, 0, buffer, 0, 0, piece = 1000, depth = 4, timeoutMs = 1000))
    }

    @Test
    fun startsOnlyWhatTheSourceHasRoomFor() {
        val link = Link(100_000, 1000.0, 1.0, credits = 2)
        val buffer = ByteArray(10_000)
        assertEquals(10_000, Pipelined.fill(link, 0, buffer, 0, 10_000, piece = 1000, depth = 8, timeoutMs = 1000))
        assertEquals(2, link.mostUnderWay)
        assertArrayEquals(expected(0, 10_000), buffer)
    }

    @Test
    fun oneRequestGoesEvenWithNoRoom() {
        val link = Link(100_000, 1000.0, 1.0, credits = 0)
        val buffer = ByteArray(3000)
        assertEquals(3000, Pipelined.fill(link, 0, buffer, 0, 3000, piece = 1000, depth = 8, timeoutMs = 1000))
        assertEquals(1, link.mostUnderWay)
    }

    @Test
    fun aFailureAfterTheFirstPieceKeepsWhatCame() {
        val link = Link(100_000, 1000.0, 1.0).apply { failAt = 2000 }
        val buffer = ByteArray(5000)
        assertEquals(2000, Pipelined.fill(link, 0, buffer, 0, 5000, piece = 1000, depth = 4, timeoutMs = 1000))
        assertArrayEquals(expected(0, 2000), buffer.copyOf(2000))
    }

    @Test
    fun aFailureOfTheFirstPieceIsThrown() {
        val link = Link(100_000, 1000.0, 1.0).apply { failAt = 0 }
        try {
            Pipelined.fill(link, 0, ByteArray(5000), 0, 5000, piece = 1000, depth = 4, timeoutMs = 1000)
            fail("Should throw")
        } catch (e: IOException) {
            assertEquals("Dropped", e.message)
        }
    }

    @Test
    fun eachAnswerIsHeard() {
        val link = Link(100_000, 1000.0, 1.0)
        var heard = 0
        Pipelined.fill(link, 0, ByteArray(4500), 0, 4500, piece = 1000, depth = 2, timeoutMs = 1000) { heard++ }
        assertEquals(5, heard)
    }

    /** The share of [link]'s bandwidth a read of [total] bytes in pieces of [piece], [depth] deep, gets. */
    private fun efficiency(link: Link, bytesPerMs: Double, total: Int, piece: Int, depth: Int): Double {
        val buffer = ByteArray(total)
        assertEquals(total, Pipelined.fill(link, 0, buffer, 0, total, piece, depth, timeoutMs = 1000))
        return total / bytesPerMs / link.clock
    }

    @Test
    fun severalInFlightKeepAFastLinkBusy() {
        // 100 MB/s, 1 ms round trip, 2 ms for the server to start each answer.
        val rate = 100.0 * MB / 1000
        val one = efficiency(Link(Long.MAX_VALUE, rate, 0.5, 2.0), rate, 64 * MB, MB, 1)
        val eight = efficiency(Link(Long.MAX_VALUE, rate, 0.5, 2.0), rate, 64 * MB, MB, 8)
        assertTrue("One at a time: $one", one < 0.8)
        assertTrue("Eight at a time: $eight", eight > 0.97)
    }

    @Test
    fun severalInFlightKeepASlowLinkBusy() {
        // 20 MB/s, 10 ms round trip.
        val rate = 20.0 * MB / 1000
        val one = efficiency(Link(Long.MAX_VALUE, rate, 5.0), rate, 32 * MB, MB, 1)
        val four = efficiency(Link(Long.MAX_VALUE, rate, 5.0), rate, 32 * MB, MB, 4)
        assertTrue("One at a time: $one", one < 0.9)
        assertTrue("Four at a time: $four", four > 0.98)
    }

    private companion object {
        const val MB = 1 shl 20
    }
}
