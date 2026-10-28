package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.NameCharset
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A book's own file name charset is kept, follows the book, and reaches the archive readers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryNameCharsetTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
        NameCharset.overrides = emptyMap()
    }

    @After
    fun tearDown() {
        NameCharset.overrides = emptyMap()
    }

    private val a = BookRef(BookId(0xa), "/books/a.cbz")

    @Test
    fun aChoiceIsKeptAndPublished() {
        library.setNameCharset(a, "Shift_JIS")
        assertEquals("Shift_JIS", library.nameCharset(a.id))
        assertEquals("Shift_JIS", NameCharset.forBook(a.id, NameCharset.AUTO))
        library.setNameCharset(a, null)
        assertNull(library.nameCharset(a.id))
        assertEquals(NameCharset.AUTO, NameCharset.forBook(a.id, NameCharset.AUTO))
    }

    @Test
    fun aChoiceFollowsTheBookWhereverItIs() {
        library.setNameCharset(a, "GBK")
        library.replacePath("/books", "/comics")
        assertEquals("GBK", library.nameCharset(a.id))
        assertEquals("GBK", NameCharset.forBook(a.id, NameCharset.AUTO))
        assertEquals("/comics/a.cbz", library.pathOf(a.id))
        library.removePathsUnder("/comics")
        assertEquals(mapOf(a.id to "GBK"), library.nameCharsets())
    }

    @Test
    fun aChoiceComesBackWithABackup() {
        library.setNameCharset(a, "Big5")
        val saved = library.exportAll()
        library.setNameCharset(a, null)
        library.importAll(JSONObject(saved.toString()))
        assertEquals("Big5", library.nameCharset(a.id))
        assertEquals("Big5", NameCharset.overrides[a.id])
    }
}
