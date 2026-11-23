package com.infinity.comicreader.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.infinity.comicreader.net.FileEnds
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.RemoteInput
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.util.Timing
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Finds books' [BookId]s: from the [BookIndex] when a file's path, size and time were
 * seen before (no read), else by reading its ends ([BookIdBytes]) and hashing them,
 * which the index then remembers. A file being hashed is hashed once however many ask.
 * On a share both ends are asked for at once, one round trip, and kept a moment
 * ([FileEnds]) so opening the book next starts from them. A share that sends files
 * only whole ([Remotes.seekable]) is never read for an ID: there it comes from the
 * book's local copy once fetched ([ofCopy]).
 */
object BookIds {
    /** The hash IDs are made with; JVM tests set a pure-JVM one. */
    @Volatile
    @get:VisibleForTesting
    @set:VisibleForTesting
    var hasher: Xxh3? = null

    @Volatile
    private var index: BookIndex? = null

    /** IDs found while no index is set (before [init], as in JVM tests), by path, size and time; the latest few. */
    private val unindexed = object : LinkedHashMap<String, BookId>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookId>?) = size > UNINDEXED
    }
    private const val UNINDEXED = 1024

    /** Files being hashed now, by path, size and time, so a second ask waits for the first. */
    private val hashing = ConcurrentHashMap<String, FutureTask<BookId>>()

    /** Reads a share file's other end while the asking thread reads the first. */
    private val ENDS: Executor by lazy {
        Executors.newCachedThreadPool { r -> Thread(r, "book-id-ends").apply { isDaemon = true } }
    }

    /** Remembers IDs in [context]'s [BookIndex] from now on. Cheap; called by every [Library]. */
    fun init(context: Context) {
        if (index == null) index = BookIndex(context.applicationContext)
    }

    /** Lets go of the index and closes its database; the next [init] opens it again. For an emulated process end ([android.app.Application.onTerminate]). */
    fun close() {
        index = null
        BookIndex.reset()
    }

    /** Forgets the context, the hasher and what was kept by ID this session; tests start each case anew. */
    @VisibleForTesting
    fun reset() {
        index = null
        hasher = null
        hashing.clear()
        synchronized(unindexed) { unindexed.clear() }
        FileEnds.clear()
        BookDirection.forgetChecks()
    }

    private fun index(): BookIndex? = index

    /** Reads the index into memory, so [known] and [last] answer from it. Blocking; started at app start. */
    fun load() {
        index()?.load()
    }

    private fun hash(): Xxh3 = hasher ?: LibXxh3

    /** The XXH3-64 Book IDs are made with, for other hashes of the same kind (output marks). */
    val xxh3: Xxh3 get() = hash()

    /** The size and modification time of [file] as known now; null for a file on a share whose stat is not known. */
    private fun statOf(file: File): Pair<Long, Long>? =
        if (file is RemoteFile) file.stat()?.takeIf { !it.isDirectory }?.let { it.size to it.modified }
        else FileStat.of(file).takeIf { !it.isDirectory }?.let { it.size to it.lastModified }

    /** The ID of [file] when the index knows it at its size and time now: no read. Blocking: a stat, and the index's first [load]. */
    fun known(file: File): BookId? {
        val (size, modified) = statOf(file) ?: return null
        load()
        return known(file.absolutePath, size, modified)
    }

    /** The ID known for the file at [path] of [size] bytes modified at [modified]; null when unseen, changed since, or the index is not [load]ed yet. Never blocks. */
    fun known(path: String, size: Long, modified: Long): BookId? =
        index()?.get(path, size, modified) ?: synchronized(unindexed) { unindexed["$path|$size|$modified"] }

    /**
     * The ID last found for the file at [path], whatever its size and time then: no stat, no
     * read, so possibly of a file since replaced there. For counts, not for what is kept.
     */
    fun last(path: String): BookId? = index()?.last(path)

    /** The ID of [file]; null when it cannot be read. Blocking. */
    fun find(file: File): BookId? = try {
        of(file)
    } catch (e: IOException) {
        null
    }

    /**
     * The ID of [file]: known, else read and hashed now. Blocking; throws when it cannot be
     * read, as on a share sending files only whole before the book is fetched.
     */
    @Throws(IOException::class)
    fun of(file: File): BookId {
        val path = file.absolutePath
        val (size, modified) = statOf(file) ?: throw FileNotFoundException(path)
        load()
        known(path, size, modified)?.let { return it }
        // Reading even its ends from such a share would stream the whole file, maybe twice.
        if (file is RemoteFile && !Remotes.seekable(file.path)) throw NotInPlaceException(path)
        val key = "$path|$size|$modified"
        val mine = FutureTask { hashNow(file, size, modified) }
        val task = hashing.putIfAbsent(key, mine) ?: mine
        if (task === mine) {
            try {
                mine.run()
            } finally {
                hashing.remove(key, mine)
            }
        }
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted")
        }
    }

    /** The ID of local [file] as its contents are now, read and hashed, not remembered for its path (a file about to be renamed). Blocking. */
    @Throws(IOException::class)
    fun peek(file: File): BookId = BookIdBytes.idOf(localBytes(file), hash())

    /** The cache key of [file]'s contents: its ID when it can be found, else its path, size and time. Blocking. */
    fun keyOf(file: File): String = find(file)?.let { "id:${it.hex}" } ?: "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    /** The ID a cache key made by [keyOf] holds; null for a key made without one. */
    fun parseKey(key: String): BookId? = if (key.startsWith("id:")) BookId.parse(key.substring(3)) else null

    /** Hashes [file], listed at [size] and [modified], and remembers the ID found. */
    private fun hashNow(file: File, size: Long, modified: Long): BookId =
        remember(file.absolutePath, size, modified, Timing.span("book id") { bytesOf(file, modified) })

    /**
     * The ID of share file [file], listed at [size] and [modified], hashed from [copy], its
     * whole local copy just fetched, and remembered for [file]. Blocking.
     */
    @Throws(IOException::class)
    fun ofCopy(file: RemoteFile, size: Long, modified: Long, copy: File): BookId =
        remember(file.path, size, modified, localBytes(copy))

    /** The ID of [layout], the ID bytes of the file at [path] listed at [size] and [modified], remembered for it. */
    private fun remember(path: String, size: Long, modified: Long, layout: ByteArray): BookId {
        val id = BookIdBytes.idOf(layout, hash())
        // A file that changed while being read is not remembered under the stat taken before.
        if (size < 0 || sizeOf(layout) == size) {
            val index = index()
            if (index != null) index.put(path, size, modified, id)
            else synchronized(unindexed) { unindexed["$path|$size|$modified"] = id }
        }
        return id
    }

    /** What [of] throws for a book on a share that sends files only whole, not fetched yet. */
    class NotInPlaceException(path: String) : IOException("Not readable in place: $path")

    /** The bytes [file], listed at [modified], has its ID by ([BookIdBytes]), read now whether known or not. Blocking. */
    internal fun bytesOf(file: File, modified: Long): ByteArray = if (file is RemoteFile) remoteBytes(file, modified) else localBytes(file)

    /** The size a layout says its file had. */
    private fun sizeOf(layout: ByteArray): Long {
        var v = 0L
        for (i in BookIdBytes.SIZE_BYTES - 1 downTo 0) v = (v shl 8) or (layout[i].toLong() and 0xFF)
        return v
    }

    private fun localBytes(file: File): ByteArray = RandomAccessFile(file, "r").use { raf ->
        BookIdBytes.of(raf.length()) { position, into, offset, length ->
            raf.seek(position)
            raf.readFully(into, offset, length)
        }
    }

    /** Both ends of share file [file] asked for at once, kept in [FileEnds] for whatever opens it next. */
    private fun remoteBytes(file: RemoteFile, modified: Long): ByteArray = Remotes.open(file.path).use { input ->
        val size = input.length
        val layout = BookIdBytes.start(size)
        val ranges = BookIdBytes.ranges(size)
        val others = ranges.drop(1).map { (position, offset, length) ->
            FutureTask { readAll(input, position, layout, offset, length) }.also { ENDS.execute(it) }
        }
        try {
            ranges.firstOrNull()?.let { (position, offset, length) -> readAll(input, position, layout, offset, length) }
        } finally {
            for (t in others) {
                try {
                    t.get()
                } catch (e: ExecutionException) {
                    throw e.cause as? IOException ?: IOException(e.cause)
                }
            }
        }
        FileEnds.put(file.path, size, modified, layout)
        layout
    }

    private fun readAll(input: RemoteInput, position: Long, into: ByteArray, offset: Int, length: Int) {
        var n = 0
        while (n < length) {
            val r = input.read(position + n, into, offset + n, length - n)
            if (r < 0) throw EOFException("Ended at ${position + n}")
            n += r
        }
    }

    /** Re-points the IDs known for [old], renamed or moved by the app, to [new]. Blocking. */
    fun moved(old: String, new: String) {
        index()?.move(old, new)
    }

    /** The IDs last found for [root], a file or folder, and everything below it. Blocking. */
    fun knownUnder(root: String): Set<BookId> = index()?.under(root).orEmpty()

    /**
     * For each of [ids], the path of a copy outside [root] the index knows of: a file on a
     * share, or one on the device still there. IDs without one are left out. Blocking.
     */
    fun elsewhere(ids: Set<BookId>, root: String): Map<BookId, String> =
        index()?.elsewhere(ids, root) { Remotes.isRemote(it) || File(it).isFile }.orEmpty()

    /** The size the file at [path] had when found to be book [id]; null when not known now. Never blocks. */
    fun sizeAt(path: String, id: BookId): Long? = index()?.sizeAt(path, id)

    /** The size each of [ids] was last found at, where the index has it. Blocking. */
    fun sizesOf(ids: Set<BookId>): Map<BookId, Long> = index()?.sizesOf(ids).orEmpty()

    /** Forgets every ID found; each file is hashed again when next asked for. Blocking. */
    fun clear() {
        index()?.clear()
        synchronized(unindexed) { unindexed.clear() }
    }

    /** Forgets the IDs known for [root], deleted or replaced, and everything below it. Blocking. */
    fun removed(root: String) {
        index()?.remove(root)
    }
}
