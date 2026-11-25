package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import minty.emanata.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A book opened anew that is like one with data kept may take that data over, once asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CopyMatchTest {

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var store: LikenessStore

    private val old = BookRef(BookId(0x01), "/storage/emulated/0/Comics/Night Owls 003.cbr")
    private val copy = BookRef(BookId(0x02), "/storage/1234-ABCD/Night Owls 003.cbz")

    private val oldLike = Likeness.of(null, listOf("p01.jpg", "p02.jpg", "p03.jpg", "p04.jpg"), "Night Owls 003.cbr")

    /** The copy's pages, the first two named in the other order. */
    private val copyLike = Likeness.of(null, listOf("p02.jxl", "p01.jxl", "p03.jxl", "p04.jxl"), "Night Owls 003.cbz")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
        store = LikenessStore(context)
        store.clear()
    }

    @Test
    fun likenessIsTakenOnceUnlessWeakAndRenamed() {
        assertTrue(CopyMatch.needed(store, old.id, "Night Owls 003.cbr", fresh = false))
        CopyMatch.note(store, library, old.id, oldLike, "Night Owls 003.cbr", fresh = false)
        assertFalse(CopyMatch.needed(store, old.id, "Night Owls 003.cbr", fresh = false))
        assertTrue(CopyMatch.needed(store, old.id, "renamed.cbr", fresh = false))
        assertTrue(CopyMatch.needed(store, old.id, "Night Owls 003.cbr", fresh = true))
        assertEquals(oldLike, store.get(old.id)!!.likeness)
    }

    @Test
    fun aFreshBookFindsOnlyALikeBookWithDataKept() {
        val like = Likeness.of(null, listOf("p01.jpg", "p02.jpg", "p03.jpg", "p04.jpg"), "Night Owls 003.cbz")
        CopyMatch.note(store, library, old.id, oldLike, "Night Owls 003.cbr", fresh = false)
        // Nothing kept for the old book: nothing to carry over.
        assertNull(CopyMatch.note(store, library, copy.id, like, "Night Owls 003.cbz", fresh = true))
        library.setRead(old, true)
        assertEquals(old.id, CopyMatch.note(store, library, copy.id, like, "Night Owls 003.cbz", fresh = true)!!.id)
        // A book with data of its own is never offered another's.
        assertNull(CopyMatch.note(store, library, copy.id, like, "Night Owls 003.cbz", fresh = false))
    }

    @Test
    fun carryingOverMovesEverythingWithItsPages() {
        CopyMatch.note(store, library, old.id, oldLike, "Night Owls 003.cbr", fresh = false)
        library.toggleFavoriteFolder("/storage/emulated/0/Comics")
        library.toggleFavorite(old)
        library.setRead(old, true)
        // Book page 0 hidden: page 1 of the book shows as page 0.
        library.setHiddenPages(old, setOf(0), 4)
        library.addHistory(HistoryItem(old.path, "Night Owls 003.cbr", 2, 3, 5, old.id))
        library.addBookmark(Bookmark(old.path, "Night Owls 003.cbr", 0, "start", 6, old.id))
        library.setAdjustments(old, Adjustments(10, 0, 0))
        library.setIgnoreSeams(old, true)
        // The copy opened once already: what it kept goes.
        library.addHistory(HistoryItem(copy.path, "Night Owls 003.cbz", 0, 4, 7, copy.id))

        CopyMatch.carryOver(store, library, store.get(old.id)!!, copy, copyLike, 4)

        assertFalse(library.hasData(old.id))
        assertNull(store.get(old.id))
        assertEquals(listOf(copy.path, "/storage/emulated/0/Comics"), library.favorites())
        assertTrue(library.isRead(copy.id))
        assertTrue(library.ignoresSeams(copy.id))
        assertEquals(Adjustments(10, 0, 0), library.adjustmentsFor(copy.id))
        // Book page 0 ("p01") is the copy's page 1.
        assertEquals(setOf(1), library.hiddenPages(copy.id))
        val history = library.history().single()
        assertEquals(copy.id, history.id)
        assertEquals(copy.path, history.path)
        assertEquals(2, history.page)
        // The bookmark on shown page 0 (book page 1, "p02") is on the copy's book page 0, shown as 0.
        assertEquals(0, library.bookmarks().single().page)
        assertEquals(copy.path, library.pathOf(copy.id))
        assertNull(library.pathOf(old.id))
    }
}
