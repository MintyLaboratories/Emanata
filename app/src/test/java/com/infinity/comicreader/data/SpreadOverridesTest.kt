package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpreadOverridesTest {

    private lateinit var library: Library
    private val a = BookRef(BookId(0xa), "/books/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/b.cbz")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    @Test
    fun aChoiceUndoesTheChoicesItContradicts() {
        val paired = SpreadOverrides().pairing(4)
        assertEquals(SpreadOverrides(paired = setOf(4)), paired)
        // Shown alone, page 5 leaves the pair it was in.
        assertEquals(SpreadOverrides(alone = setOf(5)), paired.showingAlone(5))
        assertEquals(SpreadOverrides(alone = setOf(4)), paired.showingAlone(4))
        // A pair undoes those it would overlap, and its pages are no longer alone.
        assertEquals(SpreadOverrides(paired = setOf(5)), paired.pairing(5))
        assertEquals(SpreadOverrides(paired = setOf(3)), paired.pairing(3))
        assertEquals(SpreadOverrides(paired = setOf(2)), SpreadOverrides(alone = setOf(2, 3)).pairing(2))
        // With a page hidden between, the pages shown before and after are named.
        assertEquals(SpreadOverrides(paired = setOf(6)), SpreadOverrides(paired = setOf(4, 8)).pairing(6, next = 8, previous = 4))
        assertEquals(SpreadOverrides(), paired.cleared(6, previous = 4))
    }

    @Test
    fun choicesCountPagesAsShownAndFollowTheirPages() {
        val o = SpreadOverrides(alone = setOf(1, 5), paired = setOf(3, 7))
        assertEquals(SpreadOverrides(alone = setOf(3), paired = setOf(1, 5)), o.shown(setOf(0, 1)))
        assertEquals(o, o.shown(emptySet()))
        assertEquals(SpreadOverrides(alone = setOf(0), paired = setOf(2)), o.moved(intArrayOf(9, 0, 9, 2, 9, -1, 9, 9), 4))
        assertEquals(o, SpreadOverrides.of(o.toJson()))
        assertEquals(SpreadOverrides(alone = setOf(2)), SpreadOverrides.of(org.json.JSONObject("""{"alone":[2,-1,"x"],"pair":[2]}""")))
    }

    @Test
    fun aBookKeepsItsChoicesUntilCleared() {
        assertTrue(library.spreadOverrides(a.id).isEmpty)
        val o = SpreadOverrides(alone = setOf(2), paired = setOf(5))
        library.setSpreadOverrides(a, o)
        assertEquals(o, library.spreadOverrides(a.id))
        assertTrue(library.spreadOverrides(b.id).isEmpty)
        assertTrue(library.kept().getValue(a.id).settings)
        library.movePages(a.id, intArrayOf(0, 1, 3, 2, 4, 6, 5), 7)
        assertEquals(SpreadOverrides(alone = setOf(3), paired = setOf(6)), library.spreadOverrides(a.id))
        library.carryOver(a.id, b, IntArray(7) { it }, 7)
        assertEquals(SpreadOverrides(alone = setOf(3), paired = setOf(6)), library.spreadOverrides(b.id))
        assertTrue(library.spreadOverrides(a.id).isEmpty)
        val backup = library.exportAll()
        library.clearLibraryData()
        assertTrue(library.spreadOverrides(b.id).isEmpty)
        library.importAll(backup)
        assertEquals(setOf(3), library.spreadOverrides(b.id).alone)
        library.setSpreadOverrides(b, SpreadOverrides())
        assertTrue(library.kept().isEmpty())
    }
}
