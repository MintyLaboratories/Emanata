package com.infinity.comicreader.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rebuilt archive is flat: names kept unless two match, OS litter left out. */
class FlatNamesTest {

    @Test
    fun osLitterIsJunkOtherFilesAreNot() {
        for (path in listOf("__MACOSX/a/001.jpg", "a/._001.jpg", "Thumbs.db", "x/desktop.ini", "x/.DS_Store", ".cache/001.jpg", "a/.thumbs/b/001.jpg")) {
            assertTrue(path, FlatNames.isJunk(path))
        }
        for (path in listOf("ComicInfo.xml", "a/001.jpg", ".hidden.txt", "notes/readme.txt", "...Extras/001.jpg")) assertFalse(path, FlatNames.isJunk(path))
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
        // Case alone counts as a match; a name still taken gets a number, here ahead of the place it keeps.
        assertEquals(listOf("a_X.jpg", "a_x 2.jpg"), FlatNames.of(listOf("a/X.jpg", "a/x.jpg"), listOf("X.jpg", "x.jpg")))
    }

    @Test
    fun foldersArePrefixedWhenBareNamesWouldChangeTheReadingOrder() {
        // Unique names, but "Part B/01" would come before "Part A/02".
        assertEquals(
            listOf("Part A_02.jpg", "Part A_03.jpg", "Part B_01.jpg", "ComicInfo.xml"),
            FlatNames.of(
                listOf("Part A/02.jpg", "Part A/03.jpg", "Part B/01.jpg", "ComicInfo.xml"),
                listOf("02.jpg", "03.jpg", "01.jpg", "ComicInfo.xml")
            )
        )
        // Pages alone decide: a text file out of order changes nothing.
        assertEquals(listOf("01.jpg", "02.jpg", "zz.txt"), FlatNames.of(listOf("b/01.jpg", "b/02.jpg", "a/zz.txt"), listOf("01.jpg", "02.jpg", "zz.txt")))
    }

    @Test
    fun placesAreNumberedWhenPrefixesStillChangeTheOrder() {
        // "A/" reads before "A B/", but "A_" sorts after "A B_".
        val paths = listOf("A/2.jpg", "A B/1.jpg")
        val flat = FlatNames.of(paths, listOf("2.jpg", "1.jpg"))
        assertEquals(listOf("1_A_2.jpg", "2_A B_1.jpg"), flat)
        assertEquals(listOf(0, 1), flat.indices.sortedWith(compareBy(com.infinity.comicreader.util.NaturalOrder) { flat[it] }))
    }

    @Test
    fun theNewFileIsNamedAfterTheBookUnlessTaken() {
        assertEquals("Book.cbz", FlatNames.output("Book.cbr", "compacted") { false })
        assertEquals("Book (compacted).cbz", FlatNames.output("Book.cbz", "compacted") { it == "Book.cbz" })
        assertEquals("Book (compatible 2).cbz", FlatNames.output("Book.cb7", "compatible") { it == "Book.cbz" || it == "Book (compatible).cbz" })
        assertEquals("page.jxl", FlatNames.withExtension("page.png", "jxl"))
    }
}
