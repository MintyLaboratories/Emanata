package com.infinity.comicreader.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers each book's [BookFacts] with the file size they were read at; a
 * different size means the book changed and its facts are stale. Kept in memory
 * once loaded, and on disk across launches.
 */
class BookFactsCache(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The facts stored for [path] at [size], or null when unknown or stale. */
    fun get(path: String, size: Long): BookFacts? {
        val (known, facts) = memory()[path] ?: return null
        return facts.takeIf { known == size }
    }

    /** Stores the facts of several books at once, by path, with the size each was read at. */
    fun putAll(found: Map<String, Pair<Long, BookFacts>>) {
        if (found.isEmpty()) return
        memory().putAll(found)
        val e = sp.edit()
        for ((path, value) in found) e.putString(path, encode(value.first, value.second))
        e.apply()
    }

    /** Loads the stored facts, so later lookups do not read the disk. */
    fun warm() {
        memory()
    }

    private fun memory(): MutableMap<String, Pair<Long, BookFacts>> {
        loaded?.let { return it }
        synchronized(LOCK) {
            loaded?.let { return it }
            val map = ConcurrentHashMap<String, Pair<Long, BookFacts>>()
            for ((path, value) in sp.all) decode(value as? String)?.let { map[path] = it }
            loaded = map
            return map
        }
    }

    companion object {
        private const val FILE = "book_facts"
        private val LOCK = Any()

        /** Shared by every instance: one process, one file. */
        @Volatile
        private var loaded: MutableMap<String, Pair<Long, BookFacts>>? = null

        /** `size:pages:year`. */
        internal fun encode(size: Long, facts: BookFacts) = "$size:${facts.pages}:${facts.year}"

        internal fun decode(value: String?): Pair<Long, BookFacts>? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 3) return null
            val size = parts[0].toLongOrNull() ?: return null
            val pages = parts[1].toIntOrNull() ?: return null
            val year = parts[2].toIntOrNull() ?: return null
            return size to BookFacts(pages, year)
        }

        /** Forgets the in-memory copy; tests start each case from disk. */
        internal fun reset() {
            loaded = null
        }
    }
}
