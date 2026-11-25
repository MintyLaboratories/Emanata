package minty.emanata.ui

import android.view.OrientationEventListener.ORIENTATION_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class UprightFilterTest {

    @Test
    fun readingsNearAQuarterPointToIt() {
        assertEquals(0, UprightFilter.quarterOf(0))
        assertEquals(0, UprightFilter.quarterOf(25))
        assertEquals(0, UprightFilter.quarterOf(335))
        assertEquals(1, UprightFilter.quarterOf(90))
        assertEquals(2, UprightFilter.quarterOf(200))
        assertEquals(3, UprightFilter.quarterOf(240))
        assertEquals(0, UprightFilter.quarterOf(360))
    }

    @Test
    fun flatOrBetweenQuartersPointsNowhere() {
        assertEquals(-1, UprightFilter.quarterOf(ORIENTATION_UNKNOWN))
        assertEquals(-1, UprightFilter.quarterOf(45))
        assertEquals(-1, UprightFilter.quarterOf(130))
    }

    @Test
    fun aQuarterSettlesOnceItHolds() {
        val f = UprightFilter(settleMs = 400)
        assertFalse(f.feed(ORIENTATION_UNKNOWN, 0))
        assertFalse(f.settled(1000))
        assertTrue(f.feed(2, 1000))
        assertFalse(f.feed(10, 1200))
        assertFalse(f.settled(1399))
        assertTrue(f.settled(1400))
    }

    @Test
    fun lyingDownAgainOrTurningStartsOver() {
        val f = UprightFilter(settleMs = 400)
        f.feed(0, 0)
        f.feed(ORIENTATION_UNKNOWN, 300)
        assertFalse(f.settled(1000))
        assertTrue(f.feed(90, 1000))
        assertTrue(f.feed(180, 1300))
        assertFalse(f.settled(1600))
        assertTrue(f.settled(1700))
        f.reset()
        assertFalse(f.settled(5000))
    }

    /** Gravity on a device turned [degrees] clockwise from upright and tilted [back] degrees face up from upright. */
    private fun reading(degrees: Int, back: Int): Int {
        val g = 9.81
        val t = Math.toRadians(back.toDouble())
        val r = Math.toRadians(degrees.toDouble())
        val up = g * cos(t)
        return UprightFilter.degreesOf((-up * sin(r)).toFloat(), (up * cos(r)).toFloat(), (g * sin(t)).toFloat())
    }

    @Test
    fun gravityTellsTheWayUpClockwise() {
        assertEquals(0, reading(0, 0))
        assertEquals(90, reading(90, 0))
        assertEquals(180, reading(180, 0))
        assertEquals(270, reading(270, 0))
        assertEquals(20, reading(20, 10))
    }

    @Test
    fun recliningCountsAsFarBackAsTheSystemTurns() {
        // Past the 63 degrees a listener of orientation events gives up at.
        assertEquals(0, reading(0, 68))
        assertEquals(UprightFilter.UNKNOWN, reading(0, 72))
        assertEquals(90, reading(90, 64))
        assertEquals(UprightFilter.UNKNOWN, reading(90, 67))
        assertEquals(UprightFilter.UNKNOWN, reading(180, 62))
        assertEquals(UprightFilter.UNKNOWN, reading(0, 90))
    }

    @Test
    fun facingDownOrMovingTellsNothing() {
        assertEquals(0, reading(0, -20))
        assertEquals(UprightFilter.UNKNOWN, reading(0, -30))
        assertEquals(UprightFilter.UNKNOWN, UprightFilter.degreesOf(0f, 15f, 0f))
        assertEquals(UprightFilter.UNKNOWN, UprightFilter.degreesOf(0f, 0f, 0f))
    }

    @Test
    fun betweenQuartersTheTiltDoesNotMatter() {
        assertEquals(-1, UprightFilter.quarterOf(reading(45, 75)))
    }

    @Test
    fun tiltLimitsOfTheDeviceApply() {
        val strict = intArrayOf(-10, 40, -10, 40, -10, 40, -10, 40)
        val g = 9.81f
        val t = Math.toRadians(50.0)
        assertEquals(UprightFilter.UNKNOWN, UprightFilter.degreesOf(0f, (g * cos(t)).toFloat(), (g * sin(t)).toFloat(), strict))
        assertEquals(0, UprightFilter.degreesOf(0f, (g * cos(t)).toFloat(), (g * sin(t)).toFloat()))
    }
}
