package com.infinity.comicreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LibrarySortTest {

    private fun book(name: String, type: EntryType = EntryType.ARCHIVE) =
        BookEntry(File("/books/$name"), name, isDir = type == EntryType.FOLDER, type = type, size = 0, lastModified = 0)

    private fun folder(name: String) = book(name, EntryType.FOLDER)

    /** Values from a table: name → key → value; missing means none. */
    private fun values(table: Map<String, Map<SortKey, Long>>) =
        SortValues { entry, key -> table[entry.name]?.get(key) }

    private fun List<BookEntry>.sorted(sort: TabSort, values: SortValues = values(emptyMap())) =
        sortedWith(LibrarySort.comparator(sort, values)).map { it.name }

    @Test
    fun nameIsNaturalAndFlips() {
        val list = listOf(book("Vol 10"), book("Vol 2"), book("vol 1"))
        assertEquals(listOf("vol 1", "Vol 2", "Vol 10"), list.sorted(TabSort(SortLevel(SortKey.NAME))))
        assertEquals(listOf("Vol 10", "Vol 2", "vol 1"), list.sorted(TabSort(SortLevel(SortKey.NAME, flipped = true))))
    }

    @Test
    fun foldersFirstUnlessTurnedOff() {
        val list = listOf(book("A"), folder("B"), book("C"))
        assertEquals(listOf("B", "A", "C"), list.sorted(TabSort(SortLevel(SortKey.NAME))))
        assertEquals(listOf("A", "B", "C"), list.sorted(TabSort(SortLevel(SortKey.NAME), foldersFirst = false)))
    }

    @Test
    fun firstDirectionsMatchTheirNames() {
        val list = listOf(book("a"), book("b"), book("c"))
        val table = mapOf(
            "a" to mapOf(SortKey.YEAR to 2001L, SortKey.SIZE to 5L, SortKey.LENGTH to 30L, SortKey.ACCESSED to 10L, SortKey.ADDED to 3L),
            "b" to mapOf(SortKey.YEAR to 2010L, SortKey.SIZE to 9L, SortKey.LENGTH to 10L, SortKey.ACCESSED to 30L, SortKey.ADDED to 1L),
            "c" to mapOf(SortKey.YEAR to 1999L, SortKey.SIZE to 1L, SortKey.LENGTH to 20L, SortKey.ACCESSED to 20L, SortKey.ADDED to 2L)
        )
        val v = values(table)
        // Newest, largest, longest, recent first.
        assertEquals(listOf("b", "a", "c"), list.sorted(TabSort(SortLevel(SortKey.YEAR)), v))
        assertEquals(listOf("b", "a", "c"), list.sorted(TabSort(SortLevel(SortKey.SIZE)), v))
        assertEquals(listOf("a", "c", "b"), list.sorted(TabSort(SortLevel(SortKey.LENGTH)), v))
        assertEquals(listOf("b", "c", "a"), list.sorted(TabSort(SortLevel(SortKey.ACCESSED)), v))
        assertEquals(listOf("a", "c", "b"), list.sorted(TabSort(SortLevel(SortKey.ADDED)), v))
        // Flipped: oldest, smallest, shortest first.
        assertEquals(listOf("c", "a", "b"), list.sorted(TabSort(SortLevel(SortKey.YEAR, true)), v))
        assertEquals(listOf("c", "a", "b"), list.sorted(TabSort(SortLevel(SortKey.SIZE, true)), v))
        assertEquals(listOf("b", "c", "a"), list.sorted(TabSort(SortLevel(SortKey.LENGTH, true)), v))
    }

    @Test
    fun missingValuesSortLastEitherWay() {
        val list = listOf(book("none"), book("old"), book("new"), book("also none"))
        val v = values(mapOf("old" to mapOf(SortKey.YEAR to 1990L), "new" to mapOf(SortKey.YEAR to 2020L)))
        assertEquals(listOf("new", "old", "also none", "none"), list.sorted(TabSort(SortLevel(SortKey.YEAR)), v))
        assertEquals(listOf("old", "new", "also none", "none"), list.sorted(TabSort(SortLevel(SortKey.YEAR, true)), v))
    }

    @Test
    fun typeOrderIsFolderArchivePdfImage() {
        val list = listOf(book("p", EntryType.PDF), book("i", EntryType.IMAGE), folder("f"), book("a"))
        val sort = TabSort(SortLevel(SortKey.TYPE), foldersFirst = false)
        assertEquals(listOf("f", "a", "p", "i"), list.sorted(sort))
        assertEquals(listOf("i", "p", "a", "f"), list.sorted(sort.copy(by = SortLevel(SortKey.TYPE, true))))
    }

    @Test
    fun thenByBreaksTiesThenName() {
        val list = listOf(book("d"), book("c"), book("b"), book("a"))
        val v = values(
            mapOf(
                "a" to mapOf(SortKey.YEAR to 2014L, SortKey.LENGTH to 100L),
                "b" to mapOf(SortKey.YEAR to 2014L, SortKey.LENGTH to 20L),
                "c" to mapOf(SortKey.YEAR to 2014L, SortKey.LENGTH to 20L),
                "d" to mapOf(SortKey.YEAR to 2020L)
            )
        )
        val sort = TabSort(SortLevel(SortKey.YEAR), then = SortLevel(SortKey.LENGTH, flipped = true))
        // 2020 first; then 2014 by length, shortest first, with name as the last tiebreaker.
        assertEquals(listOf("d", "b", "c", "a"), list.sorted(sort, v))
    }

    @Test
    fun thenByOnlyAppliesAfterOptionsWithTies() {
        val by = TabSort(SortLevel(SortKey.SIZE), then = SortLevel(SortKey.LENGTH))
        assertNull(by.thenInEffect)
        assertEquals(setOf(SortKey.SIZE), by.keys)
        assertEquals(SortLevel(SortKey.LENGTH), TabSort(SortLevel(SortKey.DIRECTION), then = SortLevel(SortKey.LENGTH)).thenInEffect)
        assertNull(TabSort(SortLevel(SortKey.YEAR), then = SortLevel(SortKey.YEAR)).thenInEffect)
    }

    @Test
    fun tappingPicksThenFlips() {
        val start = TabSort.defaultFor(Prefs.TAB_BROWSE)
        assertEquals(SortLevel(SortKey.NAME), start.by)
        assertEquals(SortLevel(SortKey.NAME, flipped = true), start.tapBy(SortKey.NAME).by)
        val year = start.tapBy(SortKey.YEAR)
        assertEquals(SortLevel(SortKey.YEAR), year.by)
        val then = year.tapThen(SortKey.LENGTH)
        assertEquals(SortLevel(SortKey.LENGTH), then.then)
        assertEquals(SortLevel(SortKey.LENGTH, flipped = true), then.tapThen(SortKey.LENGTH).then)
        assertNull(then.tapThen(null).then)
        // Flipping the first level keeps the second; picking another one starts it at None.
        assertEquals(SortLevel(SortKey.LENGTH), then.tapBy(SortKey.YEAR).then)
        assertNull(then.tapBy(SortKey.LENGTH).then)
        assertNull(then.tapBy(SortKey.NAME).tapBy(SortKey.YEAR).then)
        assertNull(then.tapBy(SortKey.TYPE).then)
    }

    @Test
    fun defaultsPerTab() {
        assertEquals(TabSort(SortLevel(SortKey.NAME)), TabSort.defaultFor(Prefs.TAB_FAVORITES))
        assertEquals(TabSort(SortLevel(SortKey.ACCESSED)), TabSort.defaultFor(Prefs.TAB_HISTORY))
        assertEquals(TabSort(SortLevel(SortKey.ACCESSED)), TabSort.defaultFor(Prefs.TAB_BOOKMARKS))
        assertEquals(true, TabSort.defaultFor(Prefs.TAB_BROWSE).foldersFirst)
    }

    @Test
    fun levelsRoundTrip() {
        for (key in SortKey.entries) {
            for (flipped in listOf(false, true)) {
                val level = SortLevel(key, flipped)
                assertEquals(level, SortLevel.decode(level.encode()))
            }
        }
        assertNull(SortLevel.decode("bogus"))
        assertNull(SortLevel.decode(""))
    }
}
