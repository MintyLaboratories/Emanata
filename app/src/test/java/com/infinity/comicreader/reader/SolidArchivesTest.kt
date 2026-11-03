package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Fixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SolidArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pages = arrayOf(entry("01.jpg"), entry("02.jpg"), entry("ComicInfo.xml"))

    private fun file(name: String, bytes: ByteArray) = File(tmp.newFolder(), name).apply { writeBytes(bytes) }

    @Test
    fun rar4SolidByItsMainHeader() {
        assertTrue(SolidArchives.isSolid(file("a.cbr", Fixtures.rar4Bytes(*pages, solid = true))))
        assertFalse(SolidArchives.isSolid(file("a.cbr", Fixtures.rar4Bytes(*pages))))
    }

    @Test
    fun rar5SolidByItsArchiveFlags() {
        assertTrue(SolidArchives.isSolid(file("a.cbr", Fixtures.rar5Bytes(*pages, solid = true))))
        assertFalse(SolidArchives.isSolid(file("a.cbr", Fixtures.rar5Bytes(*pages))))
    }

    @Test
    fun sevenZipSolidWhenABlockHoldsSeveralEntries() {
        val solid = Fixtures.solidSevenZip(File(tmp.root, "solid.cb7"), *pages)
        assertTrue(SolidArchives.isSolid(solid))
        SevenZipReader(solid).use { assertEquals(true, it.solid) }
        val plain = Fixtures.sevenZip(File(tmp.root, "plain.cb7"), *pages)
        assertFalse(SolidArchives.isSolid(plain))
        SevenZipReader(plain).use { assertEquals(false, it.solid) }
    }

    @Test
    fun solidSevenZipReadsItsEntries() {
        val solid = Fixtures.solidSevenZip(File(tmp.root, "solid.cb7"), *pages)
        SevenZipReader(solid).use { r ->
            assertFalse(r.randomAccess)
            assertEquals(listOf("01.jpg", "02.jpg", "ComicInfo.xml"), r.entries.map { it.path })
            assertEquals("02.jpg", r.read(r.entries[1]) { String(it.readBytes()) })
        }
    }

    @Test
    fun otherFilesAreNotSolid() {
        assertFalse(SolidArchives.isSolid(Fixtures.zip(File(tmp.root, "a.cbz"), *pages)))
        assertFalse(SolidArchives.isSolid(Fixtures.tar(File(tmp.root, "a.cbt"), *pages)))
        assertFalse(SolidArchives.isSolid(file("a.cbr", "not a rar".toByteArray())))
        assertFalse(SolidArchives.isSolid(File(tmp.root, "missing.cbr")))
    }

    @Test
    fun rarHeadersCutShortOrEncryptedTellNothing() {
        val rar5 = Fixtures.rar5Bytes(*pages, solid = true)
        assertNull(SolidArchives.rarSolid(rar5.copyOf(12)))
        assertNull(SolidArchives.rarSolid(Fixtures.rar4Bytes(*pages).copyOf(9)))
        // RAR 5 headers encrypted: type 4 comes first.
        val encrypted = rar5.copyOf().also { it[8 + 4 + 1] = 4 }
        assertNull(SolidArchives.rarSolid(encrypted))
        assertNull(SolidArchives.rarSolid(ByteArray(0)))
    }
}
