package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.testing.bookId
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

/** What the library keeps per book can be told apart, forgotten for some books, or cleared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryForgetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library

    private val a = BookRef(BookId(0xa), "/storage/emulated/0/Comics/a.cbz")
    private val b = BookRef(BookId(0xb), "/net/s1/Comics/b.cbz")
    private val c = BookRef(BookId(0xc), "/storage/1234-ABCD/c.cbz")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private fun fill() {
        library.toggleFavoriteFolder("/storage/emulated/0/Comics")
        library.toggleFavorite(a)
        library.setRead(a, true)
        library.addHistory(HistoryItem(b.path, "b.cbz", 2, 9, 1, b.id))
        library.addBookmark(Bookmark(b.path, "b.cbz", 4, "", 1, b.id))
        library.addBookmark(Bookmark(b.path, "b.cbz", 6, "", 2, b.id))
        library.setHiddenPages(c, setOf(0), 5)
        library.setAdjustments(c, Adjustments(10, 0, 0))
        library.setFolderLayout("/storage/emulated/0/Comics", FolderLayout.Direction("rtl"))
        library.addReading(60, 3)
    }

    @Test
    fun keptTellsWhatEachBookKeeps() {
        fill()
        val kept = library.kept()
        assertEquals(Library.Kept(favorite = true, read = true), kept[a.id])
        assertEquals(Library.Kept(history = true, bookmarks = 2), kept[b.id])
        assertEquals(Library.Kept(hidden = 1, settings = true), kept[c.id])
        assertTrue(library.hasData(a.id))
        assertFalse(library.hasData(BookId(0xd)))
        assertTrue(listOf(a, b, c).all { library.mayHaveData(it.id) })
        assertFalse(library.mayHaveData(BookId(0xd)))
    }

    @Test
    fun aBookSeenOnlyKeepsNothing() {
        library.setRead(a, true)
        library.setRead(a, false)
        // Its entry of where it was seen stays, but counts as nothing kept.
        assertTrue(a.id in library.seenAt())
        assertFalse(library.hasData(a.id))
        assertFalse(library.mayHaveData(a.id))
    }

    @Test
    fun forgettingBooksDropsAllTheyKeepAndNothingElse() {
        fill()
        library.forgetBooks(setOf(a.id, b.id))
        assertEquals(listOf("/storage/emulated/0/Comics"), library.favorites())
        assertFalse(library.isRead(a.id))
        assertTrue(library.history().isEmpty())
        assertTrue(library.bookmarks().isEmpty())
        assertNull(library.seenAt()[a.id])
        assertNull(library.seenAt()[b.id])
        assertEquals(setOf(c.id), library.kept().keys)
        assertEquals(c.path, library.pathOf(c.id))
    }

    @Test
    fun clearingLibraryDataKeepsFolderLayoutsAndStatistics() {
        fill()
        library.clearLibraryData()
        assertTrue(library.kept().isEmpty())
        assertTrue(library.favorites().isEmpty())
        assertTrue(library.seenAt().isEmpty())
        assertTrue(library.folderLayout("/storage/emulated/0/Comics") != null)
        assertEquals(60, library.stats().seconds)
    }

    @Test
    fun aBookSeenNotesItsSizeFromTheIndex() {
        val file = File(tmp.newFolder("Series"), "one.cbz").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val ref = BookRef(bookId(file), file.absolutePath)
        library.setRead(ref, true)
        assertEquals(mapOf(ref.id to 300L), library.sizes())
        // Seen elsewhere, the size it was noted with stays.
        library.seen(BookRef(ref.id, "/elsewhere/one.cbz"))
        assertEquals(mapOf(ref.id to 300L), library.sizes())
        assertEquals("/elsewhere/one.cbz", library.pathOf(ref.id))
    }
}
