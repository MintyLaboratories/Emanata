package com.infinity.comicreader.util

import java.io.File

/**
 * Cache files kept per book: named after the book ([nameOf]) so all of one book's are
 * found by listing their folder ([forget]), and kept under a size by dropping the least
 * recently used first ([trim]).
 */
object CacheFiles {
    /**
     * What the files of the book keyed [bookKey] start with: its Book ID's hex digits for a
     * key made from one (`id:` and the digits, as [com.infinity.comicreader.data.BookIds.keyOf]
     * makes), else a hash of the key. Only the part before the first [ends] counts, so keys
     * of a book's parts name their files after the book.
     */
    fun prefixOf(bookKey: String, ends: Char = '\u0000'): String {
        val book = bookKey.substringBefore(ends)
        val hex = book.removePrefix(ID_PREFIX)
        return if (book.startsWith(ID_PREFIX) && hex.length == 16 && hex.all { it in '0'..'9' || it in 'a'..'f' }) hex
        else Hashes.sha256Hex(book, bytes = 8)
    }

    /** The name of a file of book [bookKey] for [key]: the book's [prefixOf], then a hash of [key]. */
    fun nameOf(bookKey: String, key: String, ends: Char = '\u0000'): String = prefixOf(bookKey, ends) + "-" + Hashes.sha256Hex(key)

    /** Deletes the files in [dir] of the book whose [prefixOf] is [prefix], except those [keep] holds. */
    fun forget(dir: File, prefix: String, keep: (File) -> Boolean = { false }) {
        dir.listFiles()?.filter { it.name.startsWith("$prefix-") && !keep(it) }?.forEach { it.deleteRecursively() }
    }

    /** Marks [file] as just used, for [trim]. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** The bytes [file] takes on disk: whole blocks of [BLOCK] bytes. */
    fun onDisk(file: File): Long = (file.length() + BLOCK - 1) / BLOCK * BLOCK

    /** The bytes the files in and below [dir] take on disk. */
    fun bytesUnder(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { onDisk(it) }

    /**
     * Deletes the least recently used files directly in [dir] until those left take at
     * most [maxBytes] on disk ([onDisk]); files [keep] holds stay.
     */
    fun trim(dir: File, maxBytes: Long, keep: (File) -> Boolean = { false }) {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty().map { it to onDisk(it) }
        var total = files.sumOf { it.second }
        if (total <= maxBytes) return
        for ((f, size) in files.sortedBy { it.first.lastModified() }) {
            if (total <= maxBytes) break
            if (!keep(f) && f.delete()) total -= size
        }
    }

    private const val ID_PREFIX = "id:"

    /** The usual file system block: a small file takes one whole. */
    const val BLOCK = 4096L
}
