package com.infinity.comicreader.reader

import com.infinity.comicreader.util.Hashes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Every page's `[width, height]` of a book, read from page headers once ([scan]) and
 * kept on disk under [dir]: one small file per book, named by a hash of the book's
 * identity ([PreviewStore.bookId]) and its page names, so a changed book never reads
 * stale sizes. A size not known is kept as 0 x 0 and read back as null.
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
        }.getOrNull()
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
    }

    private fun fileOf(bookId: String, names: List<String?>) =
        File(dir, Hashes.sha256Hex(bookId + "\u0000" + names.joinToString("\u0000") { it.orEmpty() }) + ".bin")

    companion object {
        private const val VERSION = 1

        /** Where sizes are kept under [cacheDir], by book ID. */
        fun dirIn(cacheDir: File) = File(cacheDir, "page-sizes-by-id")

        /**
         * Fills the unknown entries of [sizes] from [source]'s page headers, nearest page
         * [from] first ([outward]), until [canceled]; returns false when canceled first. Blocking.
         */
        fun scan(source: PageSource, sizes: Array<IntArray?>, from: Int = 0, canceled: () -> Boolean = { false }): Boolean {
            for (i in outward(sizes.size, from)) {
                if (canceled()) return false
                if (sizes[i] == null) sizes[i] = runCatching { source.pageSize(i) }.getOrNull()
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
