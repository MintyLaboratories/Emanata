package com.infinity.comicreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ToolbarButton

/**
 * The "Toolbar buttons" setting: a checkbox per button to show it, and a handle
 * to drag it into place. Every change is written at once, so the toolbar follows.
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
    fun show(context: Context, prefs: Prefs, onDismiss: () -> Unit = {}): AlertDialog {
        val list = RecyclerView(context).apply {
            id = R.id.toolbar_buttons_list
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val adapter = Rows(prefs)
        list.adapter = adapter
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                // Rows are only dragged.
            }

            override fun isLongPressDragEnabled() = false
        })
        helper.attachToRecyclerView(list)
        adapter.onHandleDown = { helper.startDrag(it) }
        val pad = (context.resources.displayMetrics.density * 8).toInt()
        list.setPadding(0, pad, 0, 0)
        return AlertDialog.Builder(context)
            .setTitle(R.string.pref_toolbar_buttons)
            .setView(list)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val shown: CheckBox = view.findViewById(R.id.toolbar_button_shown)
        val handle: View = view.findViewById(R.id.toolbar_button_handle)
    }

    private class Rows(private val prefs: Prefs) : RecyclerView.Adapter<Holder>() {
        var onHandleDown: ((RecyclerView.ViewHolder) -> Unit)? = null

        fun move(from: Int, to: Int) {
            val buttons = prefs.toolbarButtons
            val moved = buttons.moved(from, to)
            if (moved == buttons) return
            prefs.toolbarButtons = moved
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = prefs.toolbarButtons.order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_toolbar_button, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val buttons = prefs.toolbarButtons
            val button = buttons.order[position]
            holder.shown.setOnCheckedChangeListener(null)
            holder.shown.text = label(holder.itemView.context, button)
            holder.shown.isChecked = button in buttons.shown
            holder.shown.setOnCheckedChangeListener { _, checked ->
                prefs.toolbarButtons = prefs.toolbarButtons.with(button, checked)
            }
            holder.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onHandleDown?.invoke(holder)
                false
            }
        }
    }
}
