package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.ArchiveKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BookInfoTest {

    private val mismatch = "%1\$s (actually %2\$s)"

    private fun format(name: String, kind: ArchiveKind?) = BookInfo.format(name, kind, mismatch)

    @Test
    fun theContainerAloneWhenTheExtensionNamesIt() {
        assertEquals("ZIP", format("Book.zip", ArchiveKind.ZIP))
        assertEquals("PDF", format("Book.pdf", ArchiveKind.PDF))
        assertEquals("7z", format("Book.7z", ArchiveKind.SEVEN_ZIP))
    }

    @Test
    fun theContainerFollowsAComicExtension() {
        assertEquals("CBZ (ZIP)", format("Book.cbz", ArchiveKind.ZIP))
        assertEquals("CBR (RAR)", format("Book.cbr", ArchiveKind.RAR))
        assertEquals("CB7 (7z)", format("Book.cb7", ArchiveKind.SEVEN_ZIP))
        assertEquals("CBT (TAR)", format("Book.cbt", ArchiveKind.TAR))
    }

    @Test
    fun aMisnamedArchiveIsFlagged() {
        assertEquals("CBR (actually ZIP)", format("Book.cbr", ArchiveKind.ZIP))
        assertEquals("ZIP (actually RAR)", format("Book.zip", ArchiveKind.RAR))
        assertEquals("CBZ (actually 7z)", format("Book.CBZ", ArchiveKind.SEVEN_ZIP))
    }

    @Test
    fun imagesAndNamesWithoutExtension() {
        assertEquals("JXL", format("page.jxl", null))
        assertEquals("RAR", format("Book", ArchiveKind.RAR))
    }
}
