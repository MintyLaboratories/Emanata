package com.infinity.comicreader.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.infinity.comicreader.R

/**
 * Fullscreen that stays fullscreen. An activity hides its system bars with [apply]; a
 * dialog or sheet it opens hides them as well ([follow]) before it shows, since a focused
 * window that does not ask for them hidden brings them back. Hidden bars come back for a
 * moment on a swipe from an edge.
 */
object Immersive {
    /** Hides or shows the system bars of [window], and notes which on it, for the dialogs of an activity's. */
    fun apply(window: Window, hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        window.decorView.setTag(R.id.immersive_bars, hidden)
    }

    /** True when [window] hid its system bars through [apply]. */
    fun hidden(window: Window?): Boolean = window?.decorView?.getTag(R.id.immersive_bars) == true

    /** Hides the system bars of [dialog], not yet shown, while those of the activity it opens over are hidden. */
    fun <T : Dialog> follow(dialog: T): T {
        val activity = dialog.context.activity() ?: return dialog
        if (!hidden(activity.window)) return dialog
        dialog.window?.let { apply(it, true) }
        return dialog
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}

/** [AlertDialog.Builder.show] that keeps a fullscreen activity's bars hidden ([Immersive.follow]). */
fun AlertDialog.Builder.showKeepingFullscreen(): AlertDialog = Immersive.follow(create()).also { it.show() }
