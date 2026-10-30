package com.infinity.comicreader.data

import java.io.File

enum class EntryType { FOLDER, IMAGE, ARCHIVE, PDF, OTHER }

/**
 * A single row in the library. [childCount] is used by folders, [pages] holds a
 * known page count, [progressPage] the last read page (-1 when unknown) and the
 * flags mirror the favorites / read-mark stores so the adapter can render badges
 * without touching disk.
 */
data class BookEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val type: EntryType,
    val size: Long,
    val lastModified: Long,
    val childCount: Int = -1,
    val pages: Int = -1,
    val progressPage: Int = -1,
    val favorite: Boolean = false,
    val read: Boolean = false,
    /** For folders: how many direct children are already marked read (-1 = unknown). */
    val readCount: Int = -1,
    val subtitle: String? = null,
    /** Shown instead of [name] where same-name favorites need telling apart. */
    val label: FavoriteLabel? = null,
    /** For bookmarks: the page drawn in front of the cover (-1 = none). */
    val bookmarkPage: Int = -1,
    /** True once the book was opened (it has a history entry). */
    val begun: Boolean = false,
    /** When it was added: creation time where known, else modification time (0 = unknown). */
    val added: Long = 0,
    /** Its latest access in the app's history or bookmarks; a folder's is the latest inside it (0 = never). */
    val accessed: Long = 0,
    /** Its year as its name gives it; a folder's is its earliest child's (0 = none). */
    val year: Int = 0,
    /** The direction it reads in: set for its folder, or the default (null = unknown). */
    val direction: ReadingDirection? = null,
    /** True when [direction] was set for its folder rather than being the default. */
    val directionSet: Boolean = false,
    /** For folders: how many direct children are books or images (-1 = unknown). */
    val bookCount: Int = -1,
    /** For folders: how many of those books were opened or read (-1 = unknown). */
    val begunCount: Int = -1,
    /** For files: the [BookId] as known when listed; null until found. */
    val id: BookId? = null,
    /** In Favorites, History and Bookmarks: whether it is where it was last seen. */
    val presence: Presence = Presence.PRESENT
) {
    val path: String get() = file.absolutePath
}

/** A book as the library keeps it: its [id], seen at [path]. */
data class BookRef(val id: BookId, val path: String)

/** Book [id], last seen at [path] (as [name]), read up to [page] of [total] at [time]. */
data class HistoryItem(
    val path: String,
    val name: String,
    val page: Int,
    val total: Int,
    val time: Long,
    val id: BookId
) {
    val ref: BookRef get() = BookRef(id, path)
}

/** A bookmark on [page] of book [id], last seen at [path] (as [name]). */
data class Bookmark(
    val path: String,
    val name: String,
    val page: Int,
    val note: String,
    val time: Long,
    val id: BookId
) {
    val ref: BookRef get() = BookRef(id, path)
}

/** Aggregated reading statistics. */
data class ReadingStats(
    val seconds: Long,
    val pages: Long,
    val chapters: Int,
    val daysRead: Int,
    val streak: Int,
    val bestStreak: Int
)
