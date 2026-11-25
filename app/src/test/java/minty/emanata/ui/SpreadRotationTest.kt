package minty.emanata.ui

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadRotationTest {

    @Test
    fun aWidePageTurnsTheScreenUntilANarrowOneShows() {
        val r = SpreadRotation()
        assertNull(r.show(-1, enabled = true))
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, r.show(5, enabled = true))
        // Shown again, as after the turn lays the page out anew: nothing more to do.
        assertNull(r.show(5, enabled = true))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, r.show(-1, enabled = true))
        assertNull(r.show(-1, enabled = true))
    }

    @Test
    fun widePagesInARowStayTurned() {
        val r = SpreadRotation()
        r.show(5, enabled = true)
        assertNull(r.show(6, enabled = true))
        assertEquals(6, r.held)
    }

    @Test
    fun theReadersOwnOrientationComesBack() {
        val r = SpreadRotation()
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, r.choose(SCREEN_ORIENTATION_PORTRAIT))
        r.show(5, enabled = true)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, r.show(-1, enabled = true))
    }

    @Test
    fun aChoiceOnAHeldPageWinsUntilAnotherPageShows() {
        val r = SpreadRotation()
        r.show(5, enabled = true)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, r.choose(SCREEN_ORIENTATION_PORTRAIT))
        assertNull(r.show(5, enabled = true))
        assertNull(r.show(-1, enabled = true))
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, r.show(5, enabled = true))
    }

    @Test
    fun offTurnsNothingAndLetsAHeldPageGo() {
        val r = SpreadRotation()
        assertNull(r.show(5, enabled = false))
        r.choose(SCREEN_ORIENTATION_LANDSCAPE)
        r.show(7, enabled = true)
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, r.show(7, enabled = false))
    }

    @Test
    fun whileHeldPagesAreLaidOutForTheOrientationThatComesBack() {
        val r = SpreadRotation()
        assertTrue(r.layoutLandscape(landscape = true))
        r.show(5, enabled = true, landscape = false)
        assertFalse(r.layoutLandscape(landscape = true))
        r.show(-1, enabled = true, landscape = true)
        assertTrue(r.layoutLandscape(landscape = true))
    }

    @Test
    fun aHeldTurnFromLandscapeLaysOutForLandscape() {
        val r = SpreadRotation()
        r.show(5, enabled = true, landscape = true)
        assertTrue(r.layoutLandscape(landscape = true))
    }

    @Test
    fun theReadersLockDecidesTheLayoutWhileHeld() {
        val r = SpreadRotation()
        r.choose(SCREEN_ORIENTATION_LANDSCAPE)
        r.show(5, enabled = true, landscape = false)
        assertTrue(r.layoutLandscape(landscape = true))
        r.choose(SCREEN_ORIENTATION_PORTRAIT)
        r.show(-1, enabled = true)
        r.show(6, enabled = true, landscape = true)
        assertFalse(r.layoutLandscape(landscape = true))
    }

    @Test
    fun aRestoredCopyKeepsTheReadersOrientationAndTheHold() {
        val r = SpreadRotation()
        r.choose(SCREEN_ORIENTATION_PORTRAIT)
        r.show(5, enabled = true, landscape = false)
        val copy = SpreadRotation()
        copy.restore(r.save())
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, copy.user)
        assertEquals(5, copy.held)
        assertFalse(copy.layoutLandscape(landscape = true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, copy.show(-1, enabled = true))
    }

    @Test
    fun restoringNothingChangesNothing() {
        val r = SpreadRotation()
        r.restore(null)
        r.restore(intArrayOf(1))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, r.user)
        assertEquals(-1, r.held)
    }

    @Test
    fun lyingFlatTheOrientationTurnedFromStaysUntilTheSensorSettles() {
        val r = SpreadRotation()
        r.show(5, enabled = true, landscape = false, screen = SCREEN_ORIENTATION_PORTRAIT)
        assertTrue(r.watching)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, r.show(-1, enabled = true, landscape = true, screen = SCREEN_ORIENTATION_LANDSCAPE, unsure = true))
        assertTrue(r.keeping)
        assertTrue(r.watching)
        // Further narrow pages keep it as it is.
        assertNull(r.show(-1, enabled = true, screen = SCREEN_ORIENTATION_PORTRAIT, unsure = true))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, r.settled())
        assertFalse(r.keeping)
        assertFalse(r.watching)
        assertNull(r.settled())
    }

    @Test
    fun anUpsideDownStartComesBackUpsideDown() {
        val r = SpreadRotation()
        r.show(5, enabled = true, screen = SCREEN_ORIENTATION_REVERSE_PORTRAIT)
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, r.show(-1, enabled = true, unsure = true))
    }

    @Test
    fun aSettledSensorTakesOverAtOnce() {
        val r = SpreadRotation()
        r.show(5, enabled = true, screen = SCREEN_ORIENTATION_PORTRAIT)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, r.show(-1, enabled = true, unsure = false))
        assertFalse(r.keeping)
        assertNull(r.settled())
    }

    @Test
    fun anUnknownStartHandsBackToTheSensor() {
        val r = SpreadRotation()
        r.show(5, enabled = true)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, r.show(-1, enabled = true, unsure = true))
        assertFalse(r.keeping)
    }

    @Test
    fun theReadersLockComesBackWhateverTheSensor() {
        val r = SpreadRotation()
        r.choose(SCREEN_ORIENTATION_LANDSCAPE)
        r.show(5, enabled = true, screen = SCREEN_ORIENTATION_LANDSCAPE)
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, r.show(-1, enabled = true, unsure = true))
        assertFalse(r.keeping)
    }

    @Test
    fun aChoiceWhileKeptWins() {
        val r = SpreadRotation()
        r.show(5, enabled = true, screen = SCREEN_ORIENTATION_PORTRAIT)
        r.show(-1, enabled = true, unsure = true)
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, r.choose(SCREEN_ORIENTATION_LANDSCAPE))
        assertFalse(r.keeping)
        assertNull(r.settled())
    }

    @Test
    fun aWidePageWhileKeptTurnsAgainFromTheKeptOrientation() {
        val r = SpreadRotation()
        r.show(5, enabled = true, screen = SCREEN_ORIENTATION_PORTRAIT)
        r.show(-1, enabled = true, landscape = true, unsure = true)
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, r.show(7, enabled = true, screen = SCREEN_ORIENTATION_PORTRAIT))
        assertFalse(r.keeping)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, r.show(-1, enabled = true, unsure = true))
    }

    @Test
    fun aWidePageWhileStillTurningBackTurnsFromTheKeptOrientation() {
        val r = SpreadRotation()
        r.show(5, enabled = true, landscape = false, screen = SCREEN_ORIENTATION_PORTRAIT)
        r.show(-1, enabled = true, landscape = true, screen = SCREEN_ORIENTATION_LANDSCAPE, unsure = true)
        // The screen is still in landscape, turning back to portrait, when the next wide page shows.
        r.show(7, enabled = true, landscape = true, screen = SCREEN_ORIENTATION_LANDSCAPE)
        assertFalse(r.layoutLandscape(true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, r.show(-1, enabled = true, landscape = true, screen = SCREEN_ORIENTATION_LANDSCAPE, unsure = true))
    }

    @Test
    fun aRestoredCopyKeepsWhatIsKept() {
        val r = SpreadRotation()
        r.show(5, enabled = true, screen = SCREEN_ORIENTATION_PORTRAIT)
        r.show(-1, enabled = true, unsure = true)
        val copy = SpreadRotation()
        copy.restore(r.save())
        assertTrue(copy.keeping)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, copy.settled())
    }

    @Test
    fun rotationsOfAPortraitDisplay() {
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, SpreadRotation.fixedOrientation(0, landscape = false))
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, SpreadRotation.fixedOrientation(1, landscape = true))
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, SpreadRotation.fixedOrientation(2, landscape = false))
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SpreadRotation.fixedOrientation(3, landscape = true))
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, SpreadRotation.fixedOrientation(3, landscape = true, reversed = true))
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SpreadRotation.fixedOrientation(1, landscape = true, reversed = true))
    }

    @Test
    fun rotationsOfALandscapeDisplay() {
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, SpreadRotation.fixedOrientation(0, landscape = true))
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SpreadRotation.fixedOrientation(2, landscape = true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, SpreadRotation.fixedOrientation(3, landscape = false))
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, SpreadRotation.fixedOrientation(1, landscape = false))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, SpreadRotation.fixedOrientation(1, landscape = false, reversed = true))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, SpreadRotation.fixedOrientation(-1, landscape = false))
    }
}
