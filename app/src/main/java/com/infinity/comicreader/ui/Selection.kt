package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.FabAction
import com.infinity.comicreader.data.MoreAction
import com.infinity.comicreader.util.Formats

/**
 * The items selected in a library tab, by row ([RowChanges.keyOf]), in the order they
 * were picked. Selecting starts with the first item and ends with the last one let go.
 */
class Selection {
    private val items = LinkedHashMap<String, BookEntry>()

    /** Rows to select once listed again, as after the screen was made anew. */
    private var pending: Set<String> = emptySet()

    val entries: List<BookEntry> get() = items.values.toList()

    val size: Int get() = items.size

    /** True while anything is selected. */
    val active: Boolean get() = items.isNotEmpty()

    /** The rows selected, to [restore] later. */
    val keys: List<String> get() = items.keys.toList()

    operator fun contains(entry: BookEntry): Boolean = RowChanges.keyOf(entry) in items

    /** Selects [entry], or lets it go when it was; true when it is selected now. */
    fun toggle(entry: BookEntry): Boolean {
        val key = RowChanges.keyOf(entry)
        if (items.remove(key) != null) return false
        items[key] = entry
        return true
    }

    /** Selects every one of [listed] besides those already. */
    fun selectAll(listed: List<BookEntry>) {
        for (e in listed) items.getOrPut(RowChanges.keyOf(e)) { e }
    }

    fun clear() {
        items.clear()
        pending = emptySet()
    }

    /** Selects the rows [keys] as soon as they are listed ([retain]). */
    fun restore(keys: Collection<String>) {
        pending = keys.toSet()
    }

    /**
     * Keeps selected only what is [listed], as it is listed now (a favorite star or a read
     * mark changed), and selects rows waiting to be ([restore]).
     */
    fun retain(listed: List<BookEntry>) {
        val now = listed.associateBy { RowChanges.keyOf(it) }
        val kept = LinkedHashMap<String, BookEntry>()
        for (key in items.keys) now[key]?.let { kept[key] = it }
        for (key in pending) now[key]?.let { kept.getOrPut(key) { it } }
        pending = emptySet()
        items.clear()
        items.putAll(kept)
    }

    /** Bytes the selected items take, folders by [folderSize]; null while any is unknown. */
    fun totalSize(folderSize: (BookEntry) -> Long?): Long? {
        var total = 0L
        for (e in items.values) total += (if (e.isDir) folderSize(e) else e.size.takeIf { it > 0 }) ?: return null
        return total
    }
}

/** What the selection bar and its ⋮ menu offer for the items selected, and what the floating button does. */
object SelectionActions {

    /** A button of the bar, left to right. */
    enum class Bar { MOVE, COPY, DELETE, SHARE, RENAME, FAVORITE, MORE }

    /** What the floating button shows. */
    enum class Fab { GRID_LIST, OPEN, MOVE, COPY, DELETE, SHARE, FAVORITE }

    /**
     * The bar's buttons [shown], those of them [enabled], and whether Favorite takes the
     * items out of the favorites ([unfavorite]: each one already is).
     */
    data class BarState(val shown: Set<Bar>, val enabled: Set<Bar>, val unfavorite: Boolean)

    /** What the ⋮ menu's items depend on beyond the items themselves. */
    class MoreFacts(
        val keepSimple: Boolean,
        /** True when the item is listed in the folder browsed. */
        val browsedHere: (BookEntry) -> Boolean,
        /** True when the book's file names may be read in another charset. */
        val charsetApplies: (BookEntry) -> Boolean,
        val canPeek: (BookEntry) -> Boolean,
        /** True when the item is a book Compact and Make compatible can rebuild: an archive on the device that can be changed. */
        val rebuildable: (BookEntry) -> Boolean = { false },
        /** The items are listed in History. */
        val inHistory: Boolean = false,
        /** The items are listed in Bookmarks. */
        val inBookmarks: Boolean = false
    )

