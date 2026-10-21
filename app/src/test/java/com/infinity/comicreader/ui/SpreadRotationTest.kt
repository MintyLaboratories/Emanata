package com.infinity.comicreader.ui

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
}
