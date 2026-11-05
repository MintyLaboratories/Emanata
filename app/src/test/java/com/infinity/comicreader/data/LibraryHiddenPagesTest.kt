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

    private val a = BookRef(BookId(0xa), "/books/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/b.cbz")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    @Test
    fun aBookKeepsItsOwnHiddenPages() {
        assertTrue(library.hiddenPages(a.id).isEmpty())
        library.setHiddenPages(a, setOf(2, 9), 10)
        assertEquals(setOf(2, 9), library.hiddenPages(a.id))
        assertTrue(library.hiddenPages(b.id).isEmpty())
        assertEquals(mapOf(a.id to 2), library.hiddenCounts())
        // Pages past the end are never kept.
        library.setHiddenPages(a, setOf(2, 12), 10)
        assertEquals(setOf(2), library.hiddenPages(a.id))
        library.setHiddenPages(a, emptySet(), 10)
        assertTrue(library.hiddenPages(a.id).isEmpty())
        assertTrue(library.hiddenCounts().isEmpty())
    }

    @Test
    fun allHiddenPagesAreParsedOncePerChange() {
        library.setHiddenPages(a, setOf(0, 4), 10)
        val first = library.hiddenAll()
        assertEquals(mapOf(a.id to setOf(0, 4)), first.byId)
        assertEquals(mapOf(a.path to setOf(0, 4)), first.byPath)
        assertTrue(first === library.hiddenAll())
        library.setHiddenPages(b, setOf(1), 10)
        assertEquals(mapOf(a.id to setOf(0, 4), b.id to setOf(1)), library.hiddenAll().byId)
    }

    @Test
    fun aBookKeepsTheCoverItWasGivenUntilCleared() {
        assertEquals(null, library.coverPage(a.id))
        library.setCoverPage(a, 4)
        assertEquals(4, library.coverPage(a.id))
        assertEquals(mapOf(a.path to 4), library.hiddenAll().coversByPath)
        assertTrue(library.kept().getValue(a.id).settings)
        // The first page is no choice: nothing is kept.
        library.setCoverPage(a, 0)
        assertEquals(null, library.coverPage(a.id))
        library.setCoverPage(a, 4)
        // It follows its page when pages move, and goes with it.
        library.movePages(a.id, intArrayOf(0, 1, 2, 3, 6, 4, 5), 7)
        assertEquals(6, library.coverPage(a.id))
        library.movePages(a.id, intArrayOf(0, 1, 2, 3, 4, 5, -1), 6)
        assertEquals(null, library.coverPage(a.id))
        library.setCoverPage(a, 2)
        library.carryOver(a.id, b, IntArray(10) { it }, 10)
        assertEquals(2, library.coverPage(b.id))
        assertEquals(null, library.coverPage(a.id))
        library.clearLibraryData()
        assertEquals(null, library.coverPage(b.id))
        library.setCoverPage(a, 1)
        val backup = library.exportAll()
        library.forgetBooks(setOf(a.id))
        assertEquals(null, library.coverPage(a.id))
        library.importAll(backup)
        assertEquals(1, library.coverPage(a.id))
    }

    @Test
    fun historyAndBookmarksStayOnTheirPages() {
                library.addHistory(HistoryItem(a.path, "a.cbz", 6, 10, 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 3, "", 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 8, "", 2, a.id))
        library.addBookmark(Bookmark(b.path, "b.cbz", 3, "", 3, b.id))
        library.setHiddenPages(a, setOf(1, 3), 10)
        // Page 6 of 10 is shown page 4 of 8; the bookmark on hidden page 3 moves to the next shown, page 4 (shown 2).
        assertEquals(4, library.pageFor(a.id))
        assertEquals(8, library.historyFor(a.id)!!.total)
        assertEquals(listOf(2, 6), library.bookmarksFor(a.id).map { it.page })
        assertEquals(listOf(3), library.bookmarksFor(b.id).map { it.page })
        // Showing them again brings everything back, except the bookmark that moved.
        library.setHiddenPages(a, emptySet(), 10)
        assertEquals(6, library.pageFor(a.id))
        assertEquals(10, library.historyFor(a.id)!!.total)
        assertEquals(listOf(4, 8), library.bookmarksFor(a.id).map { it.page })
    }

    @Test
    fun bookmarksMovedOntoOneAnotherBecomeOne() {
                library.addBookmark(Bookmark(a.path, "a.cbz", 2, "", 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 3, "", 2, a.id))
        library.setHiddenPages(a, setOf(2), 5)
        assertEquals(listOf(2), library.bookmarksFor(a.id).map { it.page })
    }

    @Test
    fun aBookmarkMovedOntoAnotherKeepsBothNotes() {
                // Newest first: the moved bookmark comes before the one already on page 3.
        library.addBookmark(Bookmark(a.path, "a.cbz", 3, "kept note", 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 2, "moved note", 2, a.id))
        library.setHiddenPages(a, setOf(2), 5)
        val marks = library.bookmarksFor(a.id)
        assertEquals(1, marks.size)
        assertEquals(2, marks[0].page)
        assertEquals(1L, marks[0].time)
        assertEquals("kept note\nmoved note", marks[0].note)
        // Showing the page again leaves the bookmark on its own page.
        library.setHiddenPages(a, emptySet(), 5)
        assertEquals(listOf(3), library.bookmarksFor(a.id).map { it.page })
    }

    @Test
    fun hiddenPagesFollowTheBookNotItsPath() {
        val moved = BookRef(BookId(0xc), "/books/old/c.cbz")
        library.setHiddenPages(moved, setOf(0), 4)
        library.replacePath("/books/old", "/books/new")
        assertEquals(setOf(0), library.hiddenPages(moved.id))
        assertEquals(mapOf("/books/new/c.cbz" to setOf(0)), library.hiddenAll().byPath)
        // Deleting a copy leaves them for the book's other copies.
        library.removePathsUnder("/books/new")
        assertEquals(setOf(0), library.hiddenPages(moved.id))
    }

    @Test
    fun hiddenPagesAreBackedUp() {
        library.setHiddenPages(a, setOf(5), 8)
        val backup = library.exportAll()
        library.setHiddenPages(a, emptySet(), 8)
        library.importAll(backup)
        assertEquals(setOf(5), library.hiddenPages(a.id))
    }

    @Test
    fun aBackupWithoutHiddenPagesShowsEveryPage() {
                // An older backup, its pages counted with none hidden.
        library.addHistory(HistoryItem(a.path, "a.cbz", 6, 10, 1, a.id))
        val backup = library.exportAll().apply { remove("hidden_pages"); remove("bookmarks") }
        library.addBookmark(Bookmark(a.path, "a.cbz", 8, "", 1, a.id))
        library.setHiddenPages(a, setOf(1, 3), 10)
        library.importAll(backup)
        assertTrue(library.hiddenPages(a.id).isEmpty())
        assertEquals(6, library.pageFor(a.id))
        assertEquals(10, library.historyFor(a.id)!!.total)
        // Bookmarks the backup leaves alone move back to their book pages.
        assertEquals(listOf(8), library.bookmarksFor(a.id).map { it.page })
    }

    @Test
    fun aBackupOfSettingsAloneKeepsHiddenPages() {
        val backup = library.exportAll().apply {
            remove("hidden_pages"); remove("history"); remove("bookmarks")
        }
        library.setHiddenPages(a, setOf(2), 5)
        library.importAll(backup)
        assertEquals(setOf(2), library.hiddenPages(a.id))
    }

    @Test
    fun hiddenPagesHistoryAndBookmarksMoveWithTheirPages() {
                // Book pages 0..4 go to 4, 3, 2, 1, 0: the order turned around.
        val moves = intArrayOf(4, 3, 2, 1, 0)
        library.setHiddenPages(a, setOf(1), 5)
        // Shown page 2 is book page 3, which goes to book page 1: shown 1 once page 3 is hidden.
        library.addHistory(HistoryItem(a.path, "a.cbz", 2, 4, 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 0, "first", 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 3, "last", 2, a.id))
        library.addBookmark(Bookmark(b.path, "b.cbz", 0, "", 3, b.id))
        library.movePages(a.id, moves, 5)
        assertEquals(setOf(3), library.hiddenPages(a.id))
        assertEquals(1, library.pageFor(a.id))
        assertEquals(4, library.historyFor(a.id)!!.total)
        // Book page 0 goes to 4 (shown 3); book page 4 (shown 3 before) goes to 0.
        assertEquals(mapOf(3 to "first", 0 to "last"), library.bookmarksFor(a.id).associate { it.page to it.note })
        assertEquals(listOf(0), library.bookmarksFor(b.id).map { it.page })
    }

    @Test
    fun pagesGoneLeaveWhatWasOnThem() {
                library.setHiddenPages(a, setOf(2), 4)
        library.addHistory(HistoryItem(a.path, "a.cbz", 1, 3, 1, a.id))
        // Book page 1 is gone, page 2 (hidden) too; page 3 becomes page 1.
        library.movePages(a.id, intArrayOf(0, -1, -1, 1), 2)
        assertTrue(library.hiddenPages(a.id).isEmpty())
        assertEquals(1, library.pageFor(a.id))
        assertEquals(2, library.historyFor(a.id)!!.total)
    }
}
