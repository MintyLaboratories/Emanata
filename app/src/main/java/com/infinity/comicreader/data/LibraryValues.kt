package com.infinity.comicreader.data

import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Slices
import com.infinity.comicreader.util.Formats
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
        fun of(file: File): FileStat = if (file is RemoteFile) remote(file) else try {
            val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val modified = a.lastModifiedTime().toMillis()
            val created = a.creationTime()?.toMillis()?.takeIf { it > 0 } ?: modified
            FileStat(a.isDirectory, if (a.isDirectory) 0 else a.size(), modified, created)
        } catch (e: Exception) {
            val modified = file.lastModified()
            val dir = file.isDirectory
            FileStat(dir, if (dir) 0 else file.length(), modified, modified)
        }

        /** A share tells creation times, so "Added" sorts by them there. */
        private fun remote(file: RemoteFile): FileStat {
            val dir = file.isDirectory
            val modified = file.lastModified()
            return FileStat(dir, if (dir) 0 else file.length(), modified, file.created().takeIf { it > 0 } ?: modified)
        }
    }
}

/** The total size of everything below a folder. */
object FolderSize {
    /**
     * Blocking; stops early, with what it found, once [alive] turns false. Linked
     * folders below [dir] are not entered, so a link loop cannot keep it going.
     */
    fun total(dir: File, alive: () -> Boolean = { true }): Long =
        dir.walkTopDown()
            .onEnter { it == dir || !Files.isSymbolicLink(it.toPath()) }
            .takeWhile { alive() }.filter { it.isFile }.sumOf { it.length() }
}

/**
 * What a book's contents tell: its page count (-1 unknown), its ComicInfo year (0 none),
 * the reading direction they suggest (null when nothing does) and whether its pages are
 * [slices] of one strip (null until checked), which outrank [detected] as in the reader.
 */
data class BookFacts(
    val pages: Int,
    val year: Int,
    val detected: DirectionDetection.Detected? = null,
    val slices: Slices.Checked? = null
) {
    /** The direction found: top to bottom for slices, else [detected]'s. */
    val direction: ReadingDirection? get() = slices?.found?.direction ?: detected?.direction

    /** The page count with [hidden] pages left out, at least one; -1 unknown. */
    fun shownPages(hidden: Int): Int = if (pages > 0) (pages - hidden).coerceAtLeast(1) else pages
}

/**
 * Sort and filter values of entries: what each entry holds, folder sizes found
 * so far by path, and [facts] read from the books themselves, less the pages each book
 * hides ([hidden] counts them). With [detect], a book whose folder has no direction
 * set reads in the direction its facts suggest.
 */
