package com.infinity.comicreader.data

import com.infinity.comicreader.net.ChangeRefused

/**
 * The books open in the reader, by path. Rebuilding one would leave the reader saving
 * progress for its old file, so a rebuild leaves them alone ([unlessOpen]).
 */
object OpenBooks {
    private val open = HashMap<String, Int>()

    /** The reader opened the book at [path]; it waits while one takes its place ([unlessOpen]). */
    @Synchronized
    fun opened(path: String) {
        open[path] = (open[path] ?: 0) + 1
    }

    @Synchronized
    fun closed(path: String) {
        val n = (open[path] ?: return) - 1
        if (n > 0) open[path] = n else open.remove(path)
    }

    @Synchronized
    fun isOpen(path: String): Boolean = path in open

    /** Runs [block] unless the book at [path] is open, which it keeps from opening meanwhile; throws [ChangeRefused] (in use) when it is. */
    @Synchronized
    fun <T> unlessOpen(path: String, block: () -> T): T {
        if (path in open) throw ChangeRefused(ChangeRefused.Why.IN_READER)
        return block()
    }
}
