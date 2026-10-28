package com.infinity.comicreader.net

import com.infinity.comicreader.data.BookIdBytes

/**
 * The first and last bytes of share files just read for their Book ID, as the ID's
 * bytes ([BookIdBytes]) hold them, so what opens such a file next (its cover, its
 * contents) starts from them instead of asking the share again ([RemoteChannel.open]).
 * Only the latest few are kept; one whose file changed since is never handed out.
 */
object FileEnds {
    private const val KEPT = 16

    /** Each file's ID bytes with the size and modification time they were read at, latest last. */
    private class Ends(val size: Long, val modified: Long, val layout: ByteArray)

    private val kept = object : LinkedHashMap<String, Ends>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ends>?) = size > KEPT
    }

    /** Keeps [layout], the ID bytes of the file at [path] read at [size] and [modified]. */
    fun put(path: String, size: Long, modified: Long, layout: ByteArray) {
        if (layout.size != BookIdBytes.length(size)) return
        synchronized(kept) { kept[path] = Ends(size, modified, layout) }
    }

    /**
     * The parts of the file at [path] kept, as (file position, bytes) pairs, when it was
     * read at [size] and [modified]; empty otherwise. The bytes are copies, the caller's to keep.
     */
    fun of(path: String, size: Long, modified: Long): List<Pair<Long, ByteArray>> {
        val ends = synchronized(kept) { kept[path] } ?: return emptyList()
        if (ends.size != size || ends.modified != modified) return emptyList()
        return BookIdBytes.ranges(size).map { (position, offset, length) -> position to ends.layout.copyOfRange(offset, offset + length) }
    }

    /** The first bytes kept of the file at [path], up to [count], when read at [size] and [modified]; null otherwise. */
    fun head(path: String, size: Long, modified: Long, count: Int): ByteArray? {
        val ends = synchronized(kept) { kept[path] } ?: return null
        if (ends.size != size || ends.modified != modified) return null
        val n = minOf(count.toLong(), ends.size, BookIdBytes.END_BYTES.toLong()).toInt()
        return ends.layout.copyOfRange(BookIdBytes.SIZE_BYTES, BookIdBytes.SIZE_BYTES + n)
    }

    /** Forgets everything kept. */
    fun clear() = synchronized(kept) { kept.clear() }
}