class EntryValues(
    private val folderSizes: Map<String, Long> = emptyMap(),
    private val detect: Boolean = false,
    private val hidden: (BookEntry) -> Int = { 0 },
    private val facts: (BookEntry) -> BookFacts? = { null }
) : SortValues {
    override fun of(entry: BookEntry, key: SortKey): Long? = when (key) {
        SortKey.YEAR -> (facts(entry)?.year?.takeIf { it > 0 } ?: entry.year.takeIf { it > 0 })?.toLong()
        SortKey.ADDED -> entry.added.takeIf { it > 0 }
        SortKey.ACCESSED -> entry.accessed.takeIf { it > 0 }
        SortKey.SIZE -> if (entry.isDir) folderSizes[entry.path] else entry.size.takeIf { it > 0 }
        SortKey.LENGTH -> (entry.pages.takeIf { it > 0 } ?: facts(entry)?.shownPages(hidden(entry))?.takeIf { it > 0 })?.toLong()
        SortKey.DIRECTION -> direction(entry)?.rank?.toLong()
        SortKey.TYPE -> LibrarySort.typeRank(entry)
        SortKey.NAME -> null
    }

    /** The direction set for the entry's folder, else the detected one when asked for, else the default. */
    private fun direction(entry: BookEntry): ReadingDirection? {
        if (entry.isDir || entry.directionSet || !detect) return entry.direction
        return facts(entry)?.direction ?: entry.direction
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
 * loaded once per listing instead of once per row. Books are known by their [BookId]
 * as the index knows it ([BookIds.known]): a file not hashed yet shows nothing kept for
 * it until its ID is found ([withId]).
 */
class LibrarySnapshot(
    val favoriteFolders: Set<String> = emptySet(),
    val favoriteBooks: Set<BookId> = emptySet(),
    val read: Set<BookId> = emptySet(),
    val history: Map<BookId, HistoryItem> = emptyMap(),
    /** Latest access by path where books were last seen, folders included ([AccessTimes]). */
    val access: Map<String, Long> = emptyMap(),
    /** Latest access of each book in the history or bookmarks. */
    val accessById: Map<BookId, Long> = emptyMap(),
    /** Where each book with anything kept was last seen. */
    val seen: Map<BookId, Library.Seen> = emptyMap(),
    /** Directions set for folders, by folder path. */
    private val directions: Map<String, ReadingDirection> = emptyMap(),
    private val defaultDirection: ReadingDirection = ReadingDirection.LTR
) {
    /** The direction a folder's books read in, or a book in its folder. */
    fun directionOf(path: String, isDir: Boolean): ReadingDirection = directions[folderOf(path, isDir)] ?: defaultDirection

    /** True when the direction of [path] was set for its folder. */
    fun isDirectionSet(path: String, isDir: Boolean): Boolean = folderOf(path, isDir) in directions

    private fun folderOf(path: String, isDir: Boolean) =
        if (isDir) path else path.substring(0, path.indexOfLast { isSeparator(it) }.coerceAtLeast(0))

    /** Books listed at another path than they were last seen at, as found by [entry] and [withId]; the caller tells the library ([Library.seenAll]). */
    val sighted: MutableMap<BookId, String> = java.util.concurrent.ConcurrentHashMap()

    /** True when anything is kept for book [id]. */
    fun keeps(id: BookId): Boolean = id in seen

    /**
     * The entry for [file], a folder holding [children] (the items a user would see)
     * or a book; [name] is what it is listed as. A folder of loose page images has
     * their count as its pages.
     */
    fun entry(file: File, children: List<File>?, name: String = file.name): BookEntry {
        val stat = FileStat.of(file)
        val path = file.absolutePath
        val isDir = stat.isDirectory
        val books = children?.filter { Formats.isOpenable(it.name) }
        val names = books?.map { it.name }.orEmpty()
        // By path alone: a stat per child would slow every listing of folders.
        val childIds = if (read.isEmpty() && history.isEmpty()) emptyMap() else children.orEmpty().associateWith { BookIds.last(it.absolutePath) }
        val entry = BookEntry(
            file = file,
            name = name,
            isDir = isDir,
            type = if (isDir) EntryType.FOLDER else Formats.typeOf(file.name),
            size = stat.size,
            lastModified = stat.lastModified,
            childCount = children?.size ?: -1,
            readCount = children?.count { childIds[it] in read } ?: -1,
            pages = if (isDir) looseImages(names) else -1,
            favorite = isDir && path in favoriteFolders,
            added = stat.created,
            accessed = access[path] ?: 0,
            year = if (isDir) Years.ofFolder(file.name, children.orEmpty().map { it.name }) else Years.fromName(file.name),
            direction = directionOf(path, isDir),
            directionSet = isDirectionSet(path, isDir),
            bookCount = books?.size ?: -1,
            begunCount = books?.count { childIds[it]?.let { id -> id in history || id in read } == true } ?: -1
        )
        if (isDir) return entry
        val id = BookIds.known(path, stat.size, stat.lastModified) ?: return entry
        return withId(entry, id)
    }

    /** Book [entry] as book [id]: its favorite, read mark, history and access, noting where it is seen now. */
    fun withId(entry: BookEntry, id: BookId): BookEntry {
        val h = history[id]
        seen[id]?.let { if (it.path != entry.path) sighted[id] = entry.path }
        return entry.copy(
            id = id,
            favorite = id in favoriteBooks,
            read = id in read,
            begun = h != null,
            pages = if (h != null && h.total > 0) h.total else entry.pages,
            progressPage = h?.page ?: entry.progressPage,
            accessed = accessById[id] ?: entry.accessed
        )
    }

    /** [sighted] as references, for [Library.seenAll]. */
    fun sightings(): List<BookRef> = sighted.map { (id, path) -> BookRef(id, path) }

    /**
     * The entry for [file] as listed: a folder with the items a user would see in it, or
     * a book. A folder on a share made not to ask the network is not looked into.
     */
    fun entry(file: File, showHidden: Boolean): BookEntry {
        val looks = file.isDirectory && (file !is RemoteFile || file.asksNetwork)
        return entry(file, if (looks) visibleChildren(file, showHidden) else null)
    }

    companion object {
        /** Items a user would see inside [dir]: sub-folders and openable files, minus hidden ones unless [showHidden]. */
        fun visibleChildren(dir: File, showHidden: Boolean): List<File> = dir.listFiles()?.let { visibleOf(it.asList(), showHidden) } ?: emptyList()

        /** The items of [files] a user would see: sub-folders and openable files, minus hidden ones unless [showHidden]. */
        fun visibleOf(files: List<File>, showHidden: Boolean): List<File> =
            files.filter { (showHidden || !it.name.startsWith(".")) && (it.isDirectory || Formats.isOpenable(it.name)) }

        /** The page count of a folder of loose page images named [names], or -1 when it is not one. */
        internal fun looseImages(names: List<String>): Int =
            if (FavoriteClassifier.isLooseComic(names)) names.count { Formats.isImageName(it) } else -1

        fun load(library: Library, prefs: Prefs): LibrarySnapshot {
            val history = library.history()
            val bookmarks = library.bookmarks()
            val favorites = library.favoriteItems()
            val byId = HashMap<BookId, Long>()
            for ((id, time) in history.map { it.id to it.time } + bookmarks.map { it.id to it.time }) {
                if (time > (byId[id] ?: 0)) byId[id] = time
            }
            return LibrarySnapshot(
                favoriteFolders = favorites.filter { it.id == null }.mapTo(HashSet()) { it.path },
                favoriteBooks = favorites.mapNotNullTo(HashSet()) { it.id },
                read = library.readSet(),
                history = history.associateBy { it.id },
                access = AccessTimes.of(
                    history.asSequence().map { it.path to it.time } + bookmarks.asSequence().map { it.path to it.time }
                ),
                accessById = byId,
                seen = library.seenAt(),
                directions = if (prefs.perSeries) library.folderDirections() else emptyMap(),
                defaultDirection = ReadingDirection.of(prefs.readingDir) ?: ReadingDirection.LTR
            )
        }
    }
}
