package minty.emanata.data

import minty.emanata.net.Remotes
import minty.emanata.util.Formats
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Forget missing books": finds the books [library] keeps data for that no reachable
 * location holds any more. Every location is walked for archives and PDFs; a file the
 * [BookIds] index knows at its size and time costs no read, and an unknown one is hashed
 * only when its size is that of a book still missing (a Book ID holds its file's size).
 * Books not found so are looked for where last seen ([lanes] at a time, as are the unknown
 * files hashed): loose images, books in hidden folders or outside every location, and books
 * whose size is not known. A book found anywhere reachable notes where ([Library.seenAll]);
 * one last seen in a location that cannot be reached now (a share offline, storage not
 * mounted, a listing that failed) is left be, the location named in [Result.skipped].
 * Blocking; stops, returning null, once [alive] turns false.
 */
class MissingBooks(
    private val library: Library,
    private val locations: List<Location>,
    private val alive: () -> Boolean = { true },
    private val progress: (Progress) -> Unit = {},
    private val lanes: Int = LANES
) {
    /** A place books are kept: [key] as [BookLocation] names it, [name] as shown, [root] its top folder. */
    data class Location(val key: String, val name: String, val root: String)

    /**
     * How far the scan is: in [location], [folders] folders and [books] books seen so far;
     * then, once every location is walked, [checked] of [checking] books and files looked at.
     */
    data class Progress(val location: String, val folders: Int, val books: Int, val checked: Int = 0, val checking: Int = 0)

    /** Why a book is listed: [GONE] from where it was last seen, a [CHANGED] file there now, or no place known ([UNKNOWN]). */
    enum class Why { GONE, CHANGED, UNKNOWN }

    /** A book kept for, found nowhere: its [id], where it was last [seen] (null when not known), what is [kept] for it, [why]. */
    data class Missing(val id: BookId, val seen: Library.Seen?, val kept: Library.Kept, val why: Why = Why.GONE)

    /**
     * What a scan found: [missing] books, of which those [ticked] are ticked for forgetting
     * by default: books gone from where they were seen, and only when every location was
     * reached ([complete]); the names of the locations [skipped].
     */
    data class Result(val missing: List<Missing>, val skipped: List<String>, val found: Int) {
        val complete: Boolean get() = skipped.isEmpty()

        fun ticked(book: Missing): Boolean = complete && book.why == Why.GONE
    }

    /** A file found by the walk. */
    private class Entry(val path: String, val size: Long, val modified: Long)

    /** What is at a book's last-seen path: a file or not, and its ID when it could be read. */
    private class AtPath(val present: Boolean, val id: BookId?)

    private var folders = 0
    private var books = 0

    fun run(): Result? {
        val kept = library.kept()
        if (kept.isEmpty()) return Result(emptyList(), emptyList(), 0)
        val seen = library.seenAt()
        val wanted = kept.keys
        val foundAt = HashMap<BookId, String>()
        val unknown = ArrayList<Entry>()
        val reached = HashSet<String>()
        val failed = ArrayList<Location>()
        BookIds.load()

        for (location in locations) {
            if (!alive()) return null
            val files = walk(location) ?: run {
                if (!alive()) return null
                failed += location
                null
            } ?: continue
            reached += location.key
            for (f in files) {
                val id = BookIds.known(f.path, f.size, f.modified)
                when {
                    id == null -> unknown += f
                    id in wanted -> if (id !in foundAt || seen[id]?.path == f.path) foundAt[id] = f.path
                }
            }
        }

        // A location not reached: one whose listing failed, or storage not mounted now. A
        // path on the device's own storage outside every location is looked at where it is,
        // as is a share removed from the app (nothing is there).
        val failedKeys = failed.mapTo(HashSet()) { it.key }
        fun skipped(s: Library.Seen) = s.location in failedKeys ||
            (s.location != BookLocation.DEVICE && s.location !in reached && !isRemovedShare(s.location))

        // Looked at where last seen: loose images, books outside every location or where the
        // walk did not go (hidden folders), and books whose size is not known.
        val atSeen = (wanted - foundAt.keys).filter { id -> seen[id]?.let { !skipped(it) } == true }
        // Unknown files of the size of a book still missing may be it.
        val sizes = library.sizes() + BookIds.sizesOf(wanted)
        fun sizesLeft() = (wanted - foundAt.keys).mapNotNullTo(HashSet()) { sizes[it] }
        val maybe = sizesLeft().let { left -> unknown.filter { it.size in left } }
        val checking = atSeen.size + maybe.size
        var checked = 0
        fun step(n: Int = 1) {
            checked += n
            progress(Progress("", folders, books, checked, checking))
        }
        val changed = HashSet<BookId>()

        if (checking > 0) {
            step(0)
            val pool = Executors.newFixedThreadPool(lanes) { r -> Thread(r, "missing-books").apply { isDaemon = true } }
            try {
                val there = atSeen.map { id -> pool.submit(Callable { atPath(seen.getValue(id).path) }) }
                for ((i, f) in there.withIndex()) {
                    val at = await(f) ?: return null
                    val id = atSeen[i]
                    if (at is AtPath && at.id == id) foundAt[id] = seen.getValue(id).path
                    else if (at !is AtPath || at.present) changed += id
                    step()
                }
                val left = sizesLeft()
                val files = maybe.filter { it.size in left }
                step(maybe.size - files.size)
                val all = AtomicBoolean(foundAt.keys.containsAll(wanted))
                val ids = files.map { e -> pool.submit(Callable { if (all.get()) null else BookIds.find(Remotes.file(e.path)) }) }
                for ((i, f) in ids.withIndex()) {
                    val id = await(f) ?: return null
                    if (id is BookId && id in wanted && id !in foundAt) {
                        foundAt[id] = files[i].path
                        if (foundAt.keys.containsAll(wanted)) all.set(true)
                    }
                    step()
                }
            } finally {
                pool.shutdownNow()
            }
        }

        library.seenAll(foundAt.filter { (id, path) -> seen[id]?.path != path }.map { (id, path) -> BookRef(id, path) })

        val missing = ArrayList<Missing>()
        val skippedNames = LinkedHashSet<String>()
        failed.forEach { skippedNames += it.name }
        for (id in wanted - foundAt.keys) {
            val s = seen[id]
            if (s == null) {
                missing += Missing(id, null, kept.getValue(id), Why.UNKNOWN)
                continue
            }
            if (skipped(s)) {
                skippedNames += locations.firstOrNull { it.key == s.location }?.name ?: s.location.removePrefix(BookLocation.VOLUME_PREFIX)
                continue
            }
            missing += Missing(id, s, kept.getValue(id), if (id in changed) Why.CHANGED else Why.GONE)
        }
        missing.sortWith(compareBy({ it.seen == null }, { it.seen?.path?.lowercase() }))
        return Result(missing, skippedNames.toList(), foundAt.size)
    }

    /**
     * What [f] gave once done ([Unit] for nothing, or when it failed); null when no longer
     * [alive] first.
     */
    private fun await(f: Future<*>): Any? {
        while (true) {
            if (!alive()) return null
            try {
                return f.get(POLL_MS, TimeUnit.MILLISECONDS) ?: Unit
            } catch (e: TimeoutException) {
                // Still at it.
            } catch (e: ExecutionException) {
                return Unit
            }
        }
    }

    /** True for [key], a share's location, of a share removed from the app: never reachable again. */
    private fun isRemovedShare(key: String): Boolean =
        key.startsWith(BookLocation.SHARE_PREFIX) && Remotes.share(key.removePrefix(BookLocation.SHARE_PREFIX)) == null

    /** What is at [path]: whether a file is, and its ID when it can be read. */
    private fun atPath(path: String): AtPath {
        val file = Remotes.file(path)
        if (!file.isFile) return AtPath(false, null)
        return AtPath(true, BookIds.find(file))
    }

    /**
     * Every archive and PDF below [location]'s root, its folders listed [lanes] at a time, level
     * by level; hidden folders are left out. Null when a listing failed, or once not [alive].
     */
    private fun walk(location: Location): List<Entry>? {
        val out = ArrayList<Entry>()
        var level = listOf(location.root)
        val pool = Executors.newFixedThreadPool(lanes) { r -> Thread(r, "missing-books").apply { isDaemon = true } }
        try {
            while (level.isNotEmpty()) {
                if (!alive()) return null
                val listed = try {
                    pool.invokeAll(level.map { dir -> Callable { list(dir, top = dir == location.root) } }).map { it.get() }
                } catch (e: ExecutionException) {
                    return null
                }
                val next = ArrayList<String>()
                for (e in listed.flatten()) {
                    if (e.isDir) {
                        // App folders hold no library; hidden ones are looked for where last seen.
                        if (!e.name.startsWith(".") && !(e.name == "Android" && e.parent == location.root)) next += e.path
                    } else if (Formats.typeOf(e.name).let { it == EntryType.ARCHIVE || it == EntryType.PDF }) {
                        out += Entry(e.path, e.size, e.modified)
                    }
                }
                folders += level.size
                progress(Progress(location.name, folders, books + out.size))
                level = next
            }
        } finally {
            pool.shutdownNow()
        }
        books += out.size
        return out
    }

    /** One entry of folder [parent]. */
    private class Listed(val parent: String, val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    /**
     * The entries of folder [dir], the [top] of a location or not; throws when a share's
     * folder or a location's top cannot be listed (a device folder below it that cannot be
     * is taken as empty). Symbolic links are left out, so a loop cannot hang the walk.
     */
    private fun list(dir: String, top: Boolean): List<Listed> {
        if (Remotes.isRemote(dir)) {
            return Remotes.list(dir).mapNotNull { f ->
                val s = f.stat() ?: return@mapNotNull null
                if (s.isLink) null else Listed(dir, f.path, f.name, s.isDirectory, s.size, s.modified)
            }
        }
        val files = File(dir).listFiles() ?: if (top) throw IOException("Cannot list $dir") else return emptyList()
        return files.mapNotNull { f ->
            if (Files.isSymbolicLink(f.toPath())) return@mapNotNull null
            val stat = FileStat.of(f)
            Listed(dir, f.path, f.name, stat.isDirectory, stat.size, stat.lastModified)
        }
    }

    companion object {
        /** Folders listed at once: a share answers several requests in flight. */
        const val LANES = 4

        /** How often a wait for a book being looked at checks whether the scan was canceled. */
        private const val POLL_MS = 100L

        /** [roots], the storage mounted and the shares, as the locations to scan. */
        fun locations(roots: List<StorageRoot>): List<Location> = roots.map { Location(BookLocation.of(it.path), it.name, it.path) }
    }
}
