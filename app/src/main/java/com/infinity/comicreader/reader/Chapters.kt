package com.infinity.comicreader.reader

import com.infinity.comicreader.util.Formats

/**
 * A chapter of a book: [count] pages from page [first] that share one folder inside the
 * archive, or one inner archive of a nested book. [name] is that folder's or inner
 * archive's name (an archive without its extension); null for pages outside any folder.
 */
data class Chapter(val name: String?, val first: Int, val count: Int) {
    val last: Int get() = first + count - 1

    operator fun contains(page: Int): Boolean = page in first..last
}

object Chapters {
    /**
     * The chapters of a book whose pages are stored at [paths], in page order. The
     * folders every page shares are left out, so chapters are the first level where the
     * pages part ways. A book in a single folder, or one with any page that has no path
     * (an image, a PDF), has none.
     */
    fun of(paths: List<String?>): List<Chapter> {
        if (paths.isEmpty() || paths.any { it == null }) return emptyList()
        val dirs = paths.map { folders(it!!) }
        val shared = sharedDepth(dirs)
        val out = ArrayList<Chapter>()
        var start = 0
        for (i in 1..dirs.size) {
            if (i < dirs.size && dirs[i].getOrNull(shared) == dirs[start].getOrNull(shared)) continue
            out += Chapter(label(dirs[start].getOrNull(shared)), start, i - start)
            start = i
        }
        return if (out.size < 2) emptyList() else out
    }

    /** The index of the chapter holding [page], or -1. */
    fun indexAt(chapters: List<Chapter>, page: Int): Int = chapters.indexOfFirst { page in it }

    /** The folders above a page, outermost first; `/` and `\` both separate. */
    private fun folders(path: String): List<String> =
        path.split('/', '\\').filter { it.isNotEmpty() }.dropLast(1)

    /** How many leading folders every page has in common. */
    private fun sharedDepth(dirs: List<List<String>>): Int {
        val first = dirs[0]
        var depth = 0
        while (depth < first.size && dirs.all { it.size > depth && it[depth] == first[depth] }) depth++
        return depth
    }

    private fun label(folder: String?): String? = when {
        folder == null -> null
        Formats.isArchiveName(folder) -> folder.substringBeforeLast('.')
        else -> folder
    }
}
