package com.infinity.comicreader.ui

import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetExitTest {

    @Test
    fun aSheetSettlingBelowItsPeekIsLeaving() {
        assertTrue(SheetExit.leaving(STATE_SETTLING, -0.4f, -0.3f, skipCollapsed = false))
    }

    @Test
    fun aSheetStillHeldOrComingBackStays() {
        // The finger is still on it.
        assertFalse(SheetExit.leaving(STATE_DRAGGING, -0.4f, -0.3f, skipCollapsed = false))
        // Springing back up to its peek.
        assertFalse(SheetExit.leaving(STATE_SETTLING, -0.3f, -0.4f, skipCollapsed = false))
        // Settling from expanded down to its peek.
        assertFalse(SheetExit.leaving(STATE_SETTLING, 0.4f, 0.5f, skipCollapsed = false))
    }

    @Test
    fun aSheetWithNoPeekLeavesAsSoonAsItSettlesDown() {
        assertTrue(SheetExit.leaving(STATE_SETTLING, 0.4f, 0.5f, skipCollapsed = true))
        assertFalse(SheetExit.leaving(STATE_SETTLING, 0.5f, 0.4f, skipCollapsed = true))
    }
}
