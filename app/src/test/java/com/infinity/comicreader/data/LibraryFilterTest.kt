package com.infinity.comicreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryFilterTest {

    private fun book(
        name: String,
        type: EntryType = EntryType.ARCHIVE,
        read: Boolean = false,
        begun: Boolean = false,
        year: Long? = null,
        length: Long? = null,
        rtl: Boolean = false
    ) = Item(
        BookEntry(File("/books/$name"), name, isDir = type == EntryType.FOLDER, type = type, size = 0, lastModified = 0, read = read, begun = begun),
        year, length, if (rtl) ReadingDirection.RTL else ReadingDirection.LTR
    )

    class Item(val entry: BookEntry, val year: Long?, val length: Long?, val direction: ReadingDirection)

    private fun List<Item>.passing(filter: LibraryFilter, browsing: Boolean = false): List<String> {
        val byEntry = associateBy { it.entry }
        val values = SortValues { e, key ->
            val item = byEntry.getValue(e)
            when (key) {
                SortKey.YEAR -> item.year
                SortKey.LENGTH -> item.length
                SortKey.DIRECTION -> item.direction.ordinal.toLong()
                else -> null
            }
        }
        return filter { filter.accepts(it.entry, values, browsing) }.map { it.entry.name }
    }

    private val items = listOf(
        book("zipped", year = 2014, length = 200),
        book("pdf", EntryType.PDF, read = true, year = 1999, length = 40),
        book("image", EntryType.IMAGE, begun = true, length = 1),
        book("folder", EntryType.FOLDER),
        book("manga", year = 2020, rtl = true, begun = true)
    )

    @Test
    fun noFilterPassesEverything() {
        assertFalse(LibraryFilter.NONE.isActive)
        assertEquals(items.map { it.entry.name }, items.passing(LibraryFilter.NONE))
    }

    @Test
    fun byFileType() {
        val f = LibraryFilter(types = setOf(EntryType.PDF, EntryType.FOLDER))
        assertTrue(f.isActive)
        assertEquals(listOf("pdf", "folder"), items.passing(f))
    }

    @Test
    fun everyTypeIsNoFilter() {
        assertFalse(LibraryFilter(types = EntryType.entries.toSet()).isActive)
        assertFalse(LibraryFilter(statuses = ReadStatus.entries.toSet()).isActive)
    }

    @Test
    fun byReadingDirection() {
        assertEquals(listOf("manga"), items.passing(LibraryFilter(direction = ReadingDirection.RTL)))
        assertEquals(4, items.passing(LibraryFilter(direction = ReadingDirection.LTR)).size)
    }

    @Test
    fun byReadStatus() {
        // The folder was not looked into, so its status is unknown.
        assertEquals(listOf("zipped"), items.passing(LibraryFilter(statuses = setOf(ReadStatus.UNREAD))))
        assertEquals(listOf("image", "manga"), items.passing(LibraryFilter(statuses = setOf(ReadStatus.IN_PROGRESS))))
        assertEquals(listOf("pdf"), items.passing(LibraryFilter(statuses = setOf(ReadStatus.FINISHED))))
    }

    @Test
    fun folderStatusFromItsBooks() {
        fun folder(read: Int, books: Int, begun: Int = read, children: Int = books) = BookEntry(
            File("/f"), "f", true, EntryType.FOLDER, 0, 0,
            childCount = children, readCount = read, bookCount = books, begunCount = begun
        )
        assertEquals(ReadStatus.UNREAD, ReadStatus.of(folder(0, 4)))
        assertEquals(ReadStatus.IN_PROGRESS, ReadStatus.of(folder(1, 4)))
        assertEquals(ReadStatus.FINISHED, ReadStatus.of(folder(4, 4)))
        // Opened but none finished.
        assertEquals(ReadStatus.IN_PROGRESS, ReadStatus.of(folder(0, 4, begun = 2)))
        // Subfolders do not keep its books from being finished.
        assertEquals(ReadStatus.FINISHED, ReadStatus.of(folder(4, 4, children = 6)))
        // No books, or not looked into: unknown, so a status rule drops it.
        assertNull(ReadStatus.of(folder(0, 0, children = 3)))
        assertNull(ReadStatus.of(folder(-1, -1)))
        assertEquals(emptyList<String>(), listOf(Item(folder(0, 0, children = 3), null, null, ReadingDirection.LTR))
            .passing(LibraryFilter(statuses = setOf(ReadStatus.UNREAD))))
    }

    @Test
    fun yearRangeIsInclusiveAndDropsUnknown() {
        assertEquals(listOf("zipped", "manga"), items.passing(LibraryFilter(minYear = 2014)))
        assertEquals(listOf("zipped", "pdf"), items.passing(LibraryFilter(maxYear = 2014)))
        assertEquals(listOf("zipped"), items.passing(LibraryFilter(minYear = 2014, maxYear = 2014)))
    }

    @Test
    fun lengthRangeAndSwappedBounds() {
        assertEquals(listOf("pdf", "image"), items.passing(LibraryFilter(minLength = 50, maxLength = 1)))
        assertEquals(listOf("zipped"), items.passing(LibraryFilter(minLength = 100)))
    }

    @Test
    fun browsingFoldersFaceOnlyTheTypeRule() {
        val rest = LibraryFilter(
            direction = ReadingDirection.RTL, statuses = setOf(ReadStatus.FINISHED),
            minYear = 2030, minLength = 1000
        )
        val folder = items.single { it.entry.isDir }
        assertEquals(emptyList<String>(), listOf(folder).passing(rest))
        assertEquals(listOf("folder"), listOf(folder).passing(rest, browsing = true))
        assertEquals(emptyList<String>(), listOf(folder).passing(rest.copy(types = setOf(EntryType.PDF)), browsing = true))
        // Books still face every rule.
        assertEquals(emptyList<String>(), items.filter { !it.entry.isDir }.passing(rest, browsing = true))
    }

    @Test
    fun keysNameTheValuesNeeded() {
        assertEquals(emptySet<SortKey>(), LibraryFilter(types = setOf(EntryType.PDF)).keys)
        assertEquals(
            setOf(SortKey.YEAR, SortKey.LENGTH, SortKey.DIRECTION),
            LibraryFilter(minYear = 1, maxLength = 3, direction = ReadingDirection.RTL).keys
        )
    }

    @Test
    fun roundTrips() {
        val f = LibraryFilter(
            types = setOf(EntryType.ARCHIVE, EntryType.PDF),
            direction = ReadingDirection.RTL,
            statuses = setOf(ReadStatus.UNREAD, ReadStatus.IN_PROGRESS),
            minYear = 2000, maxYear = 2010, maxLength = 200
        )
        assertEquals("types=archive,pdf;dir=rtl;status=unread,progress;year=2000-2010;length=-200", f.encode())
        assertEquals(f, LibraryFilter.decode(f.encode()))
        assertEquals("", LibraryFilter.NONE.encode())
        assertEquals(LibraryFilter.NONE, LibraryFilter.decode("bogus;types=nothing"))
    }
}
