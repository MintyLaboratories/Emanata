package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryHiddenPagesTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    @Test
    fun aBookKeepsItsOwnHiddenPages() {
        assertTrue(library.hiddenPages("/books/a.cbz").isEmpty())
        library.setHiddenPages("/books/a.cbz", setOf(2, 9), 10)
        assertEquals(setOf(2, 9), library.hiddenPages("/books/a.cbz"))
        assertTrue(library.hiddenPages("/books/b.cbz").isEmpty())
        assertEquals(mapOf("/books/a.cbz" to 2), library.hiddenCounts())
        // Pages past the end are never kept.
        library.setHiddenPages("/books/a.cbz", setOf(2, 12), 10)
        assertEquals(setOf(2), library.hiddenPages("/books/a.cbz"))
        library.setHiddenPages("/books/a.cbz", emptySet(), 10)
        assertTrue(library.hiddenPages("/books/a.cbz").isEmpty())
        assertTrue(library.hiddenCounts().isEmpty())
    }

    @Test
    fun allHiddenPagesAreParsedOncePerChange() {
        library.setHiddenPages("/books/a.cbz", setOf(0, 4), 10)
        val first = library.hiddenAll()
        assertEquals(mapOf("/books/a.cbz" to setOf(0, 4)), first)
        assertTrue(first === library.hiddenAll())
        library.setHiddenPages("/books/b.cbz", setOf(1), 10)
        assertEquals(mapOf("/books/a.cbz" to setOf(0, 4), "/books/b.cbz" to setOf(1)), library.hiddenAll())
    }

    @Test
    fun historyAndBookmarksStayOnTheirPages() {
        val path = "/books/a.cbz"
        library.addHistory(HistoryItem(path, "a.cbz", 6, 10, 1))
        library.addBookmark(Bookmark(path, "a.cbz", 3, "", 1))
        library.addBookmark(Bookmark(path, "a.cbz", 8, "", 2))
        library.addBookmark(Bookmark("/books/b.cbz", "b.cbz", 3, "", 3))
        library.setHiddenPages(path, setOf(1, 3), 10)
        // Page 6 of 10 is shown page 4 of 8; the bookmark on hidden page 3 moves to the next shown, page 4 (shown 2).
        assertEquals(4, library.pageFor(path))
        assertEquals(8, library.historyFor(path)!!.total)
        assertEquals(listOf(2, 6), library.bookmarksFor(path).map { it.page })
        assertEquals(listOf(3), library.bookmarksFor("/books/b.cbz").map { it.page })
        // Showing them again brings everything back, except the bookmark that moved.
        library.setHiddenPages(path, emptySet(), 10)
        assertEquals(6, library.pageFor(path))
        assertEquals(10, library.historyFor(path)!!.total)
        assertEquals(listOf(4, 8), library.bookmarksFor(path).map { it.page })
    }

    @Test
    fun bookmarksMovedOntoOneAnotherBecomeOne() {
        val path = "/books/a.cbz"
        library.addBookmark(Bookmark(path, "a.cbz", 2, "", 1))
        library.addBookmark(Bookmark(path, "a.cbz", 3, "", 2))
        library.setHiddenPages(path, setOf(2), 5)
        assertEquals(listOf(2), library.bookmarksFor(path).map { it.page })
    }

    @Test
    fun aBookmarkMovedOntoAnotherKeepsBothNotes() {
        val path = "/books/a.cbz"
        // Newest first: the moved bookmark comes before the one already on page 3.
        library.addBookmark(Bookmark(path, "a.cbz", 3, "kept note", 1))
        library.addBookmark(Bookmark(path, "a.cbz", 2, "moved note", 2))
        library.setHiddenPages(path, setOf(2), 5)
        val marks = library.bookmarksFor(path)
        assertEquals(1, marks.size)
        assertEquals(2, marks[0].page)
        assertEquals(1L, marks[0].time)
        assertEquals("kept note\nmoved note", marks[0].note)
        // Showing the page again leaves the bookmark on its own page.
        library.setHiddenPages(path, emptySet(), 5)
        assertEquals(listOf(3), library.bookmarksFor(path).map { it.page })
    }

    @Test
    fun hiddenPagesFollowRenamesAndDeletes() {
        library.setHiddenPages("/books/old/a.cbz", setOf(0), 4)
        library.setHiddenPages("/books/keep.cbz", setOf(1), 4)
        library.replacePath("/books/old", "/books/new")
        assertTrue(library.hiddenPages("/books/old/a.cbz").isEmpty())
        assertEquals(setOf(0), library.hiddenPages("/books/new/a.cbz"))
        library.removePathsUnder("/books/new")
        assertTrue(library.hiddenPages("/books/new/a.cbz").isEmpty())
        assertEquals(setOf(1), library.hiddenPages("/books/keep.cbz"))
    }

    @Test
    fun hiddenPagesAreBackedUp() {
        library.setHiddenPages("/books/a.cbz", setOf(5), 8)
        val backup = library.exportAll()
        library.setHiddenPages("/books/a.cbz", emptySet(), 8)
        library.importAll(backup)
        assertEquals(setOf(5), library.hiddenPages("/books/a.cbz"))
    }

    @Test
    fun aBackupWithoutHiddenPagesShowsEveryPage() {
        val path = "/books/a.cbz"
        // An older backup, its pages counted with none hidden.
        library.addHistory(HistoryItem(path, "a.cbz", 6, 10, 1))
        val backup = library.exportAll().apply { remove("hidden_pages"); remove("bookmarks") }
        library.addBookmark(Bookmark(path, "a.cbz", 8, "", 1))
        library.setHiddenPages(path, setOf(1, 3), 10)
        library.importAll(backup)
        assertTrue(library.hiddenPages(path).isEmpty())
        assertEquals(6, library.pageFor(path))
        assertEquals(10, library.historyFor(path)!!.total)
        // Bookmarks the backup leaves alone move back to their book pages.
        assertEquals(listOf(8), library.bookmarksFor(path).map { it.page })
    }

    @Test
    fun aBackupOfSettingsAloneKeepsHiddenPages() {
        val backup = library.exportAll().apply {
            remove("hidden_pages"); remove("history"); remove("bookmarks")
        }
        library.setHiddenPages("/books/a.cbz", setOf(2), 5)
        library.importAll(backup)
        assertEquals(setOf(2), library.hiddenPages("/books/a.cbz"))
    }

    @Test
    fun hiddenPagesHistoryAndBookmarksMoveWithTheirPages() {
        val path = "/books/a.cbz"
        // Book pages 0..4 go to 4, 3, 2, 1, 0: the order turned around.
        val moves = intArrayOf(4, 3, 2, 1, 0)
        library.setHiddenPages(path, setOf(1), 5)
        // Shown page 2 is book page 3, which goes to book page 1: shown 1 once page 3 is hidden.
        library.addHistory(HistoryItem(path, "a.cbz", 2, 4, 1))
        library.addBookmark(Bookmark(path, "a.cbz", 0, "first", 1))
        library.addBookmark(Bookmark(path, "a.cbz", 3, "last", 2))
        library.addBookmark(Bookmark("/books/b.cbz", "b.cbz", 0, "", 3))
        library.movePages(path, moves, 5)
        assertEquals(setOf(3), library.hiddenPages(path))
        assertEquals(1, library.pageFor(path))
        assertEquals(4, library.historyFor(path)!!.total)
        // Book page 0 goes to 4 (shown 3); book page 4 (shown 3 before) goes to 0.
        assertEquals(mapOf(3 to "first", 0 to "last"), library.bookmarksFor(path).associate { it.page to it.note })
        assertEquals(listOf(0), library.bookmarksFor("/books/b.cbz").map { it.page })
    }

    @Test
    fun pagesGoneLeaveWhatWasOnThem() {
        val path = "/books/a.cbz"
        library.setHiddenPages(path, setOf(2), 4)
        library.addHistory(HistoryItem(path, "a.cbz", 1, 3, 1))
        // Book page 1 is gone, page 2 (hidden) too; page 3 becomes page 1.
        library.movePages(path, intArrayOf(0, -1, -1, 1), 2)
        assertTrue(library.hiddenPages(path).isEmpty())
        assertEquals(1, library.pageFor(path))
        assertEquals(2, library.historyFor(path)!!.total)
    }
}
