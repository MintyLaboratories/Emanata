package minty.emanata.ui

import minty.emanata.data.BookEntry
import minty.emanata.data.EntryType
import minty.emanata.data.FabAction
import minty.emanata.data.MoreAction
import minty.emanata.data.MoreActions
import minty.emanata.ui.SelectionActions.Bar
import minty.emanata.ui.SelectionActions.Fab
import minty.emanata.util.Formats
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
            listOf(MoreAction.CONTINUE_READING, MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD, MoreAction.SET_STARTUP, MoreAction.PEEK),
            SelectionActions.more(listOf(folder("Sub")), all, facts)
        )
        assertEquals(
            listOf(MoreAction.MARK_READ, MoreAction.MARK_UNREAD, MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD),
            SelectionActions.more(listOf(book("a.cbz"), book("b.cbz", read = true), folder("Sub")), all, facts)
        )
        // Compaction for books it can rebuild among those selected.
        val rebuild = SelectionActions.MoreFacts(true, { true }, { true }, { true }, rebuildable = { it.name.endsWith(".cbr") })
        assertEquals(
            listOf(MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE),
            SelectionActions.more(listOf(book("a.cbr"), folder("Sub")), all, rebuild).filter { it == MoreAction.COMPACT || it == MoreAction.MAKE_COMPATIBLE }
        )
        assertFalse(MoreAction.COMPACT in SelectionActions.more(listOf(book("a.pdf"), folder("Sub")), all, rebuild))
        // Repack as CBZ for archives that are not ZIP, and folders that may hold them.
        val any = SelectionActions.MoreFacts(true, { true }, { true }, { true }, rebuildable = { it.isDir || Formats.isArchiveName(it.name) })
        for (name in listOf("a.cbr", "a.cb7", "a.cbt", "a.rar")) assertTrue(name, MoreAction.REPACK_CBZ in SelectionActions.more(listOf(book(name)), all, any))
        for (name in listOf("a.cbz", "a.zip", "a.pdf")) assertFalse(name, MoreAction.REPACK_CBZ in SelectionActions.more(listOf(book(name)), all, any))
        assertTrue(MoreAction.REPACK_CBZ in SelectionActions.more(listOf(folder("Sub")), all, any))
    }

    @Test
    fun packIsForFoldersOnlyThatItMayTake() {
        val packs = listOf(MoreAction.PACK, MoreAction.PACK_COMPACTED)
        val all = MoreActions.DEFAULT.visible
        val facts = SelectionActions.MoreFacts(true, { true }, { true }, { true }, packable = { it.name != "Share" })
        fun packing(vararg entries: BookEntry) = SelectionActions.more(entries.toList(), all, facts).filter { it in packs }
        assertEquals(packs, packing(folder("A"), folder("B")))
        // Books selected too: offered, turned off with the reason on screen.
        assertEquals(packs, packing(folder("A"), book("a.cbz")))
        assertEquals(emptyList<MoreAction>(), packing(book("a.cbz")))
        assertEquals(emptyList<MoreAction>(), packing(folder("A"), folder("Share")))
        // Where nothing says a folder may be taken (a share's), none.
        val none = SelectionActions.MoreFacts(true, { true }, { true }, { true })
        assertTrue(SelectionActions.more(listOf(folder("A")), all, none).none { it in packs })
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
