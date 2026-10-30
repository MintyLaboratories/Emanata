package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry

/** Dense details, "done/total", free of Android types. */
object DetailText {
    const val SEPARATOR = " · "

    /**
     * Folders: their item count, after the read count once any is read (`12`,
     * `3/12`, `12/12`). Books: their pages, after the bookmarked page, or else the
     * page reached once past the first (all pages once read), then [size] written
     * by [sizeText] (`192 · 87 MB`, `40/192 · 87 MB`). A bookmark with unknown pages
     * shows its page alone (`41`). Empty when nothing is known.
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
                entry.bookmarkPage >= 0 -> entry.bookmarkPage + 1
                entry.read -> pages
                entry.progressPage > 0 -> entry.progressPage + 1
                else -> 0
            }
            parts += if (done > 0) "${done.coerceAtMost(pages)}/$pages" else "$pages"
        } else if (entry.bookmarkPage >= 0) {
            parts += "${entry.bookmarkPage + 1}"
        }
        if (entry.size > 0) parts += sizeText(entry.size)
        return parts.joinToString(SEPARATOR)
    }

    /** [details] after [note] when there is one (`Missing · Page 4`); [details] alone otherwise. */
    fun withNote(note: String?, details: String): String = when {
        note == null -> details
        details.isEmpty() -> note
        else -> note + SEPARATOR + details
    }
}
