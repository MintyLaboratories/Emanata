package com.infinity.comicreader.ui

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Rotate image": which wide pages are drawn turned, and the screen lock while one shows. */
class ImageTurnTest {

    @Test
    fun aWidePageInPortraitIsTurnedWhileTheSettingIsOn() {
        val t = ImageTurn()
        assertTrue(t.turns(3, enabled = true, landscape = false))
        assertFalse(t.turns(3, enabled = false, landscape = false))
        assertFalse(t.turns(3, enabled = true, landscape = true))
        assertFalse(t.turns(-1, enabled = true, landscape = false))
    }

    @Test
    fun theScreenLocksWhileATurnedPageShowsAndGoesBackToTheReadersOwnAfter() {
        val t = ImageTurn()
        assertNull(t.show(-1, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        assertEquals(SCREEN_ORIENTATION_LOCKED, t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        assertEquals(3, t.locked)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, t.show(-1, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        assertEquals(-1, t.locked)
        // The reader's own pick comes back, not the system's.
        t.show(5, enabled = true, landscape = false, user = SCREEN_ORIENTATION_PORTRAIT)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, t.show(-1, enabled = true, landscape = false, user = SCREEN_ORIENTATION_PORTRAIT))
    }

    @Test
    fun widePagesInARowKeepTheLockWithoutAskingAgain() {
        val t = ImageTurn()
        t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED)
        assertNull(t.show(4, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        assertEquals(4, t.locked)
    }

    @Test
    fun inLandscapeOrWithTheSettingOffNothingLocks() {
        val t = ImageTurn()
        assertNull(t.show(3, enabled = true, landscape = true, user = SCREEN_ORIENTATION_UNSPECIFIED))
        assertNull(t.show(3, enabled = false, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        assertEquals(-1, t.locked)
    }

    @Test
    fun turningToLandscapeOrTheSettingOffLetsTheLockGo() {
        val t = ImageTurn()
        t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, t.show(3, enabled = true, landscape = true, user = SCREEN_ORIENTATION_UNSPECIFIED))
        t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, t.show(3, enabled = false, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
    }

    @Test
    fun anOrientationPickedWhileLockedWinsForThatPage() {
        val t = ImageTurn()
        t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED)
        t.choose()
        assertEquals(-1, t.locked)
        assertNull(t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        // The picture stays turned: only the lock was declined.
        assertTrue(t.turns(3, enabled = true, landscape = false))
        // Another wide page locks again, and so does the declined one once left.
        t.show(-1, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED)
        assertEquals(SCREEN_ORIENTATION_LOCKED, t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
    }

    @Test
    fun aPickWithoutALockChangesNothing() {
        val t = ImageTurn()
        t.choose()
        assertEquals(SCREEN_ORIENTATION_LOCKED, t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
    }

    @Test
    fun saveAndRestoreCarryTheLockAndTheDeclinedPage() {
        val t = ImageTurn()
        t.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED)
        val locked = ImageTurn().apply { restore(t.save()) }
        assertEquals(3, locked.locked)
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, locked.show(-1, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        t.choose()
        val declined = ImageTurn().apply { restore(t.save()) }
        assertNull(declined.show(3, enabled = true, landscape = false, user = SCREEN_ORIENTATION_UNSPECIFIED))
        val ignored = ImageTurn().apply { restore(intArrayOf(1, 2, 3)) }
        assertEquals(-1, ignored.locked)
    }

    @Test
    fun thePictureTurnsItsTopToTheRightOrForLeftHandsToTheLeft() {
        assertEquals(1, ImageTurn.quarter(leftHanded = false))
        assertEquals(-1, ImageTurn.quarter(leftHanded = true))
    }
}
