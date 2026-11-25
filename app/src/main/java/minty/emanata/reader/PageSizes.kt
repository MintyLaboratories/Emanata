package minty.emanata.reader

import minty.emanata.data.BookId
import minty.emanata.util.CacheFiles
import minty.emanata.util.Traces
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every page's `[width, height]` of a book, read from page headers once ([scan]) and
 * kept on disk under [dir]: one small file per book, named after the book's identity
 * ([PreviewStore.bookId], [CacheFiles.nameOf]) and a hash of it and its page names, so
 * a changed book never reads stale sizes. A size not known is kept as 0 x 0 and read
 * back as null. Each read marks the file used; the least recently used go first once
 * the store passes [MAX_BYTES] ([trim]).
 */
class PageSizes(val dir: File) {

    /** The sizes stored for book [bookId] with pages [names]; null when none are. */
    fun read(bookId: String, names: List<String?>): Array<IntArray?>? {
        val file = fileOf(bookId, names)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION) return null
                val count = input.readInt()
                if (count != names.size) return null
                Array(count) {
                    val w = input.readInt()
                    val h = input.readInt()
                    if (w > 0 && h > 0) intArrayOf(w, h) else null
                }
            }
        }.getOrNull()?.also { CacheFiles.touch(file) }
    }

    /** Stores [sizes] of book [bookId] with pages [names]; a file appears whole or not at all. */
    fun write(bookId: String, names: List<String?>, sizes: Array<IntArray?>) {
        val file = fileOf(bookId, names)
        val part = File(file.path + ".part")
        try {
            dir.mkdirs()
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(sizes.size)
                for (s in sizes) {
                    out.writeInt(s?.getOrNull(0) ?: 0)
                    out.writeInt(s?.getOrNull(1) ?: 0)
                }
            }
            if (!part.renameTo(file)) throw IOException("Could not store ${file.name}")
        } catch (e: IOException) {
            part.delete()
        }
        if (WRITES.incrementAndGet() % TRIM_EVERY == 0) trim(dir)
    }

    /**
     * Stores under book [to] with pages [toNames] the sizes kept for book [from] with pages
     * [fromNames]: page `i`'s as page [map]`[i]`'s, none where -1, over what [to] has there;
     * with [move], [from]'s go. Nothing when [from] has none.
     */
    fun carry(from: String, fromNames: List<String?>, to: String, toNames: List<String?>, map: IntArray, move: Boolean) {
        val old = read(from, fromNames) ?: return
        val out = read(to, toNames) ?: arrayOfNulls(toNames.size)
        var any = false
        for (i in old.indices) {
            val j = map.getOrElse(i) { -1 }
            val size = old[i] ?: continue
            if (j !in out.indices) continue
            out[j] = size
            any = true
        }
        if (any) write(to, toNames, out)
        if (move) fileOf(from, fromNames).delete()
    }

    private fun fileOf(bookId: String, names: List<String?>) =
        File(dir, CacheFiles.nameOf(bookId, bookId + "\u0000" + names.joinToString("\u0000") { it.orEmpty() }) + ".bin")

    companion object {
        private const val VERSION = 1

        /** Upper bound of the sizes kept on disk: some eight thousand books. */
        const val MAX_BYTES = 32L * 1024 * 1024

        /** Writes between trims while the app runs; the store is trimmed at startup too. */
        private const val TRIM_EVERY = 64

        /** Sizes written since the app started, by every store. */
        private val WRITES = AtomicInteger()

        /** Where sizes are kept under [cacheDir], by book ID. */
        fun dirIn(cacheDir: File) = File(cacheDir, "page-sizes-by-id")

        /** Keeps [dir] under [maxBytes], the books least recently read going first. */
        fun trim(dir: File, maxBytes: Long = MAX_BYTES) = CacheFiles.trim(dir, maxBytes)

        /** Deletes the sizes kept for book [id] in [dir]. */
        fun forget(dir: File, id: BookId) = CacheFiles.forget(dir, id.hex)

        /**
         * Fills the unknown entries of [sizes] from [source]'s page headers, nearest page
         * [from] first ([outward]), until [canceled]; returns false when canceled first. Blocking.
         */
        fun scan(source: PageSource, sizes: Array<IntArray?>, from: Int = 0, canceled: () -> Boolean = { false }): Boolean {
            for (i in outward(sizes.size, from)) {
                if (canceled()) return false
                if (sizes[i] == null) sizes[i] = Traces.section("page size") { runCatching { source.pageSize(i) }.getOrNull() }
            }
            return true
        }

        /** Every index below [count], [from] first, then the next one ahead and the next one back in turn. */
        fun outward(count: Int, from: Int): List<Int> {
            val start = from.coerceIn(0, maxOf(count - 1, 0))
            val out = ArrayList<Int>(count)
            var step = 0
            while (out.size < count) {
                if (start + step < count) out += start + step
                if (step > 0 && start - step >= 0) out += start - step
                step++
            }
            return out
        }
    }
}
