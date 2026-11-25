package minty.emanata.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LanesTest {

    /** A lane made for an answer time. */
    private class Lane(val answerMs: Long)

    /** [Lanes] on a clock and queues the test runs by hand. */
    private class Rig(var failing: Boolean = false, var thrifty: Boolean = false) {
        var now = 1_000L
        val made = ArrayList<Lane>()
        val shut = ArrayList<Lane>()
        val work = ArrayDeque<Runnable>()
        val checks = ArrayList<Pair<Long, () -> Unit>>()
        var answerMs = 30_000L

        val lanes = Lanes(
            make = {
                if (failing) throw java.io.IOException("Refused")
                Lane(answerMs).also { made += it }
            },
            shut = { shut += it },
            answerMsOf = { it.answerMs },
            run = { work.addLast(it) },
            schedule = { delayMs, check -> checks += (now + delayMs) to check },
            clock = { now },
            idleMs = 60_000,
            retryMs = 600_000,
            thrifty = { thrifty }
        )

        fun runWork() {
            while (work.isNotEmpty()) work.removeFirst().run()
        }

        /** Moves the clock to [to], running the checks due by then. */
        fun advance(to: Long) {
            now = to
            while (true) {
                val due = checks.filter { it.first <= now }.minByOrNull { it.first } ?: return
                checks.remove(due)
                due.second()
            }
        }
    }

    @Test
    fun madeInTheBackgroundOnceWhileWanted() {
        val rig = Rig()
        assertNull(rig.lanes.now(rig.answerMs))
        rig.lanes.want()
        rig.lanes.want()
        assertEquals(1, rig.work.size)
        rig.runWork()
        assertSame(rig.made.single(), rig.lanes.now(rig.answerMs))
        rig.lanes.want()
        assertTrue(rig.work.isEmpty())
    }

    @Test
    fun aLaneThatFailedToConnectIsNotMadeAgainForAWhile() {
        val rig = Rig(failing = true)
        rig.lanes.want()
        rig.runWork()
        assertNull(rig.lanes.now(rig.answerMs))
        rig.failing = false
        rig.now += 599_000
        rig.lanes.want()
        assertTrue(rig.work.isEmpty())
        rig.now += 1_000
        rig.lanes.want()
        rig.runWork()
        assertTrue(rig.lanes.isOpen)
    }

    @Test
    fun aLaneDroppedAfterAFailedReadIsNotMadeAgainForAWhile() {
        val rig = Rig()
        rig.lanes.want()
        rig.runWork()
        val l = rig.lanes.now(rig.answerMs)!!
        rig.lanes.drop(l, failed = true)
        assertEquals(listOf(l), rig.shut)
        rig.lanes.want()
        assertTrue(rig.work.isEmpty())
        rig.now += 600_000
        rig.lanes.want()
        assertEquals(1, rig.work.size)
    }

    @Test
    fun aLaneClosedUnfailedIsMadeAgainWhenNextWanted() {
        val rig = Rig()
        rig.lanes.want()
        rig.runWork()
        rig.lanes.drop(rig.made.single(), failed = false)
        rig.lanes.want()
        rig.runWork()
        assertEquals(2, rig.made.size)
    }

    @Test
    fun anUnusedLaneClosesAMinuteAfterItsLastUse() {
        val rig = Rig()
        rig.lanes.want()
        rig.runWork()
        val l = rig.made.single()
        // Used 40 s in: the check due at 60 s waits for another 40 s.
        rig.advance(rig.now + 40_000)
        assertSame(l, rig.lanes.now(rig.answerMs))
        rig.advance(rig.now + 59_000)
        assertTrue(rig.lanes.isOpen)
        assertTrue(rig.shut.isEmpty())
        rig.advance(rig.now + 1_000)
        assertFalse(rig.lanes.isOpen)
        assertEquals(listOf(l), rig.shut)
        assertTrue(rig.checks.isEmpty())
    }

    @Test
    fun aLaneMadeForAnotherAnswerTimeIsShutWhenNextWanted() {
        val rig = Rig()
        rig.lanes.want()
        rig.runWork()
        val old = rig.made.single()
        assertNull(rig.lanes.now(60_000))
        assertEquals(listOf(old), rig.shut)
        rig.answerMs = 60_000
        rig.lanes.want()
        rig.runWork()
        assertSame(rig.made.last(), rig.lanes.now(60_000))
    }

    @Test
    fun aLaneGoesWithTheConnectionAndOneBeingMadeIsShutAsItArrives() {
        val rig = Rig()
        rig.lanes.want()
        rig.runWork()
        assertSame(rig.made.single(), rig.lanes.take())
        assertFalse(rig.lanes.isOpen)

        rig.lanes.want()
        // The connection goes while the lane connects.
        assertNull(rig.lanes.take())
        rig.runWork()
        assertFalse(rig.lanes.isOpen)
        assertEquals(listOf(rig.made[1]), rig.shut)
    }

    @Test
    fun noLaneWhileBatterySaverHoldsBackReadAhead() {
        val rig = Rig(thrifty = true)
        rig.lanes.want()
        assertTrue(rig.work.isEmpty())
        rig.thrifty = false
        rig.lanes.want()
        rig.runWork()
        rig.thrifty = true
        assertNull(rig.lanes.now(rig.answerMs))
        // Unused meanwhile, it closes as it idles.
        rig.advance(rig.now + 60_000)
        assertFalse(rig.lanes.isOpen)
    }
}
