package com.infinity.comicreader.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.IdentityHashMap

/** The year a name gives as `(2014)`. */
object Years {
    private val PATTERN = Regex("""\((\d{4})\)""")
    private val PLAUSIBLE = 1800..2200

    /** The first plausible `(yyyy)` in [name], or 0 when it has none. */
    fun fromName(name: String): Int =
        PATTERN.findAll(name).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in PLAUSIBLE } ?: 0

    /** A folder's year: its earliest child's from [childNames], else its own from [name]. */
    fun ofFolder(name: String, childNames: List<String>): Int =
        childNames.map { fromName(it) }.filter { it > 0 }.minOrNull() ?: fromName(name)
}

/** When books were last accessed, by path; each folder holds the latest time of anything inside it. */
object AccessTimes {
    fun of(times: Sequence<Pair<String, Long>>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((path, time) in times) {
            var p: String? = path
            while (!p.isNullOrEmpty()) {
                val known = out[p]
                // Folders above a later time already hold at least as much.
                if (known != null && known >= time) break
                out[p] = time
                p = parentOf(p)
            }
        }
        return out
    }

    private fun parentOf(path: String): String? {
        val end = path.trimEnd('/', '\\')
        val cut = end.indexOfLast { isSeparator(it) }
        return if (cut <= 0) null else end.substring(0, cut)
    }
}

/** One stat of a file: kind, size, modification and creation times. */
class FileStat(val isDirectory: Boolean, val size: Long, val lastModified: Long, val created: Long) {
    companion object {
        /** Read in one call where the platform allows; creation time falls back to modification time. */
        fun of(file: File): FileStat = try {
            val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val modified = a.lastModifiedTime().toMillis()
            val created = a.creationTime()?.toMillis()?.takeIf { it > 0 } ?: modified
            FileStat(a.isDirectory, if (a.isDirectory) 0 else a.size(), modified, created)
        } catch (e: Exception) {
            val modified = file.lastModified()
            val dir = file.isDirectory
            FileStat(dir, if (dir) 0 else file.length(), modified, modified)
        }
    }
}

/** What a book's contents tell: its page count (-1 unknown) and its ComicInfo year (0 none). */
data class BookFacts(val pages: Int, val year: Int)

/**
 * Sort and filter values of entries: what each entry holds, folder sizes found
 * so far by path, and [facts] read from the books themselves.
 */
class EntryValues(
    private val folderSizes: Map<String, Long> = emptyMap(),
    private val facts: (BookEntry) -> BookFacts? = { null }
) : SortValues {
    override fun of(entry: BookEntry, key: SortKey): Long? = when (key) {
        SortKey.YEAR -> (facts(entry)?.year?.takeIf { it > 0 } ?: entry.year.takeIf { it > 0 })?.toLong()
        SortKey.ADDED -> entry.added.takeIf { it > 0 }
        SortKey.ACCESSED -> entry.accessed.takeIf { it > 0 }
        SortKey.SIZE -> if (entry.isDir) folderSizes[entry.path] else entry.size.takeIf { it > 0 }
        SortKey.LENGTH -> (entry.pages.takeIf { it > 0 } ?: facts(entry)?.pages?.takeIf { it > 0 })?.toLong()
        SortKey.DIRECTION -> entry.direction?.ordinal?.toLong()
        SortKey.TYPE -> LibrarySort.typeRank(entry)
        SortKey.NAME -> null
    }

    /** These values frozen for [entries] and [keys], so later findings do not reorder a list already shown. */
    fun frozen(entries: Collection<BookEntry>, keys: Set<SortKey>): SortValues {
        val table = IdentityHashMap<BookEntry, Map<SortKey, Long?>>(entries.size)
        for (e in entries) table[e] = keys.associateWith { of(e, it) }
        return SortValues { e, key -> if (key in keys) table[e]?.get(key) else of(e, key) }
    }
}

/**
 * Favorites, read marks, history, access times and the directions set for folders,
 * loaded once per listing instead of once per row.
 */
class LibrarySnapshot(
    val favorites: Set<String> = emptySet(),
    val read: Set<String> = emptySet(),
    val history: Map<String, HistoryItem> = emptyMap(),
    /** Latest access by path, folders included ([AccessTimes]). */
    val access: Map<String, Long> = emptyMap(),
    /** Directions set for folders, by folder path. */
    private val directions: Map<String, ReadingDirection> = emptyMap(),
    private val defaultDirection: ReadingDirection = ReadingDirection.LTR
) {
    /** The direction a folder's books read in, or a book in its folder. */
    fun directionOf(path: String, isDir: Boolean): ReadingDirection {
        val folder = if (isDir) path else path.substring(0, path.indexOfLast { isSeparator(it) }.coerceAtLeast(0))
        return directions[folder] ?: defaultDirection
    }

    /**
     * The entry for [file], a folder holding [children] (the items a user would see)
     * or a book; [name] is what it is listed as.
     */
    fun entry(file: File, children: List<File>?, name: String = file.name): BookEntry {
        val stat = FileStat.of(file)
        val path = file.absolutePath
        val isDir = stat.isDirectory
        val history = if (isDir) null else history[path]
        return BookEntry(
            file = file,
            name = name,
            isDir = isDir,
            type = if (isDir) EntryType.FOLDER else com.infinity.comicreader.util.Formats.typeOf(file.name),
            size = stat.size,
            lastModified = stat.lastModified,
            childCount = children?.size ?: -1,
            readCount = children?.count { it.absolutePath in read } ?: -1,
            pages = history?.total ?: -1,
            progressPage = history?.page ?: -1,
            favorite = path in favorites,
            read = path in read,
            begun = history != null,
            added = stat.created,
            accessed = access[path] ?: 0,
            year = if (isDir) Years.ofFolder(file.name, children.orEmpty().map { it.name }) else Years.fromName(file.name),
            direction = directionOf(path, isDir)
        )
    }

    companion object {
        fun load(library: Library, prefs: Prefs): LibrarySnapshot {
            val history = library.history()
            val bookmarks = library.bookmarks()
            return LibrarySnapshot(
                favorites = library.favoriteSet(),
                read = library.readSet(),
                history = history.associateBy { it.path },
                access = AccessTimes.of(
                    history.asSequence().map { it.path to it.time } + bookmarks.asSequence().map { it.path to it.time }
                ),
                directions = if (prefs.perSeries) library.seriesDirections() else emptyMap(),
                defaultDirection = ReadingDirection.of(prefs.readingDir) ?: ReadingDirection.LTR
            )
        }
    }
}
