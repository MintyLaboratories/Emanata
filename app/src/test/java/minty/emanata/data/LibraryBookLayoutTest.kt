package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A book's own layout is kept by its ID, like its other settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryBookLayoutTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val book = BookRef(BookId(0xb00), "/books/Loose.cbz")
    private val other = BookRef(BookId(0xc00), "/books/Other.cbz")

    @Test
    fun aBooksOwnLayoutFollowsItsDataAndGoesWithIt() {
        val layout = FolderLayout.Direction(ReaderLayout.DIR_RTL)
        library.setBookLayout(book, layout)
        assertEquals(layout, library.bookLayout(book.id))
        library.carryOver(book.id, other, IntArray(0), 0)
        assertNull(library.bookLayout(book.id))
        assertEquals(layout, library.bookLayout(other.id))
        val saved = library.exportAll()
        library.forgetBooks(setOf(other.id))
        assertNull(library.bookLayout(other.id))
        library.importAll(saved)
        assertEquals(layout, library.bookLayout(other.id))
        library.setBookLayout(other, null)
        assertFalse(library.hasData(other.id))
    }
}
