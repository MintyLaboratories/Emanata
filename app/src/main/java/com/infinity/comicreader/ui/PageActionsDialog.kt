package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.PageAction
import com.infinity.comicreader.data.Prefs

/** The "Page menu" setting: which rows the reader's page long-press menu offers, and in what order ([OrderDialog]). */
object PageActionsDialog {

    /** The row's name in the setting. */
    fun labelOf(action: PageAction): Int = when (action) {
        PageAction.HIDE -> R.string.menu_hide_page
        PageAction.SET_COVER -> R.string.page_set_cover
        PageAction.PAGE_INFO -> R.string.page_info
        PageAction.SPREAD -> R.string.page_spread_setting
    }

    /** The row's icon in the page menu; Show alone has one of its own. */
    fun iconOf(action: PageAction): Int = when (action) {
        PageAction.HIDE -> R.drawable.ic_visibility_off
        PageAction.SET_COVER -> R.drawable.ic_image
        PageAction.PAGE_INFO -> R.drawable.ic_info
        PageAction.SPREAD -> R.drawable.ic_auto_stories
    }

    /** The rows offered, in order, e.g. "Hide this page, Page info"; "None" without any. */
    fun summary(context: Context, prefs: Prefs): String =
        prefs.pageActions.visible.joinToString(", ") { context.getString(labelOf(it)) }
            .ifEmpty { context.getString(R.string.toolbar_buttons_none) }

    /** Shows the setting; [onDismiss] runs when it closes. */
    fun show(context: Context, prefs: Prefs, onDismiss: () -> Unit = {}): AlertDialog =
        OrderDialog.show(context, R.string.pref_page_actions, R.id.page_actions_list, object : OrderDialog.Items {
            override val count: Int get() = prefs.pageActions.order.size

            override fun label(position: Int) = context.getString(labelOf(prefs.pageActions.order[position]))

            override fun shown(position: Int) = prefs.pageActions.let { it.order[position] in it.shown }

            override fun show(position: Int, shown: Boolean) {
                val actions = prefs.pageActions
                prefs.pageActions = actions.with(actions.order[position], shown)
            }

            override fun move(from: Int, to: Int): Boolean {
                val actions = prefs.pageActions
                val moved = actions.moved(from, to)
                if (moved == actions) return false
                prefs.pageActions = moved
                return true
            }
        }, onDismiss)
}
