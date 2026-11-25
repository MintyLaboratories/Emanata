package minty.emanata.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A bottom sheet swiped away keeps its window over the screen until it has slid out,
 * swallowing a tap made meanwhile. [passTouchesWhileLeaving] lets touches through to
 * the window below once the sheet slides out.
 */
object SheetExit {
    /**
     * True when a sheet in [state] at [offset] (BottomSheetBehavior's slide offset: below
     * 0 under its peek) after [last] is sliding out: settling downward, under its peek or
     * with no peek to stop at ([skipCollapsed]).
     */
    fun leaving(state: Int, offset: Float, last: Float, skipCollapsed: Boolean): Boolean =
        state == BottomSheetBehavior.STATE_SETTLING && offset < last && (offset < 0f || skipCollapsed)

    /** Makes [dialog]'s window untouchable while its sheet slides out. */
    fun passTouchesWhileLeaving(dialog: BottomSheetDialog) {
        val behavior = dialog.behavior
        behavior.addBottomSheetCallback(PassThrough({ dialog.window }, { behavior.state }, { behavior.skipCollapsed }))
    }

    /**
     * Makes [window] untouchable while the sheet it follows, in [state] and [skipCollapsed]
     * as read from its behavior, is [leaving]; touchable again once it stops or turns back.
     */
    class PassThrough(
        private val window: () -> Window?,
        private val state: () -> Int,
        private val skipCollapsed: () -> Boolean
    ) : BottomSheetBehavior.BottomSheetCallback() {
        private var last = Float.NaN
        private var passing = false

        override fun onStateChanged(sheet: View, newState: Int) {
            if (newState != BottomSheetBehavior.STATE_SETTLING && newState != BottomSheetBehavior.STATE_HIDDEN) pass(false)
        }

        override fun onSlide(sheet: View, offset: Float) {
            if (!last.isNaN()) pass(leaving(state(), offset, last, skipCollapsed()))
            last = offset
        }

        private fun pass(on: Boolean) {
            if (on == passing) return
            passing = on
            val w = window() ?: return
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }
}
