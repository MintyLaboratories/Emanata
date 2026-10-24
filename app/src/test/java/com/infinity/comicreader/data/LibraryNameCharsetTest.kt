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

    @Test
    fun aChoiceIsKeptAndPublished() {
        library.setNameCharset("/books/a.cbz", "Shift_JIS")
        assertEquals("Shift_JIS", library.nameCharset("/books/a.cbz"))
        assertEquals("Shift_JIS", NameCharset.forBook("/books/a.cbz", NameCharset.AUTO))
        library.setNameCharset("/books/a.cbz", null)
        assertNull(library.nameCharset("/books/a.cbz"))
        assertEquals(NameCharset.AUTO, NameCharset.forBook("/books/a.cbz", NameCharset.AUTO))
    }

    @Test
    fun aChoiceFollowsTheBookAndGoesWithIt() {
        library.setNameCharset("/books/a.cbz", "GBK")
        library.replacePath("/books", "/comics")
        assertNull(library.nameCharset("/books/a.cbz"))
        assertEquals("GBK", library.nameCharset("/comics/a.cbz"))
        assertEquals("GBK", NameCharset.forBook("/comics/a.cbz", NameCharset.AUTO))
        library.removePathsUnder("/comics")
        assertEquals(emptyMap<String, String>(), library.nameCharsets())
        assertEquals(emptyMap<String, String>(), NameCharset.overrides)
    }

    @Test
    fun aChoiceComesBackWithABackup() {
        library.setNameCharset("/books/a.cbz", "Big5")
        val saved = library.exportAll()
        library.setNameCharset("/books/a.cbz", null)
        library.importAll(JSONObject(saved.toString()))
        assertEquals("Big5", library.nameCharset("/books/a.cbz"))
        assertEquals("Big5", NameCharset.overrides["/books/a.cbz"])
    }
}
