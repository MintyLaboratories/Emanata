package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.ArchiveKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BookInfoTest {

    @Test
    fun theExtensionAloneWhenItNamesTheContainer() {
        assertEquals("ZIP", BookInfo.format("Book.zip", ArchiveKind.ZIP))
        assertEquals("PDF", BookInfo.format("Book.pdf", ArchiveKind.PDF))
        assertEquals("7Z", BookInfo.format("Book.7z", ArchiveKind.SEVEN_ZIP))
    }

    @Test
    fun theContainerFollowsAComicExtension() {
        assertEquals("CBZ (ZIP)", BookInfo.format("Book.cbz", ArchiveKind.ZIP))
        assertEquals("CB7 (7z)", BookInfo.format("Book.cb7", ArchiveKind.SEVEN_ZIP))
        assertEquals("CBT (TAR)", BookInfo.format("Book.cbt", ArchiveKind.TAR))
    }

    @Test
    fun aMisnamedArchiveSaysWhatItIs() {
        assertEquals("CBR (ZIP)", BookInfo.format("Book.cbr", ArchiveKind.ZIP))
    }

    @Test
    fun imagesAndNamesWithoutExtension() {
        assertEquals("JXL", BookInfo.format("page.jxl", null))
        assertEquals("RAR", BookInfo.format("Book", ArchiveKind.RAR))
    }
}
