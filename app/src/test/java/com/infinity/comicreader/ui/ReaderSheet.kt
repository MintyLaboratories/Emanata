package com.infinity.comicreader.ui

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
        dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
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
