package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Fixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveJunkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun junkEntries() {
        for (path in listOf(
            "__MACOSX/Book/._001.jpg", "__macosx/001.jpg", "Book/._001.jpg", ".DS_Store",
            "Book/.DS_Store", ".hidden/001.jpg", "Book/.cover.jpg", "Thumbs.db", "Book/desktop.ini",
            "Book\\.DS_Store"
        )) {
            assertTrue(path, ArchiveJunk.isJunk(path, 10))
        }
        assertTrue(ArchiveJunk.isJunk("Book/001.jpg", 0))
    }

    @Test
    fun pageEntries() {
        for (path in listOf("001.jpg", "Book/001.jpg", "./001.jpg", "Book/ch.1/001.jpg", "MACOSX/001.jpg")) {
            assertFalse(path, ArchiveJunk.isJunk(path, 10))
        }
        assertFalse(ArchiveJunk.isJunk("001.jpg", -1))
    }

    @Test
    fun sourceSkipsJunk() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Book/001.jpg"), entry("Book/002.jpg"), entry("Book/empty.jpg", ByteArray(0)),
            entry("__MACOSX/Book/._001.jpg"), entry("__MACOSX/Book/._002.jpg"), entry("Book/._001.jpg"),
            entry(".DS_Store"), entry("Book/Thumbs.db")
        )
        ArchiveSource(ZipReader(f, "UTF-8")).use { src ->
            assertEquals(listOf("Book/001.jpg", "Book/002.jpg"), src.pagePaths)
        }
    }
}
