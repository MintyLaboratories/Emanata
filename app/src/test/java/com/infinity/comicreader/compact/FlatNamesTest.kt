package com.infinity.comicreader.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rebuilt archive is flat: names kept unless two match, OS litter left out. */
class FlatNamesTest {

    @Test
    fun osLitterIsJunkOtherFilesAreNot() {
        for (path in listOf("__MACOSX/a/001.jpg", "a/._001.jpg", "Thumbs.db", "x/desktop.ini", "x/.DS_Store")) assertTrue(path, FlatNames.isJunk(path))
        for (path in listOf("ComicInfo.xml", "a/001.jpg", ".hidden.txt", "notes/readme.txt")) assertFalse(path, FlatNames.isJunk(path))
    }

    @Test
    fun namesStayBareWhenNoTwoMatch() {
        assertEquals(
            listOf("001.jxl", "002.jpg", "ComicInfo.xml"),
            FlatNames.of(listOf("ch1/001.png", "ch2/002.jpg", "ComicInfo.xml"), listOf("001.jxl", "002.jpg", "ComicInfo.xml"))
        )
    }

    @Test
    fun aRepeatedNamePrefixesFoldersAndNumbersWhatStillClashes() {
        assertEquals(
            listOf("ch1_001.jxl", "ch2_001.jxl", "ch2_sub_002.jpg", "cover.jpg"),
            FlatNames.of(listOf("ch1/001.png", "ch2/001.png", "ch2\\sub\\002.jpg", "cover.jpg"), listOf("001.jxl", "001.jxl", "002.jpg", "cover.jpg"))
        )
        // Case alone counts as a match; a name still taken gets a number.
        assertEquals(listOf("a_x.jpg", "a_X 2.jpg"), FlatNames.of(listOf("a/x.jpg", "a/X.jpg"), listOf("x.jpg", "X.jpg")))
    }

    @Test
    fun theNewFileIsNamedAfterTheBookUnlessTaken() {
        assertEquals("Book.cbz", FlatNames.output("Book.cbr", "compacted") { false })
        assertEquals("Book (compacted).cbz", FlatNames.output("Book.cbz", "compacted") { it == "Book.cbz" })
        assertEquals("Book (compatible 2).cbz", FlatNames.output("Book.cb7", "compatible") { it == "Book.cbz" || it == "Book (compatible).cbz" })
        assertEquals("page.jxl", FlatNames.withExtension("page.png", "jxl"))
    }
}
