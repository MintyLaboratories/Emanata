package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.FabAction
import com.infinity.comicreader.data.MoreAction
import com.infinity.comicreader.data.Prefs

/**
 * The "Selection menu" setting: which items the selection's ⋮ menu offers, and in what
 * order ([OrderDialog]); and the names of the floating button's choices.
 */
object MoreActionsDialog {

    fun label(context: Context, action: MoreAction): String = context.getString(labelOf(action))

    fun labelOf(action: MoreAction): Int = when (action) {
        MoreAction.MARK_READ -> R.string.menu_mark_read
        MoreAction.MARK_UNREAD -> R.string.menu_mark_unread
        MoreAction.MARK_ALL_READ -> R.string.menu_mark_all_read
        MoreAction.MARK_ALL_UNREAD -> R.string.menu_mark_all_unread
        MoreAction.SET_STARTUP -> R.string.menu_set_startup
        MoreAction.SHOW_IN_FOLDER -> R.string.menu_open_location
        MoreAction.BOOK_INFO -> R.string.menu_book_info
        MoreAction.NAME_CHARSET -> R.string.menu_name_charset
        MoreAction.COMPACT -> R.string.more_compact
        MoreAction.MAKE_COMPATIBLE -> R.string.more_make_compatible
        MoreAction.PEEK -> R.string.menu_peek
    }

    fun iconOf(action: MoreAction): Int = when (action) {
        MoreAction.MARK_READ -> R.drawable.ic_done
        MoreAction.MARK_UNREAD -> R.drawable.ic_radio_unchecked
        MoreAction.MARK_ALL_READ -> R.drawable.ic_done_all
        MoreAction.MARK_ALL_UNREAD -> R.drawable.ic_radio_unchecked
        MoreAction.SET_STARTUP -> R.drawable.ic_home
        MoreAction.SHOW_IN_FOLDER -> R.drawable.ic_folder_open
        MoreAction.BOOK_INFO -> R.drawable.ic_info
        MoreAction.NAME_CHARSET -> R.drawable.ic_translate
        MoreAction.COMPACT -> R.drawable.ic_compress
        MoreAction.MAKE_COMPATIBLE -> R.drawable.ic_unfold_more
        MoreAction.PEEK -> R.drawable.ic_visibility
    }

    /** The items offered, in order, e.g. "Mark as read, Show in folder"; "None" without any. */
    fun summary(context: Context, prefs: Prefs): String =
        prefs.moreActions.visible.joinToString(", ") { label(context, it) }
            .ifEmpty { context.getString(R.string.toolbar_buttons_none) }

    /** Shows the setting; [onDismiss] runs when it closes. */
    fun show(context: Context, prefs: Prefs, onDismiss: () -> Unit = {}): AlertDialog =
        OrderDialog.show(context, R.string.pref_more_actions, R.id.more_actions_list, object : OrderDialog.Items {
            override val count: Int get() = prefs.moreActions.offered.size

            override fun label(position: Int) = label(context, prefs.moreActions.offered[position])

            override fun shown(position: Int) = prefs.moreActions.let { it.offered[position] in it.shown }

            override fun show(position: Int, shown: Boolean) {
                val actions = prefs.moreActions
                prefs.moreActions = actions.with(actions.offered[position], shown)
            }

            override fun move(from: Int, to: Int): Boolean {
                val actions = prefs.moreActions
                val moved = actions.moved(from, to)
                if (moved == actions) return false
                prefs.moreActions = moved
                return true
            }
        }, onDismiss)

    /** The name of the floating button's choice [action]. */
    fun fabLabel(context: Context, action: FabAction): String = context.getString(
        when (action) {
            FabAction.NONE -> R.string.fab_none
            FabAction.OPEN -> R.string.fab_open
            FabAction.MOVE -> R.string.file_op_move
            FabAction.COPY -> R.string.file_op_copy
            FabAction.DELETE -> R.string.delete
            FabAction.SHARE -> R.string.file_op_share
            FabAction.FAVORITE -> R.string.toolbar_button_favorite
        }
    )
}
