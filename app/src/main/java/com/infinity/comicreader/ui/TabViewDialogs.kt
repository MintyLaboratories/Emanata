package com.infinity.comicreader.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs

/** Dialogs that change one library tab's view; every choice is written at once. */
object TabViewDialogs {

    /** The title of [tab]'s view settings, e.g. "Browse view". */
    @StringRes
    fun titleOf(tab: String): Int = when (tab) {
        Prefs.TAB_FAVORITES -> R.string.view_favorites
        Prefs.TAB_HISTORY -> R.string.view_history
        Prefs.TAB_BOOKMARKS -> R.string.view_bookmarks
        else -> R.string.view_browse
    }

    /** One line naming [tab]'s view: "Grid · 4 × Auto" or "List". */
    fun summary(context: Context, prefs: Prefs, tab: String): String {
        val v = prefs.view(tab)
        if (!v.grid) return context.getString(R.string.layout_list)
        val size = GridPick.label(v.columns, v.rows, context.getString(R.string.grid_size_auto))
        return context.getString(R.string.view_grid_summary, size)
    }

    /** The "Grid dimensions" picker for [tab]; a pick is written and closes it. */
    fun showGridSize(context: Context, prefs: Prefs, tab: String): AlertDialog {
        val picker = GridPicker(context).apply { id = R.id.grid_size }
        val pad = (context.resources.displayMetrics.density * 16).toInt()
        picker.setPadding(pad, pad, pad, 0)
        val v = prefs.view(tab)
        picker.select(v.columns, v.rows)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.grid_size)
            .setView(picker)
            .setNegativeButton(R.string.cancel, null)
            .show()
        picker.onPick = { columns, rows ->
            prefs.setGridSize(tab, columns, rows)
            dialog.dismiss()
        }
        return dialog
    }

    /** All of [tab]'s view settings; [onDismiss] runs when it closes. */
    fun showTabView(context: Context, prefs: Prefs, tab: String, onDismiss: () -> Unit = {}): AlertDialog {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_tab_view, null)
        val v = prefs.view(tab)
        root.findViewById<RadioGroup>(R.id.layout_group).apply {
            check(if (v.grid) R.id.layout_grid else R.id.layout_list)
            setOnCheckedChangeListener { _, id -> prefs.setGrid(tab, id == R.id.layout_grid) }
        }
        root.findViewById<GridPicker>(R.id.grid_size).apply {
            select(v.columns, v.rows)
            onPick = { columns, rows -> prefs.setGridSize(tab, columns, rows) }
        }
        return AlertDialog.Builder(context)
            .setTitle(titleOf(tab))
            .setView(root)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }
}
