package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.infinity.comicreader.R
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/** The reader settings sheet of [viewer], opened from the toolbar, for tests to tap. */
class ReaderSheet(private val viewer: ViewerActivity) {
    val dialog: BottomSheetDialog

    init {
        shadowOf(viewer).clickMenuItem(R.id.menu_tune)
        idle()
        // This reader's sheet: another reader may show a dialog meanwhile.
        dialog = ShadowDialog.getShownDialogs().filterIsInstance<BottomSheetDialog>().last { it.shownBy(viewer) }
    }

    fun <T : View> view(id: Int): T = dialog.findViewById<T>(id)!!

    /** Taps the control [id] and lets the reader act on it. */
    fun tap(id: Int): ReaderSheet {
        view<View>(id).performClick()
        idle()
        return this
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

/** The dialog [activity] showed last: readers of earlier tests, never destroyed, may show theirs meanwhile. */
fun latestDialogOf(activity: Activity): Dialog = ShadowDialog.getShownDialogs().last { it.shownBy(activity) }

/** True when this dialog opened over [activity]. */
fun Dialog.shownBy(activity: Activity): Boolean = context.activity() === activity

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
