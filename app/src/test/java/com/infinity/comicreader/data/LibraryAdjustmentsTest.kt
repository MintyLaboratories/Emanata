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

    @Test
    fun aBookKeepsItsOwnAdjustmentsUntilReset() {
        assertNull(library.adjustmentsFor("/books/a.cbz"))
        library.setAdjustments("/books/a.cbz", Adjustments(-20, 35, 5))
        assertEquals(Adjustments(-20, 35, 5), library.adjustmentsFor("/books/a.cbz"))
        assertNull(library.adjustmentsFor("/books/b.cbz"))
        library.setAdjustments("/books/a.cbz", null)
        assertNull(library.adjustmentsFor("/books/a.cbz"))
    }

    @Test
    fun adjustmentsFollowRenamesAndDeletes() {
        library.setAdjustments("/books/old/a.cbz", Adjustments(brightness = 10))
        library.setAdjustments("/books/keep.cbz", Adjustments(contrast = 10))
        library.replacePath("/books/old", "/books/new")
        assertNull(library.adjustmentsFor("/books/old/a.cbz"))
        assertEquals(Adjustments(brightness = 10), library.adjustmentsFor("/books/new/a.cbz"))
        library.removePathsUnder("/books/new")
        assertNull(library.adjustmentsFor("/books/new/a.cbz"))
        assertEquals(Adjustments(contrast = 10), library.adjustmentsFor("/books/keep.cbz"))
    }

    @Test
    fun adjustmentsAreBackedUp() {
        library.setAdjustments("/books/a.cbz", Adjustments(saturation = -60))
        val backup = library.exportAll()
        library.setAdjustments("/books/a.cbz", null)
        library.importAll(backup)
        assertEquals(Adjustments(saturation = -60), library.adjustmentsFor("/books/a.cbz"))
    }
}
