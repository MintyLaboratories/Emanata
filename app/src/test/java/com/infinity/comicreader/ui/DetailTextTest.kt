package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DetailTextTest {

    private val mb = { bytes: Long -> "${bytes / 1_000_000} MB" }

    private fun folder(count: Int, read: Int) =
        BookEntry(File("/books/Set"), "Set", true, EntryType.FOLDER, 0, 0, childCount = count, readCount = read)

    private fun book(pages: Int = 192, progress: Int = -1, read: Boolean = false, size: Long = 87_000_000, bookmark: Int = -1) =
        BookEntry(
            File("/books/a.cbz"), "a.cbz", false, EntryType.ARCHIVE, size, 0,
            pages = pages, progressPage = progress, read = read, bookmarkPage = bookmark
        )

    @Test
    fun foldersCountReadItems() {
        assertEquals("12", DetailText.dense(folder(12, 0), mb))
        assertEquals("3/12", DetailText.dense(folder(12, 3), mb))
        assertEquals("12/12", DetailText.dense(folder(12, 12), mb))
        assertEquals("", DetailText.dense(folder(-1, -1), mb))
    }

    @Test
    fun booksCountPagesThenSize() {
        assertEquals("192 · 87 MB", DetailText.dense(book(), mb))
        assertEquals("40/192 · 87 MB", DetailText.dense(book(progress = 39), mb))
        assertEquals("192/192 · 87 MB", DetailText.dense(book(progress = 39, read = true), mb))
        // The first page is no progress yet; a bookmark there still counts.
        assertEquals("192 · 87 MB", DetailText.dense(book(progress = 0), mb))
        assertEquals("1/192 · 87 MB", DetailText.dense(book(progress = 0, bookmark = 0), mb))
    }

    @Test
    fun bookmarksShowTheirPage() {
        // A bookmark in a finished book still shows its own page.
        assertEquals("41/192 · 87 MB", DetailText.dense(book(progress = 40, read = true, bookmark = 40), mb))
        // With no total known, the page alone.
        assertEquals("41", DetailText.dense(book(pages = -1, progress = 40, size = 0, bookmark = 40), mb))
    }

    @Test
    fun unknownPartsAreLeftOut() {
        assertEquals("87 MB", DetailText.dense(book(pages = -1, progress = 5), mb))
        assertEquals("40/192", DetailText.dense(book(progress = 39, size = 0), mb))
        assertEquals("", DetailText.dense(book(pages = -1, size = 0), mb))
    }

    @Test
    fun aNoteGoesFirst() {
        assertEquals("Missing · Page 4", DetailText.withNote("Missing", "Page 4"))
        assertEquals("Missing", DetailText.withNote("Missing", ""))
        assertEquals("Page 4", DetailText.withNote(null, "Page 4"))
    }
}
