package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryAdjustmentsTest {

    private lateinit var context: Context
    private lateinit var library: Library

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val a = BookRef(BookId(0xa), "/books/old/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/b.cbz")

    @Test
    fun aBookKeepsItsOwnAdjustmentsUntilReset() {
        assertNull(library.adjustmentsFor(a.id))
        library.setAdjustments(a, Adjustments(-20, 35, 5))
        assertEquals(Adjustments(-20, 35, 5), library.adjustmentsFor(a.id))
        assertNull(library.adjustmentsFor(b.id))
        library.setAdjustments(a, null)
        assertNull(library.adjustmentsFor(a.id))
    }

    @Test
    fun adjustmentsFollowTheBookWhereverItIs() {
        library.setAdjustments(a, Adjustments(brightness = 10))
        library.replacePath("/books/old", "/books/new")
        assertEquals(Adjustments(brightness = 10), library.adjustmentsFor(a.id))
        assertEquals("/books/new/a.cbz", library.pathOf(a.id))
        // A copy deleted leaves them for the others.
        library.removePathsUnder("/books/new")
        assertEquals(Adjustments(brightness = 10), library.adjustmentsFor(a.id))
    }

    @Test
    fun adjustmentsAreBackedUp() {
        library.setAdjustments(a, Adjustments(saturation = -60))
        val backup = library.exportAll()
        library.setAdjustments(a, null)
        library.importAll(backup)
        assertEquals(Adjustments(saturation = -60), library.adjustmentsFor(a.id))
    }
}
