package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
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
    fun aShiftKeptBeforeSeamsFlipsPairingByNames() {
        library.importAll(JSONObject().put("shifted", JSONArray().put(a.id.hex)))
        assertTrue(library.isShifted(a.id))
        assertTrue(library.isShiftedByNames(a.id))
        // Turned off and on again, it flips the pairing shown, seams included.
        library.setShifted(a, false)
        assertFalse(library.isShifted(a.id))
        library.setShifted(a, true)
        assertTrue(library.isShifted(a.id))
        assertFalse(library.isShiftedByNames(a.id))
    }

    @Test
    fun shiftsFollowTheBookThroughMovesDeletesAndBackups() {
        library.importAll(JSONObject().put("shifted", JSONArray().put(a.id.hex).put("/books/old/legacy.cbz")))
        library.setShifted(b, true)
        library.replacePath("/books/old", "/books/new")
        assertTrue(library.isShiftedByNames(a.id))
        assertTrue(library.isShifted(b.id))
        assertEquals("/books/new/b.cbz", library.pathOf(b.id))
        val backup = library.exportAll()
        assertEquals(JSONArray().put(a.id.hex).toString(), backup.getJSONArray("shifted").toString())
        library.setShifted(a, false)
        library.setShifted(b, false)
        library.importAll(backup)
        assertTrue(library.isShiftedByNames(a.id))
        assertTrue(library.isShifted(b.id))
        assertFalse(library.isShiftedByNames(b.id))
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
