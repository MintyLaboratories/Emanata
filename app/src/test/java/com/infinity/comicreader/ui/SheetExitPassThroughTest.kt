package com.infinity.comicreader.ui

import android.app.Application
import android.app.Dialog
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window flag [SheetExit.PassThrough] sets while a sheet slides out, and clears after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetExitPassThroughTest {

    private lateinit var dialog: Dialog
    private lateinit var sheet: View
    private var state = STATE_DRAGGING
    private var skipCollapsed = false
    private lateinit var callback: SheetExit.PassThrough

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        dialog = Dialog(context)
        sheet = View(context)
        callback = SheetExit.PassThrough({ dialog.window }, { state }, { skipCollapsed })
    }

    private fun passing() = (dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0

    /** Moves the sheet to [offset] in [newState]. */
    private fun slide(newState: Int, offset: Float) {
        if (newState != state) {
            state = newState
            callback.onStateChanged(sheet, newState)
        }
        callback.onSlide(sheet, offset)
    }

    @Test
    fun touchesPassOnlyOnceTheSheetIsLetGoDownward() {
        slide(STATE_DRAGGING, -0.1f)
        slide(STATE_DRAGGING, -0.2f)
        assertFalse(passing())
        slide(STATE_SETTLING, -0.4f)
        assertTrue(passing())
        slide(STATE_SETTLING, -0.8f)
        assertTrue(passing())
        callback.onStateChanged(sheet, STATE_HIDDEN)
        assertTrue(passing())
    }

    @Test
    fun aSheetThatSettlesBackTakesTouchesAgain() {
        slide(STATE_DRAGGING, -0.1f)
        slide(STATE_SETTLING, -0.3f)
        assertTrue(passing())
        // It springs back up to its peek.
        slide(STATE_SETTLING, -0.2f)
        assertFalse(passing())
        slide(STATE_SETTLING, -0.3f)
        assertTrue(passing())
        slide(STATE_COLLAPSED, 0f)
        assertFalse(passing())
    }

    @Test
    fun aSheetWithNoPeekPassesAsSoonAsItSettlesDown() {
        skipCollapsed = true
        slide(STATE_DRAGGING, 0.9f)
        slide(STATE_SETTLING, 0.7f)
        assertTrue(passing())
    }
}
