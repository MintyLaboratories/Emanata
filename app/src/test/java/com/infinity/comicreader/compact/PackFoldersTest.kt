package com.infinity.comicreader.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pack takes a folder holding images only, OS litter aside; anything else says why not. */
class PackFoldersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun files(vararg names: String) = names.map { it to false }

    @Test
    fun imagesAloneArePackable() {
        assertNull(PackFolders.why(files("01.jpg", "02.png", "03.webp", "04.jxl")))
    }

    @Test
    fun osLitterIsIgnored() {
        assertNull(PackFolders.why(files("01.jpg", "Thumbs.db", "desktop.ini", ".DS_Store", "._01.jpg", ".nomedia")))
        assertTrue(PackFolders.isJunk("ehthumbs.db"))
    }

    @Test
    fun aFolderInsideBlocksIt() {
        assertEquals(PackFolders.Why.SUBFOLDERS, PackFolders.why(files("01.jpg") + ("Extras" to true)))
    }

    @Test
    fun anotherFileBlocksIt() {
        assertEquals(PackFolders.Why.OTHER_FILES, PackFolders.why(files("01.jpg", "ComicInfo.xml")))
        assertEquals(PackFolders.Why.OTHER_FILES, PackFolders.why(files("01.jpg", "Book.cbz")))
    }

    @Test
    fun litterAloneIsNoImages() {
        assertEquals(PackFolders.Why.NO_IMAGES, PackFolders.why(files("Thumbs.db")))
        assertEquals(PackFolders.Why.NO_IMAGES, PackFolders.why(emptyList()))
    }

    @Test
    fun aFolderListsItsImagesInNaturalOrderWithoutLitter() {
        val dir = tmp.newFolder("Loose")
        for (n in listOf("10.jpg", "2.jpg", "1.png", "Thumbs.db")) File(dir, n).writeBytes(byteArrayOf(1, 2, 3))
        val contents = PackFolders.of(dir)
        assertNull(contents.why)
        assertEquals(listOf("1.png", "2.jpg", "10.jpg"), contents.images.map { it.name })
        val reader = FolderReader(contents.images)
        assertEquals(listOf("1.png", "2.jpg", "10.jpg"), reader.entries.map { it.path })
        assertEquals(File(dir, "2.jpg"), reader.looseFile(reader.entries[1]))
        assertEquals(3, reader.read(reader.entries[2]) { it.readBytes() }.size)
    }

    @Test
    fun theFirstFolderThatCannotBeTakenIsNamed() {
        val good = tmp.newFolder("Good").also { File(it, "1.jpg").writeBytes(byteArrayOf(1)) }
        val bad = tmp.newFolder("Bad").also { File(it, "1.jpg").writeBytes(byteArrayOf(1)); File(it, "notes.txt").writeText("x") }
        assertNull(PackFolders.firstBlocked(listOf(good)))
        assertEquals(bad to PackFolders.Why.OTHER_FILES, PackFolders.firstBlocked(listOf(good, bad)))
        assertEquals(PackFolders.Why.UNREADABLE, PackFolders.of(File(tmp.root, "none")).why)
    }
}
