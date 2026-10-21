package com.infinity.comicreader.reader

import com.infinity.comicreader.util.Formats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which pages of one book are worth reading for a quick preview ([Decoders.preview]),
 * judged by name before any read: extensions of formats that may have one
 * ([Decoders.QUICK_EXTENSIONS]) or of no known image format. An extension that has
 * missed ([found] false) more often than not in this book is not read again.
 */
class QuickPreviews {
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val misses = ConcurrentHashMap<String, AtomicInteger>()

    /** True when page [name] may have a quick preview. */
    fun worthReading(name: String?): Boolean {
        val ext = Formats.ext(name.orEmpty())
        if (ext in Formats.IMAGE && ext !in Decoders.QUICK_EXTENSIONS) return false
        return count(misses, ext) <= count(hits, ext)
    }

    /** Records whether page [name], read for one, had a quick preview. */
    fun found(name: String?, quick: Boolean) {
        val ext = Formats.ext(name.orEmpty())
        (if (quick) hits else misses).getOrPut(ext) { AtomicInteger() }.incrementAndGet()
    }

    private fun count(map: ConcurrentHashMap<String, AtomicInteger>, ext: String) = map[ext]?.get() ?: 0
}
