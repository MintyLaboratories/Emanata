package com.infinity.comicreader.ui

import android.view.View
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
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            private var last = Float.NaN
            private var passing = false

            override fun onStateChanged(sheet: View, newState: Int) {
                if (newState != BottomSheetBehavior.STATE_SETTLING && newState != BottomSheetBehavior.STATE_HIDDEN) pass(false)
            }

            override fun onSlide(sheet: View, offset: Float) {
                if (!last.isNaN()) pass(leaving(behavior.state, offset, last, behavior.skipCollapsed))
                last = offset
            }

            private fun pass(on: Boolean) {
                if (on == passing) return
                passing = on
                val window = dialog.window ?: return
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        })
    }
}
