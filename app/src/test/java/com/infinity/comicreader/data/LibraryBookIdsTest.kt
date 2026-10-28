package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.testing.bookId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the library keeps for a book is kept by its Book ID, with where it was last seen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryBookIdsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library

    private val a = BookRef(BookId(0xa), "/storage/emulated/0/Comics/Series/a.cbz")
    private val b = BookRef(BookId(0xb), "/net/s1/Comics/b.cbz")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    @Test
    fun booksAreKeptWithWhereTheyWereLastSeen() {
        library.setRead(a, true)
        library.addHistory(HistoryItem(b.path, "b.cbz", 3, 10, 1, b.id))
        assertEquals(Library.Seen(a.path, BookLocation.DEVICE), library.seenAt()[a.id])
        assertEquals(Library.Seen(b.path, "share:s1"), library.seenAt()[b.id])
        // Seen elsewhere, a book's lists follow it there.
        library.seen(BookRef(b.id, "/storage/1234-ABCD/b.cbz"))
        assertEquals("/storage/1234-ABCD/b.cbz", library.history().single().path)
        assertEquals("volume:1234-ABCD", library.seenAt()[b.id]!!.location)
        // A book with nothing kept is not noted.
        library.seen(BookRef(BookId(0xc), "/elsewhere/c.cbz"))
        assertNull(library.seenAt()[BookId(0xc)])
    }

    @Test
    fun favoritesHoldFoldersByPathAndBooksById() {
        assertTrue(library.toggleFavoriteFolder("/storage/emulated/0/Comics"))
        assertTrue(library.toggleFavorite(a))
        assertEquals(listOf(Library.Favorite(a.path, a.id), Library.Favorite("/storage/emulated/0/Comics", null)), library.favoriteItems())
        assertEquals(listOf(a.path, "/storage/emulated/0/Comics"), library.favorites())
        assertTrue(library.isFavorite(a.id))
        assertFalse(library.isFavorite(b.id))
        assertTrue(library.isFavoriteFolder("/storage/emulated/0/Comics"))
        assertEquals(setOf(a.id), library.favoriteBooks())
        assertEquals(setOf("/storage/emulated/0/Comics"), library.favoriteFolders())
        // The same book found at another path is the same favorite.
        assertFalse(library.toggleFavorite(BookRef(a.id, "/elsewhere/copy.cbz")))
        assertTrue(library.favoriteBooks().isEmpty())
    }

    @Test
    fun readMarksBookmarksAndHistoryAreKeptById() {
        library.setReadMany(listOf(a, b), true)
        assertEquals(setOf(a.id, b.id), library.readSet())
        library.setRead(BookRef(a.id, "/elsewhere/copy.cbz"), false)
        assertEquals(setOf(b.id), library.readSet())
        library.addBookmark(Bookmark(a.path, "a.cbz", 4, "note", 2, a.id))
        assertTrue(library.isBookmarked(a.id, 4))
        assertEquals(listOf(4), library.bookmarksFor(a.id).map { it.page })
        library.addHistory(HistoryItem(a.path, "a.cbz", 7, 20, 3, a.id))
        assertEquals(7, library.pageFor(a.id))
        assertEquals(0, library.pageFor(b.id))
        library.removeBookmark(a.id, 4)
        assertTrue(library.bookmarksFor(a.id).isEmpty())
    }

    @Test
    fun theAppsMovesRepointFoldersAndWhereBooksWereSeen() {
        library.toggleFavoriteFolder("/storage/emulated/0/Comics/Series")
        library.setFolderLayout("/storage/emulated/0/Comics/Series", FolderLayout.Direction("rtl"))
        library.toggleFavorite(a)
        library.replacePath("/storage/emulated/0/Comics/Series", "/storage/emulated/0/Comics/Renamed")
        assertEquals(
            listOf("/storage/emulated/0/Comics/Renamed/a.cbz", "/storage/emulated/0/Comics/Renamed"),
            library.favorites()
        )
        assertTrue(library.folderLayout("/storage/emulated/0/Comics/Renamed") != null)
        assertNull(library.folderLayout("/storage/emulated/0/Comics/Series"))
    }

    @Test
    fun deletingDropsListsButKeepsWhatCopiesShare() {
        library.toggleFavorite(a)
        library.setRead(a, true)
        library.addHistory(HistoryItem(a.path, "a.cbz", 1, 5, 1, a.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 2, "", 1, a.id))
        library.setHiddenPages(a, setOf(0), 5)
        library.toggleFavorite(b)
        library.removePathsUnder("/storage/emulated/0/Comics")
        assertEquals(listOf(b.path), library.favorites())
        assertTrue(library.history().isEmpty())
        assertTrue(library.bookmarks().isEmpty())
        assertTrue(library.isRead(a.id))
        assertEquals(setOf(0), library.hiddenPages(a.id))
    }

    @Test
    fun deletingOneOfTwoCopiesKeepsTheListsAtTheOther() {
        val one = tmp.newFolder("one")
        val two = tmp.newFolder("two")
        val first = File(one, "a.cbz").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val copy = File(two, "copy.cbz").apply { writeBytes(first.readBytes()) }
        val gone = File(one, "gone.cbz").apply { writeBytes(ByteArray(200) { 7 }) }
        val book = BookRef(bookId(first), first.absolutePath)
        assertEquals(book.id, bookId(copy))
        val lone = BookRef(bookId(gone), gone.absolutePath)
        library.toggleFavorite(book)
        library.addHistory(HistoryItem(book.path, "a.cbz", 1, 5, 1, book.id))
        library.addBookmark(Bookmark(book.path, "a.cbz", 2, "", 1, book.id))
        library.addBookmark(Bookmark(lone.path, "gone.cbz", 2, "", 1, lone.id))
        one.deleteRecursively()
        library.removePathsUnder(one.absolutePath)
        assertEquals(copy.absolutePath, library.pathOf(book.id))
        assertTrue(library.isFavorite(book.id))
        assertEquals(listOf(copy.absolutePath), library.history().map { it.path })
        assertEquals(listOf(book.id), library.bookmarks().map { it.id })
    }

    @Test
    fun removingAShareDropsItsBooksFromTheLists() {
        library.toggleFavorite(b)
        library.addHistory(HistoryItem(b.path, "b.cbz", 1, 5, 1, b.id))
        library.toggleFavorite(a)
        library.removeListsUnder("/net/s1")
        assertEquals(listOf(a.path), library.favorites())
        assertTrue(library.history().isEmpty())
    }

    @Test
    fun backupsCarryBooksByIdWithWhereTheyWereSeen() {
        library.toggleFavorite(a)
        library.setRead(b, true)
        val backup = library.exportAll()
        assertEquals(a.path, backup.getJSONObject("books").getJSONObject(a.id.hex).getString("path"))
        assertEquals("share:s1", backup.getJSONObject("books").getJSONObject(b.id.hex).getString("loc"))
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Library(context).importAll(JSONObject(backup.toString()))
        assertEquals(listOf(a.path), library.favorites())
        assertTrue(library.isRead(b.id))
    }

    @Test
    fun aBackupKeptByPathRestoresOnlyFolders() {
        val old = JSONObject()
            .put("favorites", JSONArray().put("/storage/emulated/0/Comics").put("/storage/emulated/0/Comics/a.cbz"))
            .put("read", JSONArray().put("/storage/emulated/0/Comics/a.cbz"))
            .put("history", JSONArray().put(JSONObject().put("path", "/storage/emulated/0/Comics/a.cbz").put("page", 3)))
            .put("hidden_pages", JSONObject().put("/storage/emulated/0/Comics/a.cbz", JSONArray().put(1)))
        library.importAll(old)
        assertTrue(library.readSet().isEmpty())
        assertTrue(library.history().isEmpty())
        assertTrue(library.hiddenAll().isEmpty())
        // A path left in favorites reads as a folder, as one that is not there shows.
        assertTrue(library.isFavoriteFolder("/storage/emulated/0/Comics"))
    }

    @Test
    fun upgradingForgetsBooksKeptByPathOnce() {
        val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        sp.edit()
            .putString("favorites", JSONArray().put("/storage/emulated/0/Comics").put("/storage/emulated/0/Comics/a.cbz").toString())
            .putString("read", JSONArray().put("/storage/emulated/0/Comics/a.cbz").toString())
            .putString("series", JSONObject().put("/storage/emulated/0/Comics", JSONObject().put("dir", "rtl")).toString())
            .commit()
        library.forgetPathKeyedBooks()
        assertEquals(listOf("/storage/emulated/0/Comics"), library.favorites())
        assertNull(sp.getString("read", null))
        assertTrue(library.folderLayout("/storage/emulated/0/Comics") != null)
        // Once done, books kept by ID since are left be.
        library.setRead(a, true)
        library.forgetPathKeyedBooks()
        assertTrue(library.isRead(a.id))
    }

    @Test
    fun aListingKnowsBooksTheIndexHasSeen() {
        val dir = tmp.newFolder("Series")
        val books = listOf("1.cbz", "2.cbz", "3.cbz").map { File(dir, it).apply { writeBytes(it.toByteArray()) } }
        val sub = File(dir, "Extras").apply { mkdirs() }
        val read = BookRef(bookId(books[0]), books[0].absolutePath)
        val begun = BookRef(bookId(books[1]), "/elsewhere/2.cbz")
        library.setRead(read, true)
        library.addHistory(HistoryItem(begun.path, "2.cbz", 3, 20, 1, begun.id))
        library.toggleFavorite(begun)
        val snap = LibrarySnapshot.load(library, Prefs(context))
        val folder = snap.entry(dir, books + sub)
        assertEquals(4, folder.childCount)
        assertEquals(3, folder.bookCount)
        assertEquals(2, folder.begunCount)
        assertEquals(1, folder.readCount)
        assertEquals(ReadStatus.IN_PROGRESS, ReadStatus.of(folder))
        // The book read elsewhere shows its history and favorite here, and is noted as seen here.
        val second = snap.entry(books[1], null)
        assertEquals(begun.id, second.id)
        assertTrue(second.favorite)
        assertEquals(3, second.progressPage)
        assertEquals(20, second.pages)
        assertEquals(listOf(BookRef(begun.id, books[1].absolutePath)), snap.sightings())
        library.seenAll(snap.sightings())
        assertEquals(books[1].absolutePath, library.pathOf(begun.id))
        // A book the index has not seen shows nothing until its ID is found.
        val unseen = File(dir, "4.cbz").apply { writeBytes(books[0].readBytes()) }
        val listed = snap.entry(unseen, null)
        assertNull(listed.id)
        assertFalse(listed.read)
        assertTrue(snap.withId(listed, bookId(unseen)).read)
    }
}
