package com.infinity.comicreader.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The picks Move and Copy made, each waiting to be pasted as a Paste bubble, kept while
 * the app runs. Pasting one turns it into its operation ([FileWork]).
 */
object FileClipboard {
    /** An item picked up, at [path]. */
    data class Item(val path: String, val isDir: Boolean) {
        val name: String get() = java.io.File(path).name
    }

    /**
     * [items] picked up together, from the device and shares alike: [move] deletes each from
     * where it was once pasted. [id] tells picks apart and places the bubble among the
     * operations' ([FileWork.nextOrder]): a pick made, or given back, later sits lower.
     */
    data class Clip(val items: List<Item>, val move: Boolean, val id: Long = FileWork.nextOrder()) {
        /** The name of the first item, the only one when [count] is 1. */
        val name: String get() = items.first().name

        val count: Int get() = items.size
    }

    private val state = MutableStateFlow<List<Clip>>(emptyList())

    /** The picks waiting, oldest first. */
    val clips: StateFlow<List<Clip>> get() = state

    /** The pick [id], if still waiting. */
    operator fun get(id: Long): Clip? = state.value.firstOrNull { it.id == id }

    /** Adds [clip], in place of a pick of its [FileClipboard.Clip.id] if there is one. */
    fun add(clip: Clip) {
        state.update { list -> (list.filterNot { it.id == clip.id } + clip).sortedBy { it.id } }
    }

    /** Lets go of the pick [id]. */
    fun remove(id: Long) {
        state.update { list -> list.filterNot { it.id == id } }
    }

    /** Changes the items of every pick by [change]; a pick left with none goes. */
    fun edit(change: (List<Item>) -> List<Item>) {
        state.update { list -> list.mapNotNull { c -> change(c.items).takeIf { it.isNotEmpty() }?.let { if (it == c.items) c else c.copy(items = it) } } }
    }

    fun clear() {
        state.value = emptyList()
    }

    /** [items] once each, without those inside a folder among them: the folder takes them along. */
    fun outermost(items: List<Item>): List<Item> {
        val folders = items.filter { it.isDir }.map { it.path }
        return items.distinctBy { it.path }.filter { item -> folders.none { it != item.path && Transfer.isWithin(item.path, it) } }
    }
}
