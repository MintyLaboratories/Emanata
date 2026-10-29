package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.FolderSize
import com.infinity.comicreader.data.ReadingDirection
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
    fun comicInfoIsReadHoweverFarIntoASolidArchive() {
        val info = entry("ComicInfo.xml", "<ComicInfo><Title>One Shot</Title></ComicInfo>".toByteArray())
        val after = File(tmp.root, "after.cbr").apply { writeBytes(Fixtures.rar4Bytes(entry("01.jpg"), entry("02.jpg"), info, solid = true)) }
        assertEquals("One Shot", BookFactsReader.comicInfo(after, "UTF-8", tmp.newFolder())?.fields?.single()?.second)
        val none = Fixtures.zip(File(tmp.root, "none.cbz"), entry("01.jpg"))
        assertEquals(null, BookFactsReader.comicInfo(none, "UTF-8", tmp.newFolder()))
        val page = File(tmp.root, "page.png").apply { writeBytes(TestImages.png(2, 2)) }
        assertEquals(null, BookFactsReader.comicInfo(page, "UTF-8", tmp.newFolder()))
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
    fun detectsTheDirectionWithoutTheCover() {
        val info = "<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>"
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("01.jpg"), entry("ComicInfo.xml", info.toByteArray()))
        assertEquals(DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.COMIC_INFO), read(f).detected)
        val tagged = Fixtures.zip(File(tmp.root, "tagged.cbz"), entry("Series B - p000 [Kodansha Comics].jpg"), entry("p001.jpg"))
        assertEquals(DirectionDetection.Source.PUBLISHER, read(tagged).detected?.source)
        val plain = Fixtures.zip(File(tmp.root, "plain.cbz"), entry("01.jpg"), entry("02.jpg"))
        assertEquals(null, read(plain).detected)
    }

    @Test
    fun slicesTellByTheirHeaders() {
        val heights = listOf(300, 380, 260, 450, 320)
        val strip = Fixtures.zip(File(tmp.root, "strip.cbz"), *heights.mapIndexed { i, h -> entry("p%02d.png".format(i), TestImages.png(100, h)) }.toTypedArray())
        assertEquals(Slices.Checked(DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SHAPE)), read(strip).slices)
        assertEquals(ReadingDirection.TTB, read(strip).direction)
        val pages = Fixtures.zip(File(tmp.root, "pages.cbz"), *(0 until 6).map { entry("p%02d.png".format(it), TestImages.png(200, 300)) }.toTypedArray())
        assertEquals(Slices.Checked(null), read(pages).slices)
        // Uniform slices wait for the reader to check their seams.
        val uniform = Fixtures.zip(File(tmp.root, "uniform.cbz"), *(0 until 6).map { entry("p%02d.png".format(it), TestImages.png(100, 400)) }.toTypedArray())
        assertEquals(null, read(uniform).slices)
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
