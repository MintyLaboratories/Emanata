package minty.emanata.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.data.ToolbarButton

/**
 * The "Toolbar buttons" setting: a checkbox per button to show it, and a handle
 * to drag it into place ([OrderDialog]). Every change is written at once, so the toolbar follows.
 */
object ToolbarButtonsDialog {

    fun label(context: Context, button: ToolbarButton): String = context.getString(
        when (button) {
            ToolbarButton.SORT -> R.string.menu_sort
            ToolbarButton.FILTER -> R.string.menu_filter
            ToolbarButton.FAVORITE -> R.string.toolbar_button_favorite
            ToolbarButton.SEARCH -> R.string.menu_search
        }
    )

    /** The buttons shown, in order, e.g. "Sort, Filter, Search"; "None" without any. */
    fun summary(context: Context, prefs: Prefs): String =
        prefs.toolbarButtons.visible.joinToString(", ") { label(context, it) }
            .ifEmpty { context.getString(R.string.toolbar_buttons_none) }

    /** Shows the setting; [onDismiss] runs when it closes. */
    fun show(context: Context, prefs: Prefs, onDismiss: () -> Unit = {}): AlertDialog =
        OrderDialog.show(context, R.string.pref_toolbar_buttons, R.id.toolbar_buttons_list, object : OrderDialog.Items {
            override val count: Int get() = prefs.toolbarButtons.order.size

            override fun label(position: Int) = label(context, prefs.toolbarButtons.order[position])

            override fun shown(position: Int) = prefs.toolbarButtons.let { it.order[position] in it.shown }

            override fun show(position: Int, shown: Boolean): Boolean {
                val buttons = prefs.toolbarButtons
                prefs.toolbarButtons = buttons.with(buttons.order[position], shown)
                return true
            }

            override fun move(from: Int, to: Int): Boolean {
                val buttons = prefs.toolbarButtons
                val moved = buttons.moved(from, to)
                if (moved == buttons) return false
                prefs.toolbarButtons = moved
                return true
            }
        }, onDismiss)
}
