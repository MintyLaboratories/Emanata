package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Not now" to repacking a solid archive, kept by Book ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryRepackDeclinedTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val a = BookRef(BookId(0xa), "/books/a.cbr")
    private val copy = BookRef(BookId(0xa), "/elsewhere/renamed.cbr")
    private val b = BookRef(BookId(0xb), "/books/b.cbr")

    @Test
    fun notNowIsKeptByBookIdWhereverTheBookIs() {
        assertFalse(library.repackDeclined(a.id))
        library.setRepackDeclined(a)
        assertTrue(library.repackDeclined(copy.id))
        assertFalse(library.repackDeclined(b.id))
        assertTrue(library.hasData(a.id))
    }

    @Test
    fun itSurvivesABackupAndFollowsACarryOver() {
        library.setRepackDeclined(a)
        val saved = library.exportAll()
        library.clearLibraryData()
        assertFalse(library.repackDeclined(a.id))
        library.importAll(saved)
        assertTrue(library.repackDeclined(a.id))
        library.carryOver(a.id, b, intArrayOf(0), 1)
        assertTrue(library.repackDeclined(b.id))
        assertFalse(library.repackDeclined(a.id))
    }
}
