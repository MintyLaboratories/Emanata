package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.FolderSize
import com.infinity.comicreader.reader.Fixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookFactsReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun read(file: File) = BookFactsReader.read(file, "UTF-8", tmp.newFolder())

    @Test
    fun countsPagesAndReadsTheComicInfoYear() {
        val info = "<?xml version=\"1.0\"?><ComicInfo><Series>Series A</Series><Year> 2014 </Year></ComicInfo>"
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("01.jpg"), entry("02.png"), entry("notes.txt"), entry("__MACOSX/._01.jpg"),
            entry("ComicInfo.xml", info.toByteArray())
        )
        assertEquals(BookFacts(pages = 2, year = 2014), read(f))
    }

    @Test
    fun withoutComicInfoTheYearIsUnknown() {
        val f = Fixtures.sevenZip(File(tmp.root, "book.cb7"), entry("a/01.jpg"), entry("a/02.jpg"), entry("a/03.jpg"))
        assertEquals(BookFacts(pages = 3, year = 0), read(f))
    }

    @Test
    fun innerArchivesCountTheirPages() {
        val inner = Fixtures.zipBytes(entry("01.jpg"), entry("02.jpg"))
        val f = Fixtures.zip(File(tmp.root, "omnibus.cbz"), entry("Vol 1.cbz", inner), entry("cover.jpg"))
        // Inner archives not copied out yet cannot be counted.
        assertEquals(-1, read(f).pages)
    }

    @Test
    fun imagesAreOnePage() {
        val f = File(tmp.root, "page.png").apply { writeBytes(TestImages.png(2, 2)) }
        assertEquals(BookFacts(pages = 1, year = 0), read(f))
    }

    @Test
    fun comicInfoPageCountStandsInForUncopiedInnerArchives() {
        val inner = Fixtures.zipBytes(entry("01.jpg"), entry("02.jpg"))
        val info = "<ComicInfo><PageCount>24</PageCount></ComicInfo>"
        val f = Fixtures.zip(File(tmp.root, "omnibus.cbz"), entry("Vol 1.cbz", inner), entry("ComicInfo.xml", info.toByteArray()))
        assertEquals(24, read(f).pages)
    }

    @Test
    fun countedPagesBeatComicInfo() {
        val info = "<ComicInfo><PageCount>99</PageCount></ComicInfo>"
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("01.jpg"), entry("ComicInfo.xml", info.toByteArray()))
        assertEquals(1, read(f).pages)
    }

    @Test
    fun topmostComicInfoWins() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("extras/ComicInfo.xml", "<ComicInfo><Year>1990</Year></ComicInfo>".toByteArray()),
            entry("comicinfo.xml", "<ComicInfo><Year>2001</Year></ComicInfo>".toByteArray()),
            entry("01.jpg")
        )
        assertEquals(2001, read(f).year)
    }

    @Test
    fun folderSizeAddsEverythingBelow() {
        val dir = tmp.newFolder("books")
        File(dir, "a.cbz").writeBytes(ByteArray(100))
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.cbz").writeBytes(ByteArray(23))
        assertEquals(123L, FolderSize.total(dir))
        assertEquals(0L, FolderSize.total(dir) { false })
    }
}
