package com.infinity.comicreader.data

import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Slices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryValuesTest {

    @Test
    fun yearsFromNames() {
        assertEquals(2014, Years.fromName("Series A v01 (2014) (Digital).cbz"))
        assertEquals(0, Years.fromName("Series A 2014.cbz"))
        assertEquals(0, Years.fromName("Series A (0042).cbz"))
        assertEquals(1999, Years.fromName("Series A (0042) (1999).cbz"))
        assertEquals(0, Years.fromName("plain"))
    }

    @Test
    fun foldersTakeTheirEarliestChildsYear() {
        assertEquals(2001, Years.ofFolder("Series", listOf("a (2005).cbz", "b (2001).cbz", "notes.txt")))
        assertEquals(2010, Years.ofFolder("Series (2010)", listOf("a.cbz")))
        assertEquals(0, Years.ofFolder("Series", emptyList()))
    }

    @Test
    fun accessTimesReachEveryFolderAbove() {
        val times = AccessTimes.of(
            sequenceOf(
                "/books/attic/Series A/1.cbz" to 100L,
                "/books/attic/Series B/1.cbz" to 300L,
                "/books/attic/Series A/2.cbz" to 200L
            )
        )
        assertEquals(200L, times["/books/attic/Series A"])
        assertEquals(300L, times["/books/attic/Series B"])
        assertEquals(300L, times["/books/attic"])
        assertEquals(300L, times["/books"])
        assertEquals(100L, times["/books/attic/Series A/1.cbz"])
        assertNull(times["/books/attic/Series C"])
    }

    @Test
    fun latestAccessOfOnePathWins() {
        // A bookmark added after the history entry counts as the later access.
        val times = AccessTimes.of(sequenceOf("/b/x.cbz" to 50L, "/b/x.cbz" to 80L, "/b/x.cbz" to 60L))
        assertEquals(80L, times["/b/x.cbz"])
        assertEquals(80L, times["/b"])
    }

    private fun entry(name: String, isDir: Boolean = false, pages: Int = -1, size: Long = 10) =
        BookEntry(File("/b/$name"), name, isDir, if (isDir) EntryType.FOLDER else EntryType.ARCHIVE, size, 0, pages = pages)

    @Test
    fun entryValuesPreferKnownFacts() {
        val book = entry("a (2001).cbz").copy(year = 2001)
        val facts = BookFacts(pages = 24, year = 1998)
        val values = EntryValues(facts = { facts })
        assertEquals(1998L, values.of(book, SortKey.YEAR))
        assertEquals(24L, values.of(book, SortKey.LENGTH))
        // A page count from history comes first.
        assertEquals(30L, values.of(book.copy(pages = 30), SortKey.LENGTH))
        assertEquals(2001L, EntryValues().of(book, SortKey.YEAR))
        assertNull(EntryValues().of(book, SortKey.LENGTH))
    }

    @Test
    fun lengthLeavesHiddenPagesOut() {
        val book = entry("a.cbz")
        val values = EntryValues(hidden = { if (it.path == book.path) 4 else 0 }) { BookFacts(pages = 24, year = 0) }
        assertEquals(20L, values.of(book, SortKey.LENGTH))
        assertEquals(24L, values.of(entry("b.cbz"), SortKey.LENGTH))
        // A count from history is already shown pages.
        assertEquals(30L, values.of(book.copy(pages = 30), SortKey.LENGTH))
        assertEquals(1, BookFacts(pages = 3, year = 0).shownPages(5))
        assertEquals(-1, BookFacts(pages = -1, year = 0).shownPages(2))
    }

    @Test
    fun folderSizesComeFromTheScan() {
        val folder = entry("f", isDir = true, size = 0)
        assertNull(EntryValues().of(folder, SortKey.SIZE))
        assertEquals(500L, EntryValues(folderSizes = mapOf(folder.path to 500L)).of(folder, SortKey.SIZE))
        assertEquals(10L, EntryValues().of(entry("b.cbz"), SortKey.SIZE))
    }

    @Test
    fun frozenValuesIgnoreLaterFindings() {
        val book = entry("a.cbz")
        var facts: BookFacts? = null
        val live = EntryValues(facts = { facts })
        val frozen = live.frozen(listOf(book), setOf(SortKey.LENGTH))
        facts = BookFacts(pages = 50, year = 0)
        assertNull(frozen.of(book, SortKey.LENGTH))
        assertEquals(50L, live.of(book, SortKey.LENGTH))
        // Entries not frozen have no value yet, so they sort last.
        assertNull(frozen.of(entry("new.cbz"), SortKey.LENGTH))
    }

    @Test
    fun foldersOfLoosePagesHaveALength() {
        val pages = (1..6).map { "page%02d.jpg".format(it) }
        assertEquals(6, LibrarySnapshot.looseImages(pages + "notes.cbz"))
        // Too few pages, or stray pictures without a shared numbered name.
        assertEquals(-1, LibrarySnapshot.looseImages(pages.take(3)))
        assertEquals(-1, LibrarySnapshot.looseImages(listOf("cover.jpg", "promo.jpg", "a.png", "b.png", "c.png")))
        assertEquals(-1, LibrarySnapshot.looseImages(emptyList()))

        val dir = Files.createTempDirectory("loose").toFile()
        try {
            val files = pages.map { File(dir, it).apply { writeBytes(ByteArray(1)) } }
            val entry = LibrarySnapshot().entry(dir, files)
            assertEquals(6L, EntryValues().of(entry, SortKey.LENGTH))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun folderTotalsSkipLinkedFolders() {
        val dir = Files.createTempDirectory("total").toFile()
        val loop = File(dir, "sub/loop").toPath()
        try {
            File(dir, "a.cbz").writeBytes(ByteArray(10))
            File(dir, "sub").mkdirs()
            File(dir, "sub/b.cbz").writeBytes(ByteArray(5))
            val linked = runCatching { Files.createSymbolicLink(loop, dir.toPath()) }.isSuccess
            assertEquals(15L, FolderSize.total(dir))
            if (!linked) println("Symbolic links unavailable; loop case not exercised")
        } finally {
            // The link goes first, so deleting does not follow it round.
            Files.deleteIfExists(loop)
            dir.deleteRecursively()
        }
    }

    @Test
    fun slicesOutrankTheDetectedDirection() {
        val rtl = DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.COMIC_INFO)
        val shape = Slices.Checked(DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SHAPE))
        val book = entry("a.cbz").copy(direction = ReadingDirection.LTR)
        assertEquals(ReadingDirection.TTB.rank.toLong(), EntryValues(detect = true) { BookFacts(10, 0, rtl, shape) }.of(book, SortKey.DIRECTION))
        assertEquals(ReadingDirection.RTL.rank.toLong(), EntryValues(detect = true) { BookFacts(10, 0, rtl, Slices.Checked(null)) }.of(book, SortKey.DIRECTION))
        assertEquals(ReadingDirection.LTR.rank.toLong(), EntryValues(detect = true) { BookFacts(10, 0, rtl, shape) }.of(book.copy(directionSet = true), SortKey.DIRECTION))
    }

    @Test
    fun detectedDirectionUnlessTheFolderHasOne() {
        val rtl = BookFacts(10, 0, DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.PUBLISHER))
        val book = entry("a.cbz").copy(direction = ReadingDirection.LTR)
        val rtlKey = ReadingDirection.RTL.rank.toLong()
        val ltrKey = ReadingDirection.LTR.rank.toLong()
        assertEquals(rtlKey, EntryValues(detect = true) { rtl }.of(book, SortKey.DIRECTION))
        // Off, or not known yet: the default.
        assertEquals(ltrKey, EntryValues(detect = false) { rtl }.of(book, SortKey.DIRECTION))
        assertEquals(ltrKey, EntryValues(detect = true) { null }.of(book, SortKey.DIRECTION))
        // A direction set for the folder wins.
        assertEquals(ltrKey, EntryValues(detect = true) { rtl }.of(book.copy(directionSet = true), SortKey.DIRECTION))
    }

    @Test
    fun snapshotDirections() {
        val snap = LibrarySnapshot(
            directions = mapOf("/b/manga" to ReadingDirection.RTL),
            defaultDirection = ReadingDirection.LTR
        )
        assertEquals(ReadingDirection.RTL, snap.directionOf("/b/manga/1.cbz", isDir = false))
        assertEquals(ReadingDirection.RTL, snap.directionOf("/b/manga", isDir = true))
        assertEquals(ReadingDirection.LTR, snap.directionOf("/b/other/1.cbz", isDir = false))
        assertTrue(snap.isDirectionSet("/b/manga/1.cbz", isDir = false))
        assertFalse(snap.isDirectionSet("/b/other/1.cbz", isDir = false))
    }
}
