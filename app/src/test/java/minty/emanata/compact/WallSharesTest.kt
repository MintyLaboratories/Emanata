package minty.emanata.compact

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wall time split among tasks running together. */
class WallSharesTest {
    private var now = 0L
    private val shares = WallShares { now }

    @Test
    fun aTaskAloneIsChargedAllItsTime() {
        shares.start("a")
        now = 100
        assertEquals(100.0, shares.end("a"), 0.0)
    }

    @Test
    fun tasksTogetherShareTheTimeTheyOverlap() {
        shares.start("a")
        now = 100
        shares.start("b")
        now = 300
        // a: 100 alone + 200 / 2; b: 200 / 2 + 100 alone.
        assertEquals(200.0, shares.end("a"), 0.0)
        now = 400
        assertEquals(200.0, shares.end("b"), 0.0)
    }

    @Test
    fun chargesAddUpToTheTimeAnyRan() {
        shares.start(1)
        shares.start(2)
        shares.start(3)
        now = 90
        val a = shares.end(1)
        now = 150
        val b = shares.end(2)
        now = 160
        val c = shares.end(3)
        assertEquals(160.0, a + b + c, 1e-9)
        assertEquals(30.0, a, 1e-9)
    }

    @Test
    fun idleTimeIsChargedToNoOne() {
        shares.start("a")
        now = 10
        shares.end("a")
        now = 1000
        shares.start("b")
        now = 1010
        assertEquals(10.0, shares.end("b"), 0.0)
        assertEquals(0.0, shares.end("never"), 0.0)
    }
}
