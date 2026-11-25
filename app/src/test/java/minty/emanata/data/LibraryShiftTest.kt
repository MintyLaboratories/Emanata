package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryShiftTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val a = BookRef(BookId(0xa), "/books/old/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/old/b.cbz")

    @Test
    fun shiftsFollowTheBookThroughMovesAndBackups() {
        library.setShifted(b, true)
        library.replacePath("/books/old", "/books/new")
        assertTrue(library.isShifted(b.id))
        assertFalse(library.isShifted(a.id))
        assertEquals("/books/new/b.cbz", library.pathOf(b.id))
        val backup = library.exportAll()
        library.setShifted(b, false)
        assertFalse(library.isShifted(b.id))
        // A row that is not a book ID is dropped.
        backup.getJSONArray("shifted_seams").put("/books/old/a.cbz")
        library.importAll(backup)
        assertTrue(library.isShifted(b.id))
        assertEquals(JSONArray().put(b.id.hex).toString(), library.exportAll().getJSONArray("shifted_seams").toString())
    }

    @Test
    fun ignoreSeamsFollowsTheBookAndBackups() {
        library.setIgnoreSeams(a, true)
        assertTrue(library.ignoresSeams(a.id))
        assertFalse(library.ignoresSeams(b.id))
        val backup = library.exportAll()
        library.setIgnoreSeams(a, false)
        assertFalse(library.ignoresSeams(a.id))
        library.importAll(backup)
        assertTrue(library.ignoresSeams(a.id))
    }
}
