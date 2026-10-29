package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.FabAction
import com.infinity.comicreader.data.MoreAction
import com.infinity.comicreader.data.MoreActions
import com.infinity.comicreader.ui.SelectionActions.Bar
import com.infinity.comicreader.ui.SelectionActions.Fab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectionTest {

    private fun book(name: String, size: Long = 100, read: Boolean = false, favorite: Boolean = false, page: Int = -1) =
        BookEntry(File("/shelf/$name"), name, false, EntryType.ARCHIVE, size, 0, read = read, favorite = favorite, bookmarkPage = page)

    private fun folder(name: String, favorite: Boolean = false) = BookEntry(File("/shelf/$name"), name, true, EntryType.FOLDER, 0, 0, favorite = favorite)

    @Test
    fun togglingStartsAndEndsSelection() {
        val s = Selection()
        assertFalse(s.active)
        assertTrue(s.toggle(book("a.cbz")))
        assertTrue(s.toggle(book("b.cbz")))
        assertEquals(2, s.size)
        assertFalse(s.toggle(book("a.cbz")))
        assertFalse(s.toggle(book("b.cbz")))
        assertFalse(s.active)
    }

    @Test
    fun bookmarksOfOneBookAreSelectedApart() {
        val s = Selection()
        s.toggle(book("a.cbz", page = 2))
        assertFalse(book("a.cbz", page = 5) in s)
        assertTrue(book("a.cbz", page = 2) in s)
    }

    @Test
    fun aListingKeepsWhatIsStillListedAsListedNow() {
        val s = Selection()
        s.selectAll(listOf(book("a.cbz"), book("b.cbz")))
        s.retain(listOf(book("a.cbz", favorite = true), book("c.cbz")))
        assertEquals(listOf(book("a.cbz", favorite = true)), s.entries)
        s.retain(emptyList())
        assertFalse(s.active)
    }

    @Test
    fun restoredRowsAreSelectedOnceListed() {
        val s = Selection()
        s.selectAll(listOf(book("a.cbz"), folder("Sub")))
        val keys = s.keys
        val again = Selection()
        again.restore(keys)
        assertFalse(again.active)
        again.retain(listOf(book("a.cbz"), folder("Sub"), book("z.cbz")))
        assertEquals(listOf("a.cbz", "Sub"), again.entries.map { it.name })
    }

    @Test
    fun totalSizeIsKnownOnlyWhenEverySizeIs() {
        val s = Selection()
        s.selectAll(listOf(book("a.cbz", 100), book("b.cbz", 50)))
        assertEquals(150L, s.totalSize { null })
        s.toggle(folder("Sub"))
        assertNull(s.totalSize { null })
        assertEquals(1150L, s.totalSize { 1000L })
    }

    private val facts = SelectionActions.MoreFacts(keepSimple = true, browsedHere = { true }, charsetApplies = { true }, canPeek = { true })

    @Test
    fun renameTakesOneAndBookmarksOfferNoFileOperations() {
        val one = SelectionActions.bar(false, listOf(book("a.cbz")), { true }, { true }, listOf(MoreAction.BOOK_INFO))
        assertEquals(Bar.entries.toSet(), one.enabled)
        val two = SelectionActions.bar(false, listOf(book("a.cbz"), book("b.cbz")), { true }, { true }, emptyList())
        assertEquals(Bar.entries.toSet(), two.shown)
        assertEquals(setOf(Bar.MOVE, Bar.COPY, Bar.DELETE, Bar.SHARE, Bar.FAVORITE), two.enabled)
        // One that cannot change keeps every file operation from all.
        val stuck = SelectionActions.bar(false, listOf(book("a.cbz"), book("gone.cbz")), { it.name != "gone.cbz" }, { true }, emptyList())
        assertEquals(setOf(Bar.SHARE, Bar.FAVORITE), stuck.enabled)
        val marks = SelectionActions.bar(true, listOf(book("a.cbz", page = 1)), { false }, { true }, emptyList())
        assertEquals(setOf(Bar.DELETE, Bar.SHARE, Bar.FAVORITE, Bar.MORE), marks.shown)
        assertEquals(setOf(Bar.DELETE, Bar.SHARE, Bar.FAVORITE), marks.enabled)
    }

    @Test
    fun favoriteRemovesOnlyWhenEveryItemIsOne() {
        assertFalse(SelectionActions.bar(false, listOf(book("a.cbz", favorite = true), folder("Sub")), { true }, { true }, emptyList()).unfavorite)
        assertTrue(SelectionActions.bar(false, listOf(book("a.cbz", favorite = true), folder("Sub", favorite = true)), { true }, { true }, emptyList()).unfavorite)
    }

    @Test
    fun theMoreMenuOffersWhatApplies() {
        val all = MoreActions.DEFAULT.with(MoreAction.PEEK, true).visible
        assertEquals(
            listOf(MoreAction.MARK_READ, MoreAction.BOOK_INFO, MoreAction.PEEK),
            SelectionActions.more(listOf(book("a.cbz")), all, facts)
        )
        assertEquals(
            listOf(MoreAction.MARK_UNREAD, MoreAction.SHOW_IN_FOLDER, MoreAction.BOOK_INFO, MoreAction.NAME_CHARSET, MoreAction.PEEK),
            SelectionActions.more(listOf(book("a.cbz", read = true)), all, SelectionActions.MoreFacts(false, { false }, { true }, { true }))
        )
        assertEquals(
            listOf(MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD, MoreAction.SET_STARTUP, MoreAction.PEEK),
            SelectionActions.more(listOf(folder("Sub")), all, facts)
        )
        assertEquals(
            listOf(MoreAction.MARK_READ, MoreAction.MARK_UNREAD, MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD),
            SelectionActions.more(listOf(book("a.cbz"), book("b.cbz", read = true), folder("Sub")), all, facts)
        )
        // Nothing not ready is ever offered.
        assertFalse(SelectionActions.more(listOf(book("a.cbz")), MoreAction.entries.toList(), facts).any { !it.ready })
    }

    @Test
    fun theButtonFollowsTheCountAndSettings() {
        val bar = SelectionActions.bar(false, listOf(book("a.cbz"), book("b.cbz")), { true }, { true }, emptyList())
        val none = SelectionActions.BarState(emptySet(), emptySet(), false)
        assertEquals(Fab.GRID_LIST, SelectionActions.fab(0, true, FabAction.OPEN, FabAction.MOVE, none))
        assertNull(SelectionActions.fab(0, false, FabAction.OPEN, FabAction.MOVE, none))
        assertEquals(Fab.OPEN, SelectionActions.fab(1, true, FabAction.OPEN, FabAction.MOVE, bar))
        assertEquals(Fab.MOVE, SelectionActions.fab(2, true, FabAction.OPEN, FabAction.MOVE, bar))
        assertEquals(Fab.SHARE, SelectionActions.fab(3, true, FabAction.OPEN, FabAction.SHARE, bar))
        assertNull(SelectionActions.fab(1, true, FabAction.NONE, FabAction.MOVE, bar))
        // A choice the bar does not allow now hides the button.
        val marks = SelectionActions.bar(true, listOf(book("a.cbz", page = 1), book("b.cbz", page = 2)), { false }, { true }, emptyList())
        assertNull(SelectionActions.fab(2, true, FabAction.OPEN, FabAction.MOVE, marks))
    }
}
