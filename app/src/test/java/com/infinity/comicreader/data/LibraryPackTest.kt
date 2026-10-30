package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What was kept for a folder of loose images and its images goes to the book they were packed into. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryPackTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val folder = "/books/Loose"
    private val images = (1..4).map { BookRef(BookId(0x100L + it), "$folder/$it.jpg") }
    private val book = BookRef(BookId(0xb00), "/books/Loose.cbz")
    private val other = BookRef(BookId(0xc00), "/books/Other.cbz")

    private fun carry() = library.carryFolder(folder, images.map { it.id }, book, images.size)

    @Test
    fun theFoldersFavoriteBecomesTheBooksInItsPlace() {
        library.toggleFavorite(other)
        library.toggleFavoriteFolder(folder)
        library.toggleFavoriteFolder("/books/Else")
        carry()
        assertTrue(library.isFavorite(book.id))
        assertFalse(library.isFavoriteFolder(folder))
        assertEquals(listOf("/books/Else", book.path, other.path), library.favoriteItems().map { it.path })
    }

    @Test
    fun anImagesFavoriteMakesTheBookOne() {
        library.toggleFavorite(images[2])
        carry()
        assertTrue(library.isFavorite(book.id))
        assertFalse(library.isFavorite(images[2].id))
    }

    @Test
    fun theBookIsReadOnlyWhenEveryImageWas() {
        library.setReadMany(images.take(3), true)
        carry()
        assertFalse(library.isRead(book.id))
        library.setRead(images[3], true)
        carry()
        assertTrue(library.isRead(book.id))
    }

    @Test
    fun historyResumesAtTheImageReadLast() {
        library.addHistory(HistoryItem(other.path, "Other.cbz", 3, 9, 50, other.id))
        library.addHistory(HistoryItem(images[0].path, "1.jpg", 0, 1, 100, images[0].id))
        library.addHistory(HistoryItem(images[2].path, "3.jpg", 0, 1, 300, images[2].id))
        library.addHistory(HistoryItem(images[1].path, "2.jpg", 0, 1, 200, images[1].id))
        carry()
        val item = library.historyFor(book.id)!!
        assertEquals(2, item.page)
        assertEquals(4, item.total)
        assertEquals(300, item.time)
        assertEquals("Loose.cbz", item.name)
        // In the place of the folder's newest row, before the other book's.
        assertEquals(book.id, library.history().first { it.id !in images.map { i -> i.id } }.id)
    }

    @Test
    fun bookmarksAndHiddenImagesMoveToTheirPages() {
        library.addBookmark(Bookmark(images[3].path, "4.jpg", 0, "last", 10, images[3].id))
        library.addBookmark(Bookmark(images[1].path, "2.jpg", 0, "hidden one", 20, images[1].id))
        library.addBookmark(Bookmark(images[2].path, "3.jpg", 0, "third", 30, images[2].id))
        library.setHiddenPages(images[1], setOf(0), 1)
        library.addHistory(HistoryItem(images[3].path, "4.jpg", 0, 1, 100, images[3].id))
        carry()
        assertEquals(setOf(1), library.hiddenPages(book.id))
        // Counted as shown: page 3 is the third shown; the hidden image's bookmark joins the next page shown.
        val marks = library.bookmarksFor(book.id)
        assertEquals(listOf(1, 2), marks.map { it.page })
        assertEquals("third\nhidden one", marks[0].note)
        assertEquals("last", marks[1].note)
        assertEquals(2, library.historyFor(book.id)!!.page)
        assertEquals(3, library.historyFor(book.id)!!.total)
    }

    @Test
    fun theFoldersLayoutAndTallAnswerBecomeTheBooks() {
        val layout = FolderLayout.Frozen(ReaderLayout(mode = ReaderLayout.MODE_CONTINUOUS, dir = ReaderLayout.DIR_TTB))
        library.setFolderLayout(folder, layout)
        library.setFolderTallStrip(folder, true)
        carry()
        assertEquals(layout, library.bookLayout(book.id))
        assertEquals(true, library.tallStrip(book.id))
        assertEquals(true, library.kept()[book.id]?.settings)
        assertEquals(book.path, library.pathOf(book.id))
    }

    @Test
    fun adjustmentsComeFromTheImageReadLast() {
        library.setAdjustments(images[0], Adjustments(10, 0, 0))
        library.setAdjustments(images[2], Adjustments(20, 0, 0))
        carry()
        assertEquals(10, library.adjustmentsFor(book.id)!!.brightness)
        library.addHistory(HistoryItem(images[2].path, "3.jpg", 0, 1, 100, images[2].id))
        carry()
        assertEquals(20, library.adjustmentsFor(book.id)!!.brightness)
    }

    @Test
    fun nothingKeptCarriesNothing() {
        carry()
        assertFalse(library.hasData(book.id))
        assertNull(library.bookLayout(book.id))
    }
}
