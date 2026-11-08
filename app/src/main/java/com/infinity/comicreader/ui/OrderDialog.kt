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

/**
 * A setting of items in an order, each shown or not: a checkbox per item to show it, and
 * a handle to drag it into place. Every change is written at once, so what it sets follows.
 */
internal object OrderDialog {

    /** The items the dialog lists, read and written through. */
    interface Items {
        val count: Int

        fun label(position: Int): String

        fun shown(position: Int): Boolean

        /** Shows or hides the item at [position]; false when that is refused, and the checkbox goes back. */
        fun show(position: Int, shown: Boolean): Boolean

        /** Moves the item at [from] to [to]; false when nothing moved. */
        fun move(from: Int, to: Int): Boolean
    }

    /** Shows [items] under [title] and [note], in a list with id [listId]; [onDismiss] runs when it closes. */
    fun show(context: Context, title: Int, listId: Int, items: Items, onDismiss: () -> Unit = {}, note: String? = null): AlertDialog {
        val list = RecyclerView(context).apply {
            id = listId
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val adapter = Rows(items)
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
            .setTitle(title)
            .apply { if (note != null) setMessage(note) }
            .setView(list)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val shown: CheckBox = view.findViewById(R.id.toolbar_button_shown)
        val handle: View = view.findViewById(R.id.toolbar_button_handle)
    }

    private class Rows(private val items: Items) : RecyclerView.Adapter<Holder>() {
        var onHandleDown: ((RecyclerView.ViewHolder) -> Unit)? = null

        fun move(from: Int, to: Int) {
            if (items.move(from, to)) notifyItemMoved(from, to)
        }

        override fun getItemCount() = items.count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_toolbar_button, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.shown.setOnCheckedChangeListener(null)
            holder.shown.text = items.label(position)
            holder.shown.isChecked = items.shown(position)
            holder.shown.setOnCheckedChangeListener { box, checked ->
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION && !items.show(at, checked)) {
                    // Posted: a checkbox set back from within its own change keeps the new state.
                    box.post { notifyItemChanged(holder.bindingAdapterPosition) }
                }
            }
            holder.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onHandleDown?.invoke(holder)
                false
            }
        }
    }
}
