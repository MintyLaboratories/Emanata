package minty.emanata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekHoldTest {

    @Test
    fun aStillTouchHolds() {
        val h = SeekHold(slop = 10f)
        h.down(100f, 20f)
        assertTrue(h.move(105f, 24f))
        assertTrue(h.held())
        // Once: the strip opens a single time per touch.
        assertFalse(h.held())
    }

    @Test
    fun movingPastTheSlopNeverHoldsAgain() {
        val h = SeekHold(slop = 10f)
        h.down(100f, 20f)
        assertFalse(h.move(120f, 20f))
        assertFalse(h.move(100f, 20f))
        assertFalse(h.held())
    }

    @Test
    fun liftingBeforeTheTimeIsUpDoesNotHold() {
        val h = SeekHold(slop = 10f)
        h.down(0f, 0f)
        h.up()
        assertFalse(h.held())
    }

    @Test
    fun aTouchNearTheThumbsCenterIsOnIt() {
        // A 1000 px track 20 px in, 100 steps: step 40 is centered at 420 px.
        fun on(x: Float, rtl: Boolean = false) =
            SeekHold.onThumb(x, progress = 40, max = 100, start = 20, track = 1000, thumbHalf = 16, rtl = rtl)
        assertTrue(on(420f))
        assertTrue(on(436f))
        assertTrue(on(404f))
        assertFalse(on(437f))
        assertFalse(on(120f))
        // Right to left, step 40 sits 600 px from the start edge.
        assertTrue(on(620f, rtl = true))
        assertFalse(on(420f, rtl = true))
        // A one-item book has nowhere else to go.
        assertTrue(SeekHold.onThumb(900f, progress = 0, max = 0, start = 20, track = 1000, thumbHalf = 16, rtl = false))
    }

    @Test
    fun aNewTouchStartsOver() {
        val h = SeekHold(slop = 10f)
        h.down(0f, 0f)
        h.move(50f, 0f)
        h.down(50f, 0f)
        assertTrue(h.held())
    }
}
