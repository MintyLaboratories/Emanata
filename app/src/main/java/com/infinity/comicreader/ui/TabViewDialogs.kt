package com.infinity.comicreader.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
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

    /** One line naming [tab]'s view: "Grid · 4 × Auto · Dense" or "List · Normal · Comfortable". */
    fun summary(context: Context, prefs: Prefs, tab: String): String {
        val v = prefs.view(tab)
        val layout = if (!v.grid) {
            context.getString(R.string.view_list_summary, densityLabel(context, v.density))
        } else {
            context.getString(R.string.view_grid_summary, GridPick.label(v.columns, v.rows, context.getString(R.string.grid_size_auto)))
        }
        return "$layout · ${detailLabel(context, v.detail)}"
    }

    private val DETAIL_BUTTONS = listOf(
        DetailDisplay.HIDDEN to R.id.detail_hidden,
        DetailDisplay.COMFORTABLE to R.id.detail_comfortable,
        DetailDisplay.DENSE to R.id.detail_dense
    )

    private fun detailLabel(context: Context, detail: DetailDisplay) = context.getString(
        when (detail) {
            DetailDisplay.HIDDEN -> R.string.detail_hidden
            DetailDisplay.COMFORTABLE -> R.string.detail_comfortable
            DetailDisplay.DENSE -> R.string.detail_dense
        }
    )

    /** The "Detail display" choice for [tab]; a pick is written and closes it. */
    fun showDetail(context: Context, prefs: Prefs, tab: String): AlertDialog {
        val values = DETAIL_BUTTONS.map { it.first }
        val labels = values.map { detailLabel(context, it) }.toTypedArray()
        return AlertDialog.Builder(context)
            .setTitle(R.string.detail_display)
            .setSingleChoiceItems(labels, values.indexOf(prefs.view(tab).detail)) { dialog, which ->
                prefs.setDetail(tab, values[which])
                dialog.dismiss()
            }
            .show()
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

    private val DENSITY_BUTTONS = listOf(
        ListDensity.COMPACT to R.id.density_compact,
        ListDensity.NORMAL to R.id.density_normal,
        ListDensity.LARGE to R.id.density_large
    )

    private fun densityLabel(context: Context, density: ListDensity) = context.getString(
        when (density) {
            ListDensity.COMPACT -> R.string.density_compact
            ListDensity.NORMAL -> R.string.density_normal
            ListDensity.LARGE -> R.string.density_large
        }
    )

    /** The "List density" choice for [tab]; a pick is written and closes it. */
    fun showDensity(context: Context, prefs: Prefs, tab: String): AlertDialog {
        val values = DENSITY_BUTTONS.map { it.first }
        val labels = values.map { densityLabel(context, it) }.toTypedArray()
        return AlertDialog.Builder(context)
            .setTitle(R.string.list_density)
            .setSingleChoiceItems(labels, values.indexOf(prefs.view(tab).density)) { dialog, which ->
                prefs.setDensity(tab, values[which])
                dialog.dismiss()
            }
            .show()
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
        root.findViewById<RadioGroup>(R.id.detail_group).apply {
            check(DETAIL_BUTTONS.first { it.first == v.detail }.second)
            setOnCheckedChangeListener { _, id ->
                DETAIL_BUTTONS.firstOrNull { it.second == id }?.let { prefs.setDetail(tab, it.first) }
            }
        }
        root.findViewById<RadioGroup>(R.id.density_group).apply {
            check(DENSITY_BUTTONS.first { it.first == v.density }.second)
            setOnCheckedChangeListener { _, id ->
                DENSITY_BUTTONS.firstOrNull { it.second == id }?.let { prefs.setDensity(tab, it.first) }
            }
        }
        return AlertDialog.Builder(context)
            .setTitle(titleOf(tab))
            .setView(root)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }
}
