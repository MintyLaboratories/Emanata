package com.infinity.comicreader.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.infinity.comicreader.net.FileEnds
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.RemoteInput
import com.infinity.comicreader.net.Remotes
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
 * ([FileEnds]) so opening the book next starts from them.
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

    /** Forgets the context and hasher; tests start each case anew. */
    @VisibleForTesting
    fun reset() {
        index = null
        hasher = null
        hashing.clear()
        synchronized(unindexed) { unindexed.clear() }
    }

    private fun index(): BookIndex? = index

    private fun hash(): Xxh3 = hasher ?: LibXxh3

    /** The size and modification time of [file] as known now; null for a file on a share whose stat is not known. */
    private fun statOf(file: File): Pair<Long, Long>? =
        if (file is RemoteFile) file.stat()?.takeIf { !it.isDirectory }?.let { it.size to it.modified }
        else FileStat.of(file).takeIf { !it.isDirectory }?.let { it.size to it.lastModified }

    /** The ID of [file] when the index knows it at its size and time now: no read. */
    fun known(file: File): BookId? {
        val (size, modified) = statOf(file) ?: return null
        return known(file.absolutePath, size, modified)
    }

    /** The ID known for the file at [path] of [size] bytes modified at [modified]; null when unseen or changed since. */
    fun known(path: String, size: Long, modified: Long): BookId? =
        index()?.get(path, size, modified) ?: synchronized(unindexed) { unindexed["$path|$size|$modified"] }

    /** The ID of [file]; null when it cannot be read. Blocking. */
    fun find(file: File): BookId? = try {
        of(file)
    } catch (e: IOException) {
        null
    }

    /** The ID of [file]: known, else read and hashed now. Blocking; throws when it cannot be read. */
    @Throws(IOException::class)
    fun of(file: File): BookId {
        val path = file.absolutePath
        val (size, modified) = statOf(file) ?: throw FileNotFoundException(path)
        known(path, size, modified)?.let { return it }
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

    /** The cache key of [file]'s contents: its ID when it can be found, else its path, size and time. Blocking. */
    fun keyOf(file: File): String = find(file)?.let { "id:${it.hex}" } ?: "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    /** Hashes [file], listed at [size] and [modified], and remembers the ID found. */
    private fun hashNow(file: File, size: Long, modified: Long): BookId {
        val layout = Timing.span("book id") { bytesOf(file, modified) }
        val id = BookIdBytes.idOf(layout, hash())
        val read = sizeOf(layout)
        // A file that changed while being read is not remembered under the stat taken before.
        if (size < 0 || read == size) {
            val index = index()
            if (index != null) index.put(file.absolutePath, size, modified, id)
            else synchronized(unindexed) { unindexed["${file.absolutePath}|$size|$modified"] = id }
        }
        return id
    }

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

    /** Forgets the IDs known for [root], deleted or replaced, and everything below it. Blocking. */
    fun removed(root: String) {
        index()?.remove(root)
    }
}
