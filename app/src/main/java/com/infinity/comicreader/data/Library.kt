package com.infinity.comicreader.data

import android.content.Context
import android.content.SharedPreferences
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.reader.HiddenPages
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.util.Formats
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Favorites, read marks, reading history, bookmarks, per-folder layouts, per-book
 * color adjustments, hidden pages and file name charsets, and reading statistics, persisted
 * as JSON documents in SharedPreferences.
 *
 * Everything kept for a book is kept by its [BookId], so it follows the book moved,
 * renamed or copied, and identical files share it. Each such book also keeps the path
 * and [BookLocation] it was last seen at ([seenAt]), which the lists show and open.
 * Folders (favorite folders, per-folder layouts) are kept by path, and re-pointed when
 * the app moves them ([replacePath]).
 *
 * Every read tolerates a corrupted document (it yields an empty value instead of
 * throwing) and every read-modify-write runs under one process-wide lock, because
 * the reader, the library and background scans each create their own instance.
 */
class Library(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        BookIds.init(context)
    }

    // ---------------- where books were last seen ----------------
    /** Where each book with anything kept was last seen, by ID. */
    fun seenAt(): Map<BookId, Seen> {
        val raw = sp.getString(KEY_BOOKS, "{}") ?: "{}"
        seenParsed?.let { (from, map) -> if (from == raw) return map }
        val all = parse(raw)
        val out = HashMap<BookId, Seen>()
        all.keys().forEach { k ->
            val id = BookId.parse(k) ?: return@forEach
            val o = all.optJSONObject(k) ?: return@forEach
            val path = o.optString("path").ifEmpty { return@forEach }
            out[id] = Seen(path, o.optString("loc").ifEmpty { BookLocation.of(path) })
        }
        seenParsed = raw to out
        return out
    }

    /** The path book [id] was last seen at; null when nothing is kept for it. */
    fun pathOf(id: BookId): String? = seenAt()[id]?.path

    /** Notes that [book] was seen at its path, where anything is kept for it; nothing is written when it was seen there last. */
    fun seen(book: BookRef) = seenAll(listOf(book))

    /** [seen] for each of [books] at once. */
    fun seenAll(books: Collection<BookRef>) {
        if (books.isEmpty()) return
        val known = seenAt()
        val moved = books.filter { b -> known[b.id]?.let { it.path != b.path } == true }
        if (moved.isEmpty()) return
        synchronized(LOCK) {
            val all = readObject(KEY_BOOKS)
            for (b in moved) if (all.has(b.id.hex)) putSeen(all, b.id, b.path)
            sp.edit().putString(KEY_BOOKS, all.toString()).apply()
        }
    }

    /** Remembers where [book] is seen as something is kept for it. Called under [LOCK]. */
    private fun keep(book: BookRef) {
        val all = readObject(KEY_BOOKS)
        if (putSeen(all, book.id, book.path)) sp.edit().putString(KEY_BOOKS, all.toString()).apply()
    }

    /**
     * Notes in [all] that book [id] was seen at [path], and its size where the index knows
     * it; what else its entry holds stays. False when nothing changed.
     */
    private fun putSeen(all: JSONObject, id: BookId, path: String): Boolean {
        val entry = all.optJSONObject(id.hex) ?: JSONObject()
        val moved = entry.optString("path") != path
        val size = if (entry.has(SIZE)) null else BookIds.sizeAt(path, id)
        if (!moved && size == null) return false
        if (moved) entry.put("path", path).put("loc", BookLocation.of(path))
        size?.let { entry.put(SIZE, it) }
        all.put(id.hex, entry)
        return true
    }

    /** The size in bytes of each book whose size is noted (every copy of a book has the size its ID was made with). */
    fun sizes(): Map<BookId, Long> {
        val all = readObject(KEY_BOOKS)
        val out = HashMap<BookId, Long>()
        all.keys().forEach { k ->
            val id = BookId.parse(k) ?: return@forEach
            all.optJSONObject(k)?.optLong(SIZE, -1)?.takeIf { it >= 0 }?.let { out[id] = it }
        }
        return out
    }

    // ---------------- favorites ----------------
    /** Every favorite in the order added, newest first: a folder by its path, a book by where it was last seen. */
    fun favoriteItems(): List<Favorite> {
        val seen = seenAt()
        return readStringList(KEY_FAVORITES).mapNotNull { token ->
            val id = bookToken(token)
            when {
                id != null -> seen[id]?.let { Favorite(it.path, id) }
                isFolderToken(token) -> Favorite(token, null)
                else -> null
            }
        }
    }

    /** The paths of every favorite, newest first: folders, and books where last seen. */
    fun favorites(): MutableList<String> = favoriteItems().mapTo(ArrayList()) { it.path }

    /** The favorite folders. */
    fun favoriteFolders(): Set<String> = favoriteItems().filter { it.id == null }.mapTo(HashSet()) { it.path }

    /** The favorite books. */
    fun favoriteBooks(): Set<BookId> = readStringList(KEY_FAVORITES).mapNotNullTo(HashSet()) { bookToken(it) }

    fun isFavoriteFolder(path: String): Boolean = path in readStringList(KEY_FAVORITES)

    fun isFavorite(id: BookId): Boolean = tokenOf(id) in readStringList(KEY_FAVORITES)

    /** Returns true when folder [path] was added, false when it was removed. */
    fun toggleFavoriteFolder(path: String): Boolean = synchronized(LOCK) { toggle(path) }

    /** Returns true when [book] was added, false when it was removed. */
    fun toggleFavorite(book: BookRef): Boolean = synchronized(LOCK) {
        toggle(tokenOf(book.id)).also { if (it) keep(book) }
    }

    /** Makes [book] a favorite, or not, whatever it was. */
    fun setFavorite(book: BookRef, favorite: Boolean) = synchronized(LOCK) {
        val token = tokenOf(book.id)
        val list = readStringList(KEY_FAVORITES)
        val changed = if (favorite) (token !in list).also { if (it) list.add(0, token) } else list.remove(token)
        if (changed) writeStringList(KEY_FAVORITES, list)
        if (favorite) keep(book)
    }

    private fun toggle(token: String): Boolean {
        val list = readStringList(KEY_FAVORITES)
        val added = if (list.remove(token)) false else {
            list.add(0, token)
            true
        }
        writeStringList(KEY_FAVORITES, list)
        return added
    }

    // ---------------- read marks ----------------
    fun readSet(): MutableSet<BookId> = readIds(KEY_READ)

    fun isRead(id: BookId): Boolean = id in readSet()

    fun setRead(book: BookRef, read: Boolean) = setReadMany(listOf(book), read)

    fun setReadMany(books: Collection<BookRef>, read: Boolean) {
        synchronized(LOCK) {
            val set = readSet()
            val changed = if (read) set.addAll(books.map { it.id }) else set.removeAll(books.map { it.id }.toSet())
            if (!changed) return@synchronized
            writeIds(KEY_READ, set)
            if (read) keepAll(books)
        }
    }

    private fun keepAll(books: Collection<BookRef>) {
        val all = readObject(KEY_BOOKS)
        var changed = false
        for (b in books) if (putSeen(all, b.id, b.path)) changed = true
        if (changed) sp.edit().putString(KEY_BOOKS, all.toString()).apply()
    }

    // ---------------- spread pairing ----------------
    /** True when [id]'s two-page pairing is shifted by one page. */
    fun isShifted(id: BookId): Boolean = isShiftedByNames(id) || id in readIds(KEY_SHIFTED_SEAMS)

    /**
     * True when [id]'s shift was chosen before seams paired pages: it flips the pairing
     * page names give, not the one seams correct.
     */
    fun isShiftedByNames(id: BookId): Boolean = id in readIds(KEY_SHIFTED)

    /** Shifts [book]'s pairing, or not, from the pairing shown now: seams and names alike. */
    fun setShifted(book: BookRef, shifted: Boolean) {
        synchronized(LOCK) {
            val byNames = readIds(KEY_SHIFTED)
            if (byNames.remove(book.id)) writeIds(KEY_SHIFTED, byNames)
            val set = readIds(KEY_SHIFTED_SEAMS)
            val changed = if (shifted) set.add(book.id) else set.remove(book.id)
            if (changed) writeIds(KEY_SHIFTED_SEAMS, set)
            if (shifted) keep(book)
        }
    }

    /** True when [id]'s seams are never checked, nor pair, merge or join its pages. */
    fun ignoresSeams(id: BookId): Boolean = id in readIds(KEY_IGNORE_SEAMS)

    fun setIgnoreSeams(book: BookRef, ignoreSeams: Boolean) {
        synchronized(LOCK) {
            val set = readIds(KEY_IGNORE_SEAMS)
            val changed = if (ignoreSeams) set.add(book.id) else set.remove(book.id)
            if (changed) writeIds(KEY_IGNORE_SEAMS, set)
            if (ignoreSeams) keep(book)
        }
    }

    // ---------------- history ----------------
    /** The history, latest first, each book at the path it was last seen at. */
    fun history(): MutableList<HistoryItem> {
        val arr = readArray(KEY_HISTORY)
        val seen = seenAt()
        val out = ArrayList<HistoryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = BookId.parse(o.optString("id")) ?: continue
            val path = seen[id]?.path ?: continue
            out.add(
                HistoryItem(
                    path = path,
                    name = File(path).name.ifEmpty { o.optString("name") },
                    page = o.optInt("page"),
                    total = o.optInt("total"),
                    time = o.optLong("time"),
                    id = id
                )
            )
        }
        return out
    }

    fun historyMap(): Map<BookId, HistoryItem> = history().associateBy { it.id }

    fun historyFor(id: BookId): HistoryItem? = history().firstOrNull { it.id == id }

    fun pageFor(id: BookId): Int = historyFor(id)?.page ?: 0

    /** Puts [item] at the top of the history, its book seen at its path. */
    fun addHistory(item: HistoryItem) {
        synchronized(LOCK) {
            keep(BookRef(item.id, item.path))
            val list = ArrayList(history().filterNot { it.id == item.id })
            list.add(0, item)
            while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
            writeHistory(list)
        }
    }

    fun clearHistory() = sp.edit().putString(KEY_HISTORY, "[]").apply()

    /**
     * Calls [onChange] on the main thread after each write to the history, from any
     * screen of this process, until the returned handle is closed. The caller keeps the
     * handle: SharedPreferences holds its listener only weakly.
     */
    fun watchHistory(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY_HISTORY) onChange() }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // ---------------- bookmarks ----------------
    /** The bookmarks, latest first, each book at the path it was last seen at. */
    fun bookmarks(): MutableList<Bookmark> {
        val arr = readArray(KEY_BOOKMARKS)
        val seen = seenAt()
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = BookId.parse(o.optString("id")) ?: continue
            val path = seen[id]?.path ?: continue
            out.add(
                Bookmark(
                    path = path,
                    name = File(path).name.ifEmpty { o.optString("name") },
                    page = o.optInt("page"),
                    note = o.optString("note"),
                    time = o.optLong("time"),
                    id = id
                )
            )
        }
        return out
    }

    fun bookmarksFor(id: BookId): List<Bookmark> =
        bookmarks().filter { it.id == id }.sortedBy { it.page }

    fun isBookmarked(id: BookId, page: Int): Boolean =
        bookmarks().any { it.id == id && it.page == page }

    fun addBookmark(bookmark: Bookmark) {
        synchronized(LOCK) {
            keep(BookRef(bookmark.id, bookmark.path))
            val list = ArrayList(bookmarks().filterNot { it.id == bookmark.id && it.page == bookmark.page })
            list.add(0, bookmark)
            writeBookmarks(list)
        }
    }

    fun removeBookmark(id: BookId, page: Int) {
        synchronized(LOCK) {
            writeBookmarks(bookmarks().filterNot { it.id == id && it.page == page })
        }
    }

    fun clearBookmarks() = sp.edit().putString(KEY_BOOKMARKS, "[]").apply()

    // ---------------- hidden pages ----------------
    /** The pages left out of book [id] ("Hide this page"), by book index. */
    fun hiddenPages(id: BookId?): Set<Int> = id?.let { hiddenAll().byId[it] }.orEmpty()

    /** The pages every book leaves out, by ID and by where it was last seen; parsed once per change of what is stored. */
    fun hiddenAll(): Hidden {
        val raw = sp.getString(KEY_HIDDEN, "{}") ?: "{}"
        val seen = seenAt()
        hiddenParsed?.let { (from, hidden) -> if (from == raw && hidden.seen === seen) return hidden }
        val all = parse(raw)
        val byId = HashMap<BookId, Set<Int>>()
        all.keys().forEach { k ->
            val id = BookId.parse(k) ?: return@forEach
            parseHidden(all.optJSONArray(k)).takeIf { it.isNotEmpty() }?.let { byId[id] = it }
        }
        val byPath = HashMap<String, Set<Int>>()
        for ((id, pages) in byId) seen[id]?.let { byPath[it.path] = pages }
        return Hidden(byId, byPath, seen).also { hiddenParsed = raw to it }
    }

    /** How many pages each book leaves out, by ID. */
    fun hiddenCounts(): Map<BookId, Int> = hiddenAll().byId.mapValues { it.value.size }

    /**
     * Leaves [hidden] out of [book], of [pageCount] pages. Its history and bookmarks count
     * pages as shown, so they move to the same pages counted anew; a bookmark on a page now
     * hidden moves to the next page shown, or joins its note to the bookmark already there.
     */
    fun setHiddenPages(book: BookRef, hidden: Set<Int>, pageCount: Int) {
        synchronized(LOCK) {
            val id = book.id
            val old = hiddenPages(id)
            val new = hidden.filter { it in 0 until pageCount }.toSortedSet()
            if (old == new) return@synchronized
            keep(book)
            val all = readObject(KEY_HIDDEN)
            if (new.isEmpty()) all.remove(id.hex) else all.put(id.hex, JSONArray(new.toList()))
            sp.edit().putString(KEY_HIDDEN, all.toString()).apply()
            val last = (pageCount - new.size - 1).coerceAtLeast(0)
            // A bookmark on a page still shown is the one kept where others join it.
            renumber(id, last, { HiddenPages.remap(it, old, new) }) { HiddenPages.toBook(it.page, old) !in new }
        }
    }

    /**
     * Moves the hidden pages, history and bookmarks of book [id] with its pages, as when
     * their order changes: book page `i` went to [moves]`[i]`, or is gone where that is -1
     * (or missing), of [pageCount] pages now. History and bookmarks on a page gone stay at
     * the same number.
     */
    fun movePages(id: BookId, moves: IntArray, pageCount: Int) {
        synchronized(LOCK) {
            val old = hiddenPages(id)
            val new = old.mapNotNull { h -> moves.getOrNull(h)?.takeIf { it in 0 until pageCount } }.toSortedSet()
            if (old != new) {
                val all = readObject(KEY_HIDDEN)
                if (new.isEmpty()) all.remove(id.hex) else all.put(id.hex, JSONArray(new.toList()))
                sp.edit().putString(KEY_HIDDEN, all.toString()).apply()
            }
            val last = (pageCount - new.size - 1).coerceAtLeast(0)
            renumber(id, last, { shown ->
                val to = moves.getOrNull(HiddenPages.toBook(shown, old)) ?: -1
                if (to in 0 until pageCount) HiddenPages.toShown(to, new) else shown
            }) { true }
        }
    }

    /**
     * Moves the history and bookmarks of book [id], counted in pages shown, to the page
     * [remap] gives, at most [last]. Bookmarks landing on one page become one: the first
     * that [keeps], else the first, its note joined by the others'. Called under [LOCK].
     */
    private fun renumber(id: BookId, last: Int, remap: (Int) -> Int, keeps: (Bookmark) -> Boolean) {
        fun moved(page: Int) = remap(page).coerceIn(0, last)
        val history = history()
        if (history.any { it.id == id }) {
            writeHistory(history.map { h -> if (h.id != id) h else h.copy(page = moved(h.page), total = last + 1) })
        }
        val marks = bookmarks()
        if (marks.none { it.id == id }) return
        val merged = marks.filter { it.id == id }
            .groupBy { moved(it.page) }
            .mapValues { (page, group) ->
                val keep = group.firstOrNull(keeps) ?: group.first()
                val notes = (listOf(keep) + (group - keep)).map { it.note }.filter { it.isNotBlank() }.distinct()
                keep.copy(page = page, note = notes.joinToString("\n"))
            }
        val written = HashSet<Int>()
        writeBookmarks(
            marks.mapNotNull { b ->
                if (b.id != id) return@mapNotNull b
                val page = moved(b.page)
                if (written.add(page)) merged.getValue(page) else null
            }
        )
    }

    /** Shows every hidden page again, moving history and bookmarks back to book pages. */
    private fun showAllPages() {
        val byId = hiddenAll().byId
        if (byId.isEmpty()) return
        writeHistory(
            history().map { h ->
                val old = byId[h.id] ?: return@map h
                h.copy(page = HiddenPages.toBook(h.page, old), total = if (h.total > 0) h.total + old.size else h.total)
            }
        )
        writeBookmarks(
            bookmarks().map { b -> byId[b.id]?.let { b.copy(page = HiddenPages.toBook(b.page, it)) } ?: b }
        )
        sp.edit().putString(KEY_HIDDEN, "{}").apply()
    }

    private fun parseHidden(arr: JSONArray?): Set<Int> {
        if (arr == null) return emptySet()
        val out = HashSet<Int>()
        for (i in 0 until arr.length()) arr.optInt(i, -1).takeIf { it >= 0 }?.let { out.add(it) }
        return out
    }

    // ---------------- path maintenance (rename / move / delete) ----------------
    private fun under(path: String, root: String) = BookIndex.under(path, root)

    /**
     * Re-points what is kept by path after a file or folder was renamed or moved by the app:
     * favorite folders, folder layouts, where books inside were last seen, and their known IDs.
     */
    fun replacePath(old: String, new: String) {
        synchronized(LOCK) {
            fun remap(p: String) = if (under(p, old)) new + p.removePrefix(old) else p
            writeStringList(KEY_FAVORITES, readStringList(KEY_FAVORITES).map { if (isFolderToken(it)) remap(it) else it })
            val series = readObject(KEY_SERIES)
            val moved = JSONObject()
            series.keys().forEach { k -> moved.put(remap(k), series.get(k)) }
            sp.edit().putString(KEY_SERIES, moved.toString()).apply()
            val books = readObject(KEY_BOOKS)
            for (k in books.keys().asSequence().toList()) {
                val path = books.optJSONObject(k)?.optString("path") ?: continue
                if (under(path, old)) books.optJSONObject(k)?.put("path", remap(path))?.put("loc", BookLocation.of(remap(path)))
            }
            sp.edit().putString(KEY_BOOKS, books.toString()).apply()
        }
        BookIds.moved(old, new)
    }

    /**
     * Forgets the favorites, history and bookmarks under [root]: folders there, and books last
     * seen there. A book with a copy elsewhere ([BookIds.elsewhere]) keeps them, seen at the copy.
     */
    fun removeListsUnder(root: String) {
        val there = seenAt().filterValues { under(it.path, root) }.keys
        val copies = BookIds.elsewhere(there, root)
        synchronized(LOCK) {
            if (copies.isNotEmpty()) {
                val books = readObject(KEY_BOOKS)
                for ((id, path) in copies) if (books.has(id.hex)) putSeen(books, id, path)
                sp.edit().putString(KEY_BOOKS, books.toString()).apply()
            }
            val gone = there - copies.keys
            writeStringList(KEY_FAVORITES, readStringList(KEY_FAVORITES).filterNot { t ->
                bookToken(t)?.let { it in gone } ?: under(t, root)
            })
            writeHistory(history().filterNot { it.id in gone })
            writeBookmarks(bookmarks().filterNot { it.id in gone })
        }
    }

    /**
     * After a file or folder was deleted (or replaced) by the app: forgets what is kept for
     * folders there, and the favorites, history and bookmarks of books last seen there. What
     * else books keep (read marks, hidden pages, their own settings) stays with their IDs,
     * for copies elsewhere.
     */
    fun removePathsUnder(root: String) {
        synchronized(LOCK) {
            val series = readObject(KEY_SERIES)
            val kept = JSONObject()
            series.keys().forEach { k -> if (!under(k, root)) kept.put(k, series.get(k)) }
            sp.edit().putString(KEY_SERIES, kept.toString()).apply()
        }
        removeListsUnder(root)
        BookIds.removed(root)
    }

    // ---------------- per-folder reader layouts ----------------
    /** What [folder] keeps of the reader's layout, or null when it follows the defaults. */
    fun folderLayout(folder: String): FolderLayout? =
        readObject(KEY_SERIES).optJSONObject(folder)?.let { FolderLayout.decode(it) }

    /** The reading direction set for each folder that has one. */
    fun folderDirections(): Map<String, ReadingDirection> {
        val all = readObject(KEY_SERIES)
        val out = HashMap<String, ReadingDirection>()
        all.keys().forEach { k ->
            val layout = all.optJSONObject(k)?.let { FolderLayout.decode(it) } ?: return@forEach
            if (layout.dirChosen) out[k] = ReadingDirection.of(layout.dir) ?: ReadingDirection.LTR
        }
        return out
    }

    /** Keeps [layout] for [folder], or forgets the folder's when null. */
    fun setFolderLayout(folder: String, layout: FolderLayout?) {
        synchronized(LOCK) {
            val all = readObject(KEY_SERIES)
            if (layout == null) all.remove(folder) else all.put(folder, layout.encode())
            sp.edit().putString(KEY_SERIES, all.toString()).apply()
        }
    }

    /**
     * Brings folder layouts older versions wrote into today's form ([FolderLayout.migrate]),
     * and retired modes into today's layout and direction ([FolderLayout.decode]).
     */
    fun migrateFolderLayouts(defaults: ReaderLayout) {
        synchronized(LOCK) {
            val all = readObject(KEY_SERIES)
            var changed = false
            for (k in all.keys().asSequence().toList()) {
                val o = all.optJSONObject(k) ?: continue
                val now = when {
                    FolderLayout.isLegacy(o) -> FolderLayout.migrate(o, defaults)
                    FolderLayout.hasRetiredMode(o) -> FolderLayout.decode(o, defaults)
                    else -> continue
                }
                all.put(k, now.encode())
                changed = true
            }
            if (changed) sp.edit().putString(KEY_SERIES, all.toString()).apply()
        }
    }

    // ---------------- per-book color adjustments ----------------
    /** The adjustments set for book [id], or null when it uses the defaults. */
    fun adjustmentsFor(id: BookId): Adjustments? {
        val o = readObject(KEY_ADJUST).optJSONObject(id.hex) ?: return null
        return Adjustments(o.optInt("b"), o.optInt("c"), o.optInt("s")).coerced()
    }

    /** Saves [adjustments] for [book], or returns it to the defaults when null. */
    fun setAdjustments(book: BookRef, adjustments: Adjustments?) {
        synchronized(LOCK) {
            val all = readObject(KEY_ADJUST)
            if (adjustments == null) {
                all.remove(book.id.hex)
            } else {
                val a = adjustments.coerced()
                all.put(book.id.hex, JSONObject().put("b", a.brightness).put("c", a.contrast).put("s", a.saturation))
                keep(book)
            }
            sp.edit().putString(KEY_ADJUST, all.toString()).apply()
        }
    }

    // ---------------- per-book file name charsets ----------------
    /** The charset book [id] reads its file names in ([NameCharset]), or null when it follows the setting. */
    fun nameCharset(id: BookId): String? = readObject(KEY_CHARSETS).optString(id.hex).ifEmpty { null }

    /** Every book's own file name charset, by ID. */
    fun nameCharsets(): Map<BookId, String> {
        val all = readObject(KEY_CHARSETS)
        val out = HashMap<BookId, String>()
        all.keys().forEach { k ->
            val id = BookId.parse(k) ?: return@forEach
            all.optString(k).takeIf { it.isNotEmpty() }?.let { out[id] = it }
        }
        return out
    }

    /** Makes [charset] the one [book] reads its file names in, or lets it follow the setting when null. */
    fun setNameCharset(book: BookRef, charset: String?) {
        synchronized(LOCK) {
            val all = readObject(KEY_CHARSETS)
            if (charset == null) all.remove(book.id.hex) else all.put(book.id.hex, charset)
            sp.edit().putString(KEY_CHARSETS, all.toString()).apply()
            if (charset != null) keep(book)
            publishNameCharsets()
        }
    }

    /** Hands the books' own file name charsets to the archive readers ([NameCharset.overrides]). */
    fun publishNameCharsets() {
        NameCharset.overrides = nameCharsets()
    }

    // ---------------- reading statistics ----------------
    /** Adds [seconds] of reading time and [pages] newly seen pages to today's totals. */
    fun addReading(seconds: Long, pages: Int) {
        synchronized(LOCK) {
            if (seconds <= 0 && pages <= 0) return@synchronized
            val o = readObject(KEY_STATS)
            o.put("seconds", o.optLong("seconds") + seconds.coerceAtLeast(0))
            o.put("pages", o.optLong("pages") + pages.coerceAtLeast(0))
            val days = o.optJSONArray("days") ?: JSONArray()
            val today = LocalDate.now().toString()
            var has = false
            for (i in 0 until days.length()) if (days.optString(i) == today) has = true
            if (!has) days.put(today)
            // Keep roughly ten years of days.
            val trimmed = JSONArray()
            for (i in (days.length() - MAX_DAYS).coerceAtLeast(0) until days.length()) trimmed.put(days.get(i))
            o.put("days", trimmed)
            sp.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    fun stats(): ReadingStats {
        val o = readObject(KEY_STATS)
        val days = HashSet<LocalDate>()
        o.optJSONArray("days")?.let { arr ->
            for (i in 0 until arr.length()) {
                runCatching { days.add(LocalDate.parse(arr.getString(i))) }
            }
        }
        // Current streak: consecutive days ending today (or yesterday if not read yet today).
        var streak = 0
        var d = LocalDate.now()
        if (d !in days) d = d.minusDays(1)
        while (d in days) {
            streak++
            d = d.minusDays(1)
        }
        // Best streak over all days.
        var best = 0
        for (day in days) {
            if (day.minusDays(1) in days) continue // not the start of a run
            var len = 0
            var cur = day
            while (cur in days) {
                len++
                cur = cur.plusDays(1)
            }
            if (len > best) best = len
        }
        return ReadingStats(
            seconds = o.optLong("seconds"),
            pages = o.optLong("pages"),
            chapters = readSet().size,
            daysRead = days.size,
            streak = streak,
            bestStreak = best
        )
    }

    fun clearStats() = sp.edit().putString(KEY_STATS, "{}").apply()

    // ---------------- what books keep ----------------
    /** What is kept for each book with anything kept, by ID. */
    fun kept(): Map<BookId, Kept> {
        val favorites = favoriteBooks()
        val read = readSet()
        val history = readArray(KEY_HISTORY).ids()
        val marks = readArray(KEY_BOOKMARKS).ids()
        val hidden = hiddenCounts()
        val settings = readIds(KEY_SHIFTED) + readIds(KEY_SHIFTED_SEAMS) + readIds(KEY_IGNORE_SEAMS) +
            readObject(KEY_ADJUST).ids() + readObject(KEY_CHARSETS).ids()
        val all = favorites + read + history + marks + hidden.keys + settings
        return all.associateWith { id ->
            Kept(
                favorite = id in favorites,
                read = id in read,
                history = id in history,
                bookmarks = marks.count { it == id },
                hidden = hidden[id] ?: 0,
                settings = id in settings
            )
        }
    }

    /** True when anything is kept for book [id]: where it was seen alone does not count. */
    fun hasData(id: BookId): Boolean = id in kept()

    /**
     * Forgets everything kept for books [ids]: favorites, read marks, history, bookmarks,
     * hidden pages, their own settings and where they were seen.
     */
    fun forgetBooks(ids: Set<BookId>) {
        if (ids.isEmpty()) return
        synchronized(LOCK) {
            val hexes = ids.mapTo(HashSet()) { it.hex }
            val e = sp.edit()
            e.putString(KEY_FAVORITES, JSONArray(readStringList(KEY_FAVORITES).filterNot { bookToken(it) in ids }).toString())
            for (k in ID_LIST_KEYS) e.putString(k, JSONArray(readIds(k).filterNot { it in ids }.map { it.hex }).toString())
            for (k in listOf(KEY_HISTORY, KEY_BOOKMARKS)) {
                val arr = readArray(k)
                val left = JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optString("id") !in hexes }?.let { left.put(it) }
                e.putString(k, left.toString())
            }
            for (k in BOOK_OBJECT_KEYS) {
                val o = readObject(k)
                for (h in hexes) o.remove(h)
                e.putString(k, o.toString())
            }
            e.apply()
            publishNameCharsets()
        }
    }

    /**
     * Clears what cannot be made again: favorites, read marks, history, bookmarks, hidden
     * pages, books' own settings and where books were seen. Folder layouts and reading
     * statistics stay.
     */
    fun clearLibraryData() {
        synchronized(LOCK) {
            val e = sp.edit()
            for (k in listOf(KEY_FAVORITES, KEY_HISTORY, KEY_BOOKMARKS) + ID_LIST_KEYS) e.putString(k, "[]")
            for (k in BOOK_OBJECT_KEYS) e.putString(k, "{}")
            e.apply()
            publishNameCharsets()
        }
    }

    /** What is kept for one book; see [kept]. */
    data class Kept(
        val favorite: Boolean = false,
        val read: Boolean = false,
        val history: Boolean = false,
        val bookmarks: Int = 0,
        val hidden: Int = 0,
        val settings: Boolean = false
    )

    private fun JSONArray.ids(): List<BookId> = (0 until length()).mapNotNull { BookId.parse(optJSONObject(it)?.optString("id").orEmpty()) }

    private fun JSONObject.ids(): Set<BookId> = keys().asSequence().mapNotNullTo(HashSet()) { BookId.parse(it) }

    // ---------------- upgrade ----------------
    /**
     * Drops what versions before Book IDs kept by book path: books start afresh under
     * their IDs. Favorite folders and folder layouts stay. Does nothing once done.
     */
    fun forgetPathKeyedBooks() {
        synchronized(LOCK) {
            if (sp.getInt(KEY_KEYED, 0) >= KEYED_BY_ID) return@synchronized
            val e = sp.edit()
            for (k in PATH_KEYED_BOOK_DOCS) e.remove(k)
            val folders = readStringList(KEY_FAVORITES).filter { isFolderPath(it) }
            e.putString(KEY_FAVORITES, JSONArray(folders).toString())
            e.putInt(KEY_KEYED, KEYED_BY_ID)
            e.apply()
        }
    }

    /** True for a favorite token naming a folder, not a book kept by path before Book IDs. */
    private fun isFolderPath(token: String) = isFolderToken(token) && !Formats.isOpenable(token.substringAfterLast('/'))

    // ---------------- backup ----------------
    /** The stored documents, keyed by name, as parsed JSON (for backup). */
    fun exportAll(): JSONObject {
        val out = JSONObject()
        for (k in ARRAY_KEYS) out.put(k, readArray(k))
        for (k in OBJECT_KEYS) out.put(k, readObject(k))
        return out
    }

    /**
     * Replaces the stored documents with those in [data]. Only known keys of the
     * right JSON type are taken, and of those only rows of a book with a valid ID or of a
     * folder; everything else (as a backup made before Book IDs keeps by path) is ignored.
     * A backup with history or bookmarks but no hidden pages counts every page, so every
     * page is shown again.
     */
    fun importAll(data: JSONObject) {
        synchronized(LOCK) {
            if (data.optJSONObject(KEY_HIDDEN) == null &&
                (data.optJSONArray(KEY_HISTORY) != null || data.optJSONArray(KEY_BOOKMARKS) != null)
            ) showAllPages()
            val e = sp.edit()
            for (k in ARRAY_KEYS) data.optJSONArray(k)?.let { e.putString(k, sanitizeArray(k, it).toString()) }
            for (k in OBJECT_KEYS) data.optJSONObject(k)?.let { e.putString(k, sanitizeObject(k, it).toString()) }
            e.putInt(KEY_KEYED, KEYED_BY_ID)
            e.apply()
            publishNameCharsets()
        }
    }

    /** Drops malformed rows so a hand-edited backup cannot break the lists later. */
    private fun sanitizeArray(key: String, arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            when (key) {
                KEY_FAVORITES -> arr.optString(i, "").takeIf { bookToken(it) != null || isFolderPath(it) }?.let { out.put(it) }
                KEY_READ, KEY_SHIFTED, KEY_SHIFTED_SEAMS, KEY_IGNORE_SEAMS -> BookId.parse(arr.optString(i, ""))?.let { out.put(it.hex) }
                else -> arr.optJSONObject(i)?.takeIf { BookId.parse(it.optString("id")) != null }?.let { out.put(it) }
            }
        }
        return out
    }

    /** Keeps only rows by book ID in documents kept by book, and those of a path in [KEY_BOOKS]. */
    private fun sanitizeObject(key: String, o: JSONObject): JSONObject {
        if (key !in BOOK_OBJECT_KEYS) return o
        val out = JSONObject()
        o.keys().forEach { k ->
            val id = BookId.parse(k) ?: return@forEach
            if (key == KEY_BOOKS && o.optJSONObject(k)?.optString("path").isNullOrEmpty()) return@forEach
            out.put(id.hex, o.get(k))
        }
        return out
    }

    // ---------------- helpers ----------------
    private fun parse(raw: String): JSONObject =
        try {
            JSONObject(raw)
        } catch (e: JSONException) {
            JSONObject()
        }

    private fun readArray(key: String): JSONArray =
        try {
            JSONArray(sp.getString(key, "[]") ?: "[]")
        } catch (e: JSONException) {
            JSONArray()
        }

    private fun readObject(key: String): JSONObject = parse(sp.getString(key, "{}") ?: "{}")

    private fun readStringList(key: String): MutableList<String> {
        val arr = readArray(key)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun writeStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun readIds(key: String): MutableSet<BookId> = readStringList(key).mapNotNullTo(HashSet()) { BookId.parse(it) }

    private fun writeIds(key: String, ids: Collection<BookId>) = writeStringList(key, ids.map { it.hex })

    private fun writeHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("id", h.id.hex).put("name", h.name)
                    .put("page", h.page).put("total", h.total).put("time", h.time)
            )
        }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun writeBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject().put("id", b.id.hex).put("name", b.name)
                    .put("page", b.page).put("note", b.note).put("time", b.time)
            )
        }
        sp.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    /** Where a book was last seen: its path and [BookLocation]. */
    data class Seen(val path: String, val location: String)

    /** A favorite: a folder at [path], or book [id] last seen at [path]. */
    data class Favorite(val path: String, val id: BookId?)

    /** The pages books leave out by [BookId], and by the path each was last seen at ([seen]). */
    class Hidden(val byId: Map<BookId, Set<Int>>, val byPath: Map<String, Set<Int>>, internal val seen: Map<BookId, Seen>) {
        fun isEmpty(): Boolean = byId.isEmpty()
    }

    companion object {
        private const val FILE = "library"

        /** Favorites, newest first: folders by path, books by [tokenOf]. */
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_READ = "read"
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_SERIES = "series"
        private const val KEY_STATS = "stats"
        private const val KEY_ADJUST = "adjust"
        private const val KEY_HIDDEN = "hidden_pages"

        /** Books shifted before seams paired pages ([isShiftedByNames]). */
        private const val KEY_SHIFTED = "shifted"

        /** Books shifted since. */
        private const val KEY_SHIFTED_SEAMS = "shifted_seams"

        /** Books whose seams are ignored ([ignoresSeams]); stored under its first name. */
        private const val KEY_IGNORE_SEAMS = "no_merge"

        /** Books' own file name charsets ([nameCharset]). */
        private const val KEY_CHARSETS = "name_charsets"

        /** Where each book with anything kept was last seen ([seenAt]). */
        private const val KEY_BOOKS = "books"

        /** A book's size in bytes, in its [KEY_BOOKS] entry. */
        private const val SIZE = "size"

        /** What books are kept by: [KEYED_BY_ID] once [forgetPathKeyedBooks] ran. */
        private const val KEY_KEYED = "keyed_by"
        private const val KEYED_BY_ID = 1

        /** What a favorite book is stored as, before its ID's hex digits. */
        private const val BOOK_TOKEN = "id:"

        const val MAX_HISTORY = 200
        private const val MAX_DAYS = 3650
        private val ARRAY_KEYS = listOf(KEY_FAVORITES, KEY_READ, KEY_HISTORY, KEY_BOOKMARKS, KEY_SHIFTED, KEY_SHIFTED_SEAMS, KEY_IGNORE_SEAMS)
        private val OBJECT_KEYS = listOf(KEY_SERIES, KEY_STATS, KEY_ADJUST, KEY_HIDDEN, KEY_CHARSETS, KEY_BOOKS)

        /** Lists of book IDs. */
        private val ID_LIST_KEYS = listOf(KEY_READ, KEY_SHIFTED, KEY_SHIFTED_SEAMS, KEY_IGNORE_SEAMS)

        /** Documents kept by book ID. */
        private val BOOK_OBJECT_KEYS = setOf(KEY_ADJUST, KEY_HIDDEN, KEY_CHARSETS, KEY_BOOKS)

        /** Documents older versions kept by book path, dropped once ([forgetPathKeyedBooks]). */
        private val PATH_KEYED_BOOK_DOCS = listOf(
            KEY_READ, KEY_HISTORY, KEY_BOOKMARKS, KEY_ADJUST, KEY_HIDDEN, KEY_SHIFTED, KEY_SHIFTED_SEAMS, KEY_IGNORE_SEAMS, KEY_CHARSETS
        )
        private val LOCK = Any()

        private fun tokenOf(id: BookId) = BOOK_TOKEN + id.hex

        private fun bookToken(token: String): BookId? = if (token.startsWith(BOOK_TOKEN)) BookId.parse(token.substring(BOOK_TOKEN.length)) else null

        private fun isFolderToken(token: String) = !token.startsWith(BOOK_TOKEN) && token.isNotEmpty()

        /** Where books were seen as [seenAt] parsed it last, with the text it was parsed from. */
        @Volatile
        private var seenParsed: Pair<String, Map<BookId, Seen>>? = null

        /** The stored hidden pages [hiddenAll] parsed last, with the text they were parsed from. */
        @Volatile
        private var hiddenParsed: Pair<String, Hidden>? = null
    }
}
