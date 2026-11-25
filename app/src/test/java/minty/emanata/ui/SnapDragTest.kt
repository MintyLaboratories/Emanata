package minty.emanata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapDragTest {

    /** 10 px per unit, a 150 px hold at 0. */
    private fun drag(start: Int) = SnapDrag(start, pxPerUnit = 10f, hold = 150f)

    /** The value after each move of [moves]. */
    private fun SnapDrag.trace(vararg moves: Float) = moves.map { moveBy(it) }

    @Test
    fun followsTheFingerAwayFromZero() {
        assertEquals(listOf(3, 10, 5), drag(0).trace(30f, 70f, -50f))
        assertEquals(listOf(-3, -10), drag(0).trace(-30f, -70f))
        assertEquals(listOf(25, 15), drag(20).trace(50f, -100f))
    }

    @Test
    fun startingAtZeroNeverWaitsInTheHold() {
        assertEquals(1, drag(0).moveBy(10f))
        assertEquals(-1, drag(0).moveBy(-10f))
        assertEquals(0, drag(0).moveBy(0f))
    }

    @Test
    fun crossingZeroDownwardHoldsThenGoesOn() {
        val d = drag(10)
        // 100 px reach 0, the next 150 px hold it, then the value goes negative.
        assertEquals(listOf(0, 0, 0, 0, -1, -5), d.trace(-100f, -50f, -50f, -50f, -10f, -40f))
    }

    @Test
    fun crossingZeroUpwardHoldsThenGoesOn() {
        val d = drag(-10)
        assertEquals(listOf(0, 0, 0, 2), d.trace(100f, 100f, 50f, 20f))
    }

    @Test
    fun turningBackInsideTheHoldLeavesOnTheSideItCameFrom() {
        val d = drag(5)
        assertEquals(listOf(0, 0, 1), d.trace(-50f, -60f, 70f))
    }

    @Test
    fun valuesStopAtTheEndsAndTurnBackAtOnce() {
        val d = drag(90)
        assertEquals(listOf(100, 100, 99), d.trace(300f, 500f, -10f))
        val e = drag(-90)
        assertEquals(listOf(-100, -99), e.trace(-400f, 10f))
    }

    @Test
    fun outOfRangeStartsAreClamped() {
        assertEquals(99, drag(250).moveBy(-10f))
        assertEquals(-99, drag(-250).moveBy(10f))
    }
}
