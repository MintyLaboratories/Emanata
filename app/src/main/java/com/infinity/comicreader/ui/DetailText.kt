package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry

/** Dense details, "done/total", free of Android types. */
object DetailText {
    const val SEPARATOR = " · "

    /**
     * Folders: their item count, after the read count once any is read (`12`,
     * `3/12`, `12/12`). Books: their pages, after the page reached once past the
     * first (or the bookmarked page, or all pages once read), then [size] written
     * by [sizeText] (`192 · 87 MB`, `40/192 · 87 MB`). Empty when nothing is known.
     */
    fun dense(entry: BookEntry, sizeText: (Long) -> String): String {
        if (entry.isDir) {
            val count = entry.childCount
            if (count < 0) return ""
            return if (entry.readCount > 0) "${entry.readCount}/$count" else "$count"
        }
        val parts = ArrayList<String>(2)
        val pages = entry.pages
        if (pages > 0) {
            val done = when {
                entry.read -> pages
                entry.bookmarkPage >= 0 -> entry.bookmarkPage + 1
                entry.progressPage > 0 -> entry.progressPage + 1
                else -> 0
            }
            parts += if (done > 0) "${done.coerceAtMost(pages)}/$pages" else "$pages"
        }
        if (entry.size > 0) parts += sizeText(entry.size)
        return parts.joinToString(SEPARATOR)
    }
}
