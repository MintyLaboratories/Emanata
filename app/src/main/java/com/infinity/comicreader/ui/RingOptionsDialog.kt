package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.RingOption

/**
 * The "View ring options" setting: a checkbox per action to put it on the ring, 3 to 6 of
 * them, and a handle to drag it into place ([OrderDialog]); and the "Grid/list button tap"
 * choices. Every change is written at once.
 */
object RingOptionsDialog {

    /** The options shown, in order, e.g. "Smaller, Larger, Details". */
    fun summary(context: Context, prefs: Prefs): String {
        val labels = RingLabels(context)
        return prefs.ringOptions.visible.joinToString(", ") { labels.settingName(it) }
    }

    /** Shows the setting; [onDismiss] runs when it closes. */
    fun show(context: Context, prefs: Prefs, onDismiss: () -> Unit = {}): AlertDialog {
        val labels = RingLabels(context)
        return OrderDialog.show(context, R.string.pref_view_ring, R.id.view_ring_list, object : OrderDialog.Items {
            override val count: Int get() = prefs.ringOptions.order.size

            override fun label(position: Int) = labels.settingName(prefs.ringOptions.order[position])

            override fun shown(position: Int) = prefs.ringOptions.let { it.order[position] in it.shown }

            override fun show(position: Int, shown: Boolean): Boolean {
                val options = prefs.ringOptions
                val changed = options.with(options.order[position], shown)
                prefs.ringOptions = changed
                return (options.order[position] in changed.shown) == shown
            }

            override fun move(from: Int, to: Int): Boolean {
                val options = prefs.ringOptions
                val moved = options.moved(from, to)
                if (moved == options) return false
                prefs.ringOptions = moved
                return true
            }
        }, onDismiss, note = context.getString(R.string.view_ring_pick))
    }

    /** The "Grid/list button tap" choices, every ring action, as names and stored values. */
    fun tapChoices(context: Context): Pair<Array<String>, Array<String>> {
        val labels = RingLabels(context)
        return RingOption.entries.map { labels.settingName(it) }.toTypedArray() to RingOption.entries.map { it.key }.toTypedArray()
    }
}
