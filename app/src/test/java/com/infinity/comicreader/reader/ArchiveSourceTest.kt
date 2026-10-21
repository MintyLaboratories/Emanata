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

class ArchiveSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(file: File): ArchiveSource =
        ArchiveSource(ArchiveReader.open(file, "UTF-8") ?: error("not an archive: $file"))

    private fun ArchiveSource.text(index: Int) = readPage(index) { String(it.readBytes()) }

    @Test
    fun imagesOnlyInNaturalOrder() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("p10.jpg"), entry("p2.png"), entry("notes.txt"), entry("p1.webp"), entry("ComicInfo.xml")
        )
        open(f).use { src ->
            assertEquals(listOf("p1.webp", "p2.png", "p10.jpg"), src.pagePaths)
            assertEquals("p2.png", src.text(1))
        }
    }

    @Test
    fun subfoldersKeepFolderThenFileOrder() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Book/ch2/01.jpg"), entry("Book/ch1 extra/01.jpg"), entry("Book/ch10/01.jpg"),
            entry("Book/ch1/10.jpg"), entry("Book/ch1/02.jpg"), entry("Book/cover.jpg")
        )
        open(f).use { src ->
            assertEquals(
                listOf(
                    "Book/ch1/02.jpg", "Book/ch1/10.jpg", "Book/ch1 extra/01.jpg",
                    "Book/ch2/01.jpg", "Book/ch10/01.jpg", "Book/cover.jpg"
                ),
                src.pagePaths
            )
        }
    }

    @Test
    fun cheapComicInfoOnlyAheadOfTheFirstPageInSolidArchives() {
        val info = entry("ComicInfo.xml", "<ComicInfo><Year>2004</Year></ComicInfo>".toByteArray())
        // 7z: stored after the pages, so reading it would unpack them first.
        val after = Fixtures.sevenZip(File(tmp.root, "after.cb7"), entry("01.jpg"), entry("02.jpg"), info)
        open(after).use { src ->
            assertNull(src.comicInfo(cheapOnly = true))
            assertTrue(src.comicInfoCostly())
            assertEquals(2004, src.comicInfo()?.year)
        }
        val before = Fixtures.sevenZip(File(tmp.root, "before.cb7"), info, entry("01.jpg"), entry("02.jpg"))
        open(before).use {
            assertEquals(2004, it.comicInfo(cheapOnly = true)?.year)
            assertFalse(it.comicInfoCostly())
        }
        // Zip reads any entry directly.
        val zip = Fixtures.zip(File(tmp.root, "book.cbz"), entry("01.jpg"), info)
        open(zip).use {
            assertEquals(2004, it.comicInfo(cheapOnly = true)?.year)
            assertFalse(it.comicInfoCostly())
        }
    }

    @Test
    fun zipNamedCbrOpensAsZip() {
        val f = Fixtures.zip(File(tmp.root, "book.cbr"), entry("001.jpg"), entry("002.jpg"))
        assertTrue(ArchiveReader.open(f, "UTF-8") is ZipReader)
        open(f).use { assertEquals(2, it.pageCount) }
    }

    @Test
    fun sevenZipPages() {
        val f = Fixtures.sevenZip(File(tmp.root, "book.cb7"), entry("b/2.jpg"), entry("a/1.jpg"), entry("a/x.txt"))
        open(f).use { src ->
            assertEquals(listOf("a/1.jpg", "b/2.jpg"), src.pagePaths)
            assertEquals("b/2.jpg", src.text(1))
        }
    }

    @Test
    fun tarPages() {
        val f = Fixtures.tar(
            File(tmp.root, "book.cbt"),
            entry("b/2.jpg"), entry("a/10.jpg"), entry("a/9.jpg"), entry("._a.jpg"), entry("a/x.txt")
        )
        assertTrue(ArchiveReader.open(f, "UTF-8") is TarReader)
        open(f).use { src ->
            assertEquals(listOf("a/9.jpg", "a/10.jpg", "b/2.jpg"), src.pagePaths)
            // Random access: a later page, then an earlier one.
            assertEquals("b/2.jpg", src.text(2))
            assertEquals("a/9.jpg", src.text(0))
        }
    }

    @Test
    fun preposixTarOpensByExtension() {
        val f = File(tmp.root, "old.cbt").apply { writeBytes(Fixtures.tarBytes(entry("1.jpg"), entry("2.jpg"), posix = false)) }
        open(f).use { src ->
            assertEquals(listOf("1.jpg", "2.jpg"), src.pagePaths)
            assertEquals("2.jpg", src.text(1))
        }
    }

    @Test
    fun nonArchiveHasNoReader() {
        val f = File(tmp.root, "book.cbz").apply { writeText("plain text") }
        assertNull(ArchiveReader.open(f, "UTF-8", ArchiveKind.sniff(f)))
    }

    @Test
    fun pageSizeReadsOnlyTheHeader() {
        // A frame header for 1300 x 2000, then bytes no decoder would take.
        val header = byteArrayOf(-1, -40, -1, -64, 0, 17, 8, 0x07, -48, 0x05, 0x14, 3) + ByteArray(9)
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("01.jpg", header + ByteArray(200_000) { 7 }))
        open(f).use { src -> assertEquals(listOf(1300, 2000), src.pageSize(0)?.toList()) }
    }

    @Test
    fun pageSizeReadsOnPastALargeHeaderSegment() {
        // A 20 KiB application segment before the frame header: past the first read.
        val app = byteArrayOf(-1, -30, 0x50, 0x00) + ByteArray(0x5000 - 2)
        val frame = byteArrayOf(-1, -64, 0, 17, 8, 0x07, -48, 0x05, 0x14, 3) + ByteArray(9)
        val jpeg = byteArrayOf(-1, -40) + app + frame + ByteArray(1000)
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("01.jpg", jpeg))
        open(f).use { src -> assertEquals(listOf(1300, 2000), src.pageSize(0)?.toList()) }
    }

    @Test
    fun aSolidInnerArchiveClosedForRoomStillMakesSizesCostly() {
        val solid = Fixtures.sevenZip(File(tmp.root, "a.cb7"), entry("01.jpg"), entry("02.jpg")).readBytes()
        val plain = { name: String -> Fixtures.zip(File(tmp.root, name), entry("01.jpg"), entry("02.jpg")).readBytes() }
        val outer = Fixtures.zip(
            File(tmp.root, "set.cbz"),
            "a.cb7" to solid, "b.cbz" to plain("b.cbz"), "c.cbz" to plain("c.cbz")
        )
        val cache = tmp.newFolder("nested")
        ArchiveSource(ZipReader(outer, "UTF-8"), NestedArchives.keyOf(outer), NestedArchives(cache, "UTF-8"), maxOpenInner = 1).use { src ->
            assertEquals(6, src.pageCount)
            assertFalse(src.cheapSizes)
        }
        val zips = Fixtures.zip(File(tmp.root, "zips.cbz"), "b.cbz" to plain("b.cbz"), "c.cbz" to plain("c.cbz"))
        ArchiveSource(ZipReader(zips, "UTF-8"), NestedArchives.keyOf(zips), NestedArchives(tmp.newFolder("nested2"), "UTF-8"), maxOpenInner = 1).use {
            assertTrue(it.cheapSizes)
        }
    }
}
