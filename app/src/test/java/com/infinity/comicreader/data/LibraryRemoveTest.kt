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

/** Items come out of Favorites, History and Bookmarks one by one or all at once; what else is kept stays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryRemoveTest {

    private lateinit var library: Library

    private val a = BookRef(BookId(0xa), "/storage/emulated/0/Comics/a.cbz")
    private val b = BookRef(BookId(0xb), "/net/s1/Comics/b.cbz")
    private val folder = "/storage/emulated/0/Comics/Series"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
        library.toggleFavoriteFolder(folder)
        library.setFavorite(a, true)
        library.setFavorite(b, true)
        library.setRead(a, true)
        library.addHistory(HistoryItem(a.path, "a.cbz", 1, 9, 1, a.id))
        library.addHistory(HistoryItem(b.path, "b.cbz", 2, 9, 2, b.id))
        library.addBookmark(Bookmark(a.path, "a.cbz", 3, "", 1, a.id))
        library.addBookmark(Bookmark(b.path, "b.cbz", 4, "", 2, b.id))
        library.addBookmark(Bookmark(b.path, "b.cbz", 6, "", 3, b.id))
    }

    @Test
    fun removeHistoryTakesOnlyThoseBooks() {
        library.removeHistory(listOf(a.id))
        assertEquals(listOf(b.id), library.history().map { it.id })
        // Read marks, favorites and bookmarks stay.
        assertTrue(library.isRead(a.id))
        assertTrue(library.isFavorite(a.id))
        assertEquals(1, library.bookmarksFor(a.id).size)
    }

    @Test
    fun removeBookmarksTakesEachPageGiven() {
        library.removeBookmarks(listOf(b.id to 4, a.id to 3))
        assertEquals(listOf(b.id to 6), library.bookmarks().map { it.id to it.page })
        assertEquals(2, library.history().size)
    }

    @Test
    fun removeFavoritesTakesFoldersAndBooks() {
        library.removeFavorites(folders = listOf(folder), books = listOf(b.id))
        assertEquals(listOf(Library.Favorite(a.path, a.id)), library.favoriteItems())
        // The history of a book no longer a favorite stays.
        assertEquals(2, library.history().size)
    }

    @Test
    fun clearFavoritesEmptiesThemAndNothingElse() {
        library.clearFavorites()
        assertTrue(library.favoriteItems().isEmpty())
        assertEquals(2, library.history().size)
        assertEquals(3, library.bookmarks().size)
        assertTrue(library.isRead(a.id))
    }

    @Test
    fun clearHistoryAndBookmarksLeaveFavorites() {
        library.clearHistory()
        library.clearBookmarks()
        assertTrue(library.history().isEmpty())
        assertTrue(library.bookmarks().isEmpty())
        assertEquals(3, library.favoriteItems().size)
    }
}
