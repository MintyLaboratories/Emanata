package com.infinity.comicreader.reader

import android.util.Log
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Opens archives stored inside an archive (a `.cbr` holding one `.cbz` per issue).
 * Each inner archive is copied to a file under [dir] and opened like a book of its
 * own. Copies are reused across opens, keyed by the outer archive's identity and the
 * entry path, and the least recently used go first once [dir] passes [maxBytes].
 * Copies held by any open book, in this or another instance, are never evicted;
 * [close] lets go of this instance's.
 *
 * One instance serves one book open and is not thread-safe. Guards: an inner
 * archive larger than [maxEntryBytes], a book needing more than [maxBytes] of copies
 * or more than [MAX_ARCHIVES] archives, and nesting deeper than [MAX_DEPTH] are skipped.
 * Copying stops at the declared size, so a forged header cannot fill the disk.
 * These skips and unreadable inner archives are the same on every open; a copy that
 * fails for lack of storage throws [StorageException] instead, so a book never opens
 * with fewer pages than it has.
 */
class NestedArchives(
    private val dir: File,
    private val charsetName: String,
    private val maxBytes: Long = MAX_CACHE_BYTES,
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES
) {
    /** An opened inner archive and the identity its own inner archives are keyed by. */
    class Opened(val reader: ArchiveReader, val key: String)

    /** An inner archive could not be copied for lack of free space or a failed write. */
    class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val held = ArrayList<File>()
    private var usedBytes = 0L

    /**
     * Opens [entry] of [outer], whose identity is [outerKey]; null when it is skipped or
     * unreadable. Throws [StorageException] when it cannot be copied right now.
     */
    fun open(outer: ArchiveReader, outerKey: String, entry: ArchiveEntry): Opened? {
        if (held.size >= MAX_ARCHIVES) return null
        val key = "$outerKey\n${entry.path}\n${entry.size}"
        val file = try {
            extract(outer, entry, key)
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract nested archive ${entry.path}", e)
            null
        } ?: return null
        val reader = try {
            ArchiveReader.open(file, charsetName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open nested archive ${entry.path}", e)
            null
        } ?: return null
        return Opened(reader, key)
    }

    private fun extract(outer: ArchiveReader, entry: ArchiveEntry, key: String): File? {
        val limit = if (entry.size >= 0) entry.size else maxEntryBytes
        if (limit > maxEntryBytes || usedBytes + limit > maxBytes) return null
        val target = File(dir, hash(key) + "." + Formats.ext(entry.path))
        // Held before it is checked or written, so no trim can delete it from here on.
        val lock = retain(target)
        try {
            synchronized(lock) {
                if (!target.isFile || (entry.size >= 0 && target.length() != entry.size)) {
                    dir.mkdirs()
                    trim(dir, maxBytes - limit)
                    if (dir.usableSpace < limit + FREE_SPACE_RESERVE) {
                        throw StorageException("No space to copy nested archive ${entry.path}")
                    }
                    val part = storage { File.createTempFile(target.name, PART_SUFFIX, dir) }
                    try {
                        val copied = outer.read(entry) { input ->
                            storage { part.outputStream() }.use { copyBounded(input, it, limit) }
                        }
                        if (entry.size >= 0 && copied != entry.size) throw IOException("Short nested archive")
                        if (!part.renameTo(target)) {
                            target.delete()
                            if (!part.renameTo(target)) throw StorageException("Could not store nested archive")
                        }
                    } finally {
                        part.delete()
                    }
                }
                target.setLastModified(System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            release(target)
            throw t
        }
        held += target
        usedBytes += target.length()
        return target
    }

    /** Lets go of the copies this instance opened, so [trim] may evict them. */
    fun close() {
        for (f in held) release(f)
        held.clear()
    }

    companion object {
        /** Archives may nest this many levels below the book itself. */
        const val MAX_DEPTH = 2
        const val MAX_ARCHIVES = 256
        const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 1L * 1024 * 1024 * 1024
        private const val FREE_SPACE_RESERVE = 256L * 1024 * 1024
        private const val DIR = "nested"
        private const val PART_SUFFIX = ".part"
        private const val STALE_PART_MS = 60L * 60 * 1000
        private const val TAG = "NestedArchives"
        private const val HEX_DIGITS = "0123456789abcdef"

        /** Copies held by open books, by path, with their holder count; guarded by itself. */
        private val HELD = HashMap<String, Hold>()

        /** Holder count of one copy; also the lock its extraction runs under. */
        private class Hold {
            var count = 0
        }

        /** Marks [file] as held; returns the lock that serializes writing it. */
        internal fun retain(file: File): Any = synchronized(HELD) {
            HELD.getOrPut(file.absolutePath) { Hold() }.also { it.count++ }
        }

        internal fun release(file: File) {
            synchronized(HELD) {
                val hold = HELD[file.absolutePath] ?: return
                if (--hold.count == 0) HELD.remove(file.absolutePath)
            }
        }

        /** Deletes [file] unless an open book holds it. */
        private fun deleteUnheld(file: File): Boolean = synchronized(HELD) {
            file.absolutePath !in HELD && file.delete()
        }

        fun dirIn(cacheDir: File) = File(cacheDir, DIR)

        /** Identity of a book file on disk: changes when the file is replaced. */
        fun keyOf(file: File) = "${file.absolutePath}\n${file.length()}\n${file.lastModified()}"

        /**
         * Deletes least recently used copies until [dir] holds at most [maxBytes], never
         * touching those held by an open book. Unfinished copies count until they are an
         * hour old, then go.
         */
        fun trim(dir: File, maxBytes: Long = MAX_CACHE_BYTES) {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            val now = System.currentTimeMillis()
            var total = files.sumOf { it.length() }
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                if (f.name.endsWith(PART_SUFFIX) && now - f.lastModified() < STALE_PART_MS) continue
                val size = f.length()
                if (deleteUnheld(f)) total -= size
            }
            for (f in files) {
                if (f.name.endsWith(PART_SUFFIX) && now - f.lastModified() >= STALE_PART_MS) f.delete()
            }
        }

        /**
         * Copies [input] to [output]; fails once more than [limit] bytes arrive. Returns the
         * bytes copied. A failed write throws [StorageException].
         */
        internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) return total
                total += n
                if (total > limit) throw IOException("Nested archive exceeds $limit bytes")
                storage { output.write(buf, 0, n) }
            }
        }

        /** Runs the cache write [block], reporting its I/O failure as a [StorageException]. */
        private inline fun <T> storage(block: () -> T): T = try {
            block()
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            throw StorageException("Could not write nested archive copy", e)
        }

        private fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = CharArray(32)
            for (i in 0 until 16) {
                val b = bytes[i].toInt() and 0xFF
                hex[i * 2] = HEX_DIGITS[b ushr 4]
                hex[i * 2 + 1] = HEX_DIGITS[b and 0x0F]
            }
            return String(hex)
        }
    }
}
