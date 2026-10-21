package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Chapter
import java.io.File

/**
 * The chapter drawer's rows: "Chapters in this book" (its folders or inner archives) and
 * "In this folder" (the book and its sibling files), each section only when it has a choice.
 */
object ChapterDrawer {
    sealed class Row {
        /** A section heading. */
        data class Header(val inBook: Boolean) : Row()

        /** A chapter of the open book; [current] holds the page being read. */
        data class Part(val chapter: Chapter, val current: Boolean) : Row()

        /** A file in the book's folder; [current] is the open book. */
        data class Sibling(val file: File, val current: Boolean, val read: Boolean) : Row()
    }

    /** True when the drawer has anything to offer. */
    fun hasEntries(bookChapters: List<Chapter>, siblings: List<File>): Boolean =
        bookChapters.isNotEmpty() || siblings.size > 1

    /**
     * The rows for a book with [bookChapters], read at [page], among [siblings]; [open] is
     * the book's path and [read] the paths read to the end.
     */
    fun rows(bookChapters: List<Chapter>, page: Int, siblings: List<File>, open: String?, read: Set<String>): List<Row> {
        val out = ArrayList<Row>()
        if (bookChapters.isNotEmpty()) {
            out += Row.Header(inBook = true)
            for (c in bookChapters) out += Row.Part(c, page in c)
        }
        if (siblings.size > 1) {
            out += Row.Header(inBook = false)
            for (f in siblings) out += Row.Sibling(f, f.absolutePath == open, f.absolutePath in read)
        }
        return out
    }

    /** The row to show near the top: the current chapter, else the open book, else the first. */
    fun currentRow(rows: List<Row>): Int {
        val part = rows.indexOfFirst { it is Row.Part && it.current }
        if (part >= 0) return part
        return rows.indexOfFirst { it is Row.Sibling && it.current }.coerceAtLeast(0)
    }
}
