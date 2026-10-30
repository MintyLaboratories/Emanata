package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Answers to reading tall pages as a strip are kept per book, and per folder for loose images. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryTallStripTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val a = BookRef(BookId(0xa), "/books/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/b.cbz")

    @Test
    fun aBooksAnswerIsKeptAndFollowsItsData() {
        assertNull(library.tallStrip(a.id))
        library.setTallStrip(a, true)
        assertEquals(true, library.tallStrip(a.id))
        assertEquals(true, library.kept()[a.id]?.settings)
        library.carryOver(a.id, b, IntArray(0), 0)
        assertNull(library.tallStrip(a.id))
        assertEquals(true, library.tallStrip(b.id))
        library.setTallStrip(b, false)
        assertEquals(false, library.tallStrip(b.id))
        library.clearLibraryData()
        assertNull(library.tallStrip(b.id))
    }

    @Test
    fun aFoldersAnswerMovesWithTheFolder() {
        library.setFolderTallStrip("/books/strip", true)
        library.replacePath("/books", "/comics")
        assertNull(library.folderTallStrip("/books/strip"))
        assertEquals(true, library.folderTallStrip("/comics/strip"))
        library.removePathsUnder("/comics")
        assertNull(library.folderTallStrip("/comics/strip"))
    }

    @Test
    fun clearingLibraryDataForgetsFoldersAnswers() {
        library.setFolderTallStrip("/books/strip", false)
        library.clearLibraryData()
        assertNull(library.folderTallStrip("/books/strip"))
    }

    @Test
    fun answersComeBackWithABackup() {
        library.setTallStrip(a, false)
        library.setFolderTallStrip("/books/strip", true)
        val saved = library.exportAll()
        library.clearLibraryData()
        library.setFolderTallStrip("/books/strip", false)
        library.importAll(saved)
        assertEquals(false, library.tallStrip(a.id))
        assertEquals(true, library.folderTallStrip("/books/strip"))
    }
}
