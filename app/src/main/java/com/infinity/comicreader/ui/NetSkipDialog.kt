package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.net.NetSkip

/**
 * "Skip on metered connections" and "On slow shares, skip": a multiple choice of optional network
 * work, where "Any network use I didn't start" ticks every other choice ([NetSkip.toggle]).
 */
object NetSkipDialog {
    enum class Kind(val offered: List<NetSkip>, val title: Int) {
        METERED(NetSkip.METERED_CHOICES, R.string.pref_metered_skip),
        SLOW(NetSkip.SLOW_CHOICES, R.string.pref_slow_skip)
    }

    /** What [choice] says in [kind]'s list. */
    fun label(context: Context, kind: Kind, choice: NetSkip): String = context.getString(
        when (choice) {
            NetSkip.COVERS -> if (kind == Kind.SLOW) R.string.net_skip_covers_slow else R.string.net_skip_covers
            NetSkip.HASHING -> if (kind == Kind.SLOW) R.string.net_skip_hashing_slow else R.string.net_skip_hashing
            NetSkip.READ_AHEAD -> if (kind == Kind.SLOW) R.string.net_skip_read_ahead_slow else R.string.net_skip_read_ahead
            NetSkip.DOWNLOADS -> R.string.net_skip_downloads
            NetSkip.ANY -> R.string.net_skip_any
        }
    )

    /** The setting's summary: what [chosen] skips, and that explicit actions always run. */
    fun summary(context: Context, kind: Kind, chosen: Set<NetSkip>): String {
        val shown = if (NetSkip.ANY in chosen) listOf(NetSkip.ANY) else kind.offered.filter { it in chosen }
        if (shown.isEmpty()) return context.getString(R.string.net_skip_none)
        return context.getString(R.string.net_skip_explicit, shown.joinToString(", ") { label(context, kind, it) })
    }

    /** Asks which of [kind]'s choices to skip, [current] ticked; [onSaved] hears the new set. */
    fun show(context: Context, kind: Kind, current: Set<NetSkip>, onSaved: (Set<NetSkip>) -> Unit): AlertDialog {
        var chosen = if (NetSkip.ANY in current) kind.offered.toSet() else current.intersect(kind.offered.toSet())
        val checked = BooleanArray(kind.offered.size) { kind.offered[it] in chosen }
        return AlertDialog.Builder(context)
            .setTitle(kind.title)
            .setMultiChoiceItems(kind.offered.map { label(context, kind, it) }.toTypedArray(), checked) { dialog, which, isChecked ->
                chosen = NetSkip.toggle(chosen, kind.offered[which], isChecked, kind.offered)
                val list = (dialog as AlertDialog).listView
                kind.offered.forEachIndexed { i, choice ->
                    checked[i] = choice in chosen
                    list.setItemChecked(i, checked[i])
                }
            }
            .setPositiveButton(R.string.save) { _, _ -> onSaved(chosen) }
            .setNegativeButton(R.string.cancel, null)
            .showKeepingFullscreen()
    }
}
