package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    fun aShiftKeptBeforeSeamsFlipsPairingByNames() {
        library.importAll(JSONObject().put("shifted", JSONArray().put("/books/a.cbz")))
        assertTrue(library.isShifted("/books/a.cbz"))
        assertTrue(library.isShiftedByNames("/books/a.cbz"))
        // Turned off and on again, it flips the pairing shown, seams included.
        library.setShifted("/books/a.cbz", false)
        assertFalse(library.isShifted("/books/a.cbz"))
        library.setShifted("/books/a.cbz", true)
        assertTrue(library.isShifted("/books/a.cbz"))
        assertFalse(library.isShiftedByNames("/books/a.cbz"))
    }

    @Test
    fun shiftsFollowRenamesDeletesAndBackups() {
        library.importAll(JSONObject().put("shifted", JSONArray().put("/books/old/a.cbz")))
        library.setShifted("/books/old/b.cbz", true)
        library.replacePath("/books/old", "/books/new")
        assertTrue(library.isShiftedByNames("/books/new/a.cbz"))
        assertTrue(library.isShifted("/books/new/b.cbz"))
        val backup = library.exportAll()
        library.removePathsUnder("/books/new")
        assertFalse(library.isShifted("/books/new/a.cbz"))
        assertFalse(library.isShifted("/books/new/b.cbz"))
        library.importAll(backup)
        assertTrue(library.isShiftedByNames("/books/new/a.cbz"))
        assertTrue(library.isShifted("/books/new/b.cbz"))
        assertFalse(library.isShiftedByNames("/books/new/b.cbz"))
    }
}
