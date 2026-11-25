package minty.emanata.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun files(vararg names: String) = names.map { PackFolders.Child(it) }

    private fun folder(name: String) = PackFolders.Child(name, dir = true)

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
        assertEquals(PackFolders.Why.SUBFOLDERS, PackFolders.why(files("01.jpg") + folder("Extras")))
        assertEquals(PackFolders.Why.SUBFOLDERS, PackFolders.why(files("01.jpg") + folder("...Extras")))
    }

    @Test
    fun litterFoldersAreIgnored() {
        assertNull(PackFolders.why(files("01.jpg") + folder("__MACOSX") + folder(".thumbnails")))
        assertEquals(PackFolders.Why.NO_IMAGES, PackFolders.why(listOf(folder("__MACOSX"))))
        val dir = tmp.newFolder("Mac")
        File(dir, "1.jpg").writeBytes(byteArrayOf(1))
        File(dir, "__MACOSX").mkdirs()
        assertEquals(listOf("1.jpg"), PackFolders.of(dir).images.map { it.name })
    }

    @Test
    fun anotherFileBlocksIt() {
        assertEquals(PackFolders.Why.OTHER_FILES, PackFolders.why(files("01.jpg", "notes.txt")))
        assertEquals(PackFolders.Why.OTHER_FILES, PackFolders.why(files("01.jpg", "Book.cbz")))
    }

    @Test
    fun comicInfoGoesAlongAsAnExtra() {
        assertNull(PackFolders.why(files("01.jpg", "ComicInfo.xml")))
        assertNull(PackFolders.why(files("01.jpg", "comicinfo.XML")))
        assertEquals(PackFolders.Why.NO_IMAGES, PackFolders.why(files("ComicInfo.xml")))
        val dir = tmp.newFolder("Info")
        for (n in listOf("2.jpg", "1.jpg", "ComicInfo.xml")) File(dir, n).writeBytes(byteArrayOf(1))
        val contents = PackFolders.of(dir)
        assertEquals(listOf("1.jpg", "2.jpg"), contents.images.map { it.name })
        assertEquals(listOf("ComicInfo.xml"), contents.extras.map { it.name })
        assertEquals(listOf("1.jpg", "2.jpg", "ComicInfo.xml"), contents.packed(dir, unusualToo = false).map { it.name })
    }

    @Test
    fun dotFilesDotFoldersAndEmptyFilesAreUnusualNeverPages() {
        assertTrue(PackFolders.isUnusual(PackFolders.Child(".cover.jpg")))
        assertTrue(PackFolders.isUnusual(PackFolders.Child(".notes.txt")))
        assertTrue(PackFolders.isUnusual(PackFolders.Child("05.jpg", size = 0)))
        assertTrue(PackFolders.isUnusual(PackFolders.Child("empty.txt", size = 0)))
        assertTrue(PackFolders.isUnusual(folder(".thumbnails")))
        // OS litter stays litter; an ellipsis is not a dot.
        assertFalse(PackFolders.isUnusual(PackFolders.Child(".DS_Store")))
        assertFalse(PackFolders.isUnusual(PackFolders.Child("...more.jpg")))
        assertFalse(PackFolders.isUnusual(folder("__MACOSX")))
        // They never block, nor count as images.
        assertNull(PackFolders.why(files("01.jpg", ".notes.txt") + PackFolders.Child("empty.txt", size = 0)))
        assertEquals(PackFolders.Why.NO_IMAGES, PackFolders.why(files(".cover.jpg") + PackFolders.Child("01.jpg", size = 0)))
        val dir = tmp.newFolder("Odd")
        File(dir, "1.jpg").writeBytes(byteArrayOf(1))
        File(dir, "2.jpg").writeBytes(ByteArray(0))
        File(dir, ".cover.jpg").writeBytes(byteArrayOf(1))
        File(dir, ".cache/a").mkdirs()
        File(dir, ".cache/a/x.bin").writeBytes(byteArrayOf(1))
        val contents = PackFolders.of(dir)
        assertEquals(listOf("1.jpg"), contents.images.map { it.name })
        assertEquals(listOf(".cache", ".cover.jpg", "2.jpg"), contents.unusual.map { it.name })
        assertEquals(listOf("1.jpg"), contents.packed(dir, unusualToo = false).map { PackFolders.pathIn(dir, it) })
        assertEquals(listOf(".cache/a/x.bin", ".cover.jpg", "1.jpg", "2.jpg"), contents.packed(dir, unusualToo = true).map { PackFolders.pathIn(dir, it) })
        val reader = FolderReader(contents.packed(dir, unusualToo = true), dir)
        assertEquals(".cache/a/x.bin", reader.entries[0].path)
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