    /**
     * The bar for [entries]: Bookmarks list bookmarks, so they offer no Move, Copy or Rename,
     * and Delete takes the bookmarks away; elsewhere items that can be [changeable] are moved,
     * copied, deleted and renamed (one at a time), and those [shareable] shared.
     */
    fun bar(
        bookmarks: Boolean,
        entries: List<BookEntry>,
        changeable: (BookEntry) -> Boolean,
        shareable: (BookEntry) -> Boolean,
        more: List<MoreAction>
    ): BarState {
        val shown = if (bookmarks) setOf(Bar.DELETE, Bar.SHARE, Bar.FAVORITE, Bar.MORE) else Bar.entries.toSet()
        val enabled = HashSet<Bar>()
        if (entries.isNotEmpty()) {
            val change = entries.all(changeable)
            if (change && !bookmarks) enabled += setOf(Bar.MOVE, Bar.COPY, Bar.DELETE)
            if (change && !bookmarks && entries.size == 1) enabled += Bar.RENAME
            if (bookmarks) enabled += Bar.DELETE
            if (entries.all(shareable)) enabled += Bar.SHARE
            enabled += Bar.FAVORITE
            if (more.isNotEmpty()) enabled += Bar.MORE
        }
        return BarState(shown, enabled intersect shown, entries.isNotEmpty() && entries.all { it.favorite })
    }

    /** The ⋮ menu's items of [offered], in order, that apply to [entries]. */
    fun more(entries: List<BookEntry>, offered: List<MoreAction>, facts: MoreFacts): List<MoreAction> {
        val books = entries.filter { !it.isDir }
        val folders = entries.filter { it.isDir }
        val one = entries.singleOrNull()
        val book = one?.takeIf { !it.isDir && Formats.isOpenable(it.name) }
        return offered.filter { action ->
            when (action) {
                MoreAction.CONTINUE_READING -> one?.isDir == true
                MoreAction.MARK_READ -> books.any { !it.read }
                MoreAction.MARK_UNREAD -> books.any { it.read }
                MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD -> folders.isNotEmpty()
                MoreAction.SET_STARTUP -> one?.isDir == true
                MoreAction.SHOW_IN_FOLDER -> one != null && !facts.browsedHere(one)
                MoreAction.BOOK_INFO -> book != null
                MoreAction.NAME_CHARSET -> book != null && !facts.keepSimple && facts.charsetApplies(book)
                MoreAction.PEEK -> one != null && facts.canPeek(one)
                MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE -> entries.any(facts.rebuildable)
                MoreAction.REMOVE_FROM_HISTORY -> facts.inHistory && entries.isNotEmpty()
                MoreAction.REMOVE_BOOKMARK -> facts.inBookmarks && entries.isNotEmpty()
            }
        }
    }

    /**
     * The floating button with [count] items selected: none selected, the list/grid toggle
     * when [gridToggle]; one, [one]; several, [many]; nothing for a choice that is off or
     * that the bar does not allow now ([bar]).
     */
    fun fab(count: Int, gridToggle: Boolean, one: FabAction, many: FabAction, bar: BarState): Fab? {
        if (count == 0) return if (gridToggle) Fab.GRID_LIST else null
        val choice = if (count == 1) one else many
        fun allowed(b: Bar) = b in bar.enabled
        return when (choice) {
            FabAction.NONE -> null
            FabAction.OPEN -> Fab.OPEN.takeIf { count == 1 }
            FabAction.MOVE -> Fab.MOVE.takeIf { allowed(Bar.MOVE) }
            FabAction.COPY -> Fab.COPY.takeIf { allowed(Bar.COPY) }
            FabAction.DELETE -> Fab.DELETE.takeIf { allowed(Bar.DELETE) }
            FabAction.SHARE -> Fab.SHARE.takeIf { allowed(Bar.SHARE) }
            FabAction.FAVORITE -> Fab.FAVORITE.takeIf { allowed(Bar.FAVORITE) }
        }
    }
}
