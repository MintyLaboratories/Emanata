package com.infinity.comicreader.reader

import com.infinity.comicreader.data.ReadingDirection
import com.infinity.comicreader.reader.DirectionDetection.Detected
import com.infinity.comicreader.reader.DirectionDetection.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionDetectionTest {

    private val plainPages = (1..12).map { "Series A %03d.jpg".format(it) }
    private val mangaPages = (0..11).map { "Series B - c001 (v01) - p%03d [Digital].jpg".format(it) }

    private fun detect(
        info: ComicInfo? = null,
        file: String = "Series A 001 (2014).cbz",
        pages: List<String> = plainPages,
        cover: Wraparound.Half? = null
    ) = DirectionDetection.detect(info, file, pages) { cover }

    @Test
    fun comicInfoRightToLeftComesFirst() {
        var asked = false
        val found = DirectionDetection.detect(ComicInfo(manga = ComicInfo.Manga.YES_RTL), "a.cbz", plainPages) {
            asked = true
            Wraparound.Half.RIGHT
        }
        assertEquals(Detected(ReadingDirection.RTL, Source.COMIC_INFO), found)
        // The cover takes a decode, so it is not looked at once ComicInfo decides.
        assertFalse(asked)
    }

    @Test
    fun otherMangaValuesDecideNothing() {
        assertNull(detect(ComicInfo(manga = ComicInfo.Manga.YES)))
        assertNull(detect(ComicInfo(manga = ComicInfo.Manga.NO)))
    }

    @Test
    fun wraparoundFrontSide() {
        assertEquals(Detected(ReadingDirection.RTL, Source.COVER), detect(cover = Wraparound.Half.LEFT))
        // The cover outranks a publisher tag.
        assertEquals(
            Detected(ReadingDirection.LTR, Source.COVER),
            detect(file = "Series B v01 [Kodansha Comics].cbz", cover = Wraparound.Half.RIGHT)
        )
    }

    @Test
    fun publisherFromComicInfoOrTags() {
        assertEquals(
            Detected(ReadingDirection.RTL, Source.PUBLISHER, "VIZ"),
            detect(ComicInfo(publisher = "VIZ Media LLC"))
        )
        assertEquals(
            Detected(ReadingDirection.RTL, Source.PUBLISHER, "Kodansha"),
            detect(file = "Series B v01 (2018) (Digital) [Kodansha Comics].cbz")
        )
        val tagged = plainPages + "Series B v01 - p001 [Yen Press] [Scanner].jpg"
        assertEquals("Yen Press", detect(pages = tagged)?.detail)
        // A folder path is not a tag of the page.
        assertNull(detect(pages = listOf("[Seven Seas]/001.jpg", "[Seven Seas]/002.jpg")))
    }

    @Test
    fun westernPublishersAndOtherTagsTellNothing() {
        assertNull(detect(ComicInfo(publisher = "Publisher A")))
        assertNull(detect(file = "Series A 001 (2014) (Digital) [Webrip].cbz"))
        assertNull(detect(pages = plainPages + "zzz [Digital].jpg"))
    }

    @Test
    fun mangaNamingIsTheLastResort() {
        assertEquals(Detected(ReadingDirection.RTL, Source.NAMING), detect(pages = mangaPages))
        // A publisher tag among them names the source instead.
        assertEquals(Source.PUBLISHER, detect(pages = mangaPages + "c001 (v01) - p999 [VIZ Media].jpg")?.source)
    }

    @Test
    fun mangaNaming() {
        assertTrue(DirectionDetection.isMangaNaming(mangaPages))
        assertTrue(DirectionDetection.isMangaNaming(listOf("c012.5 - p001.png", "c012.5 - p002.png", "c012.5 - p003.png")))
        assertFalse(DirectionDetection.isMangaNaming(mangaPages.take(2)))
        assertFalse(DirectionDetection.isMangaNaming(plainPages))
        // Issue-page names and c2c tags are not chapter numbers.
        assertFalse(DirectionDetection.isMangaNaming(listOf("Series A 001-002.jpg", "Series A 001-003.jpg", "Series A 001-004.jpg")))
        assertFalse(DirectionDetection.isMangaNaming(listOf("Series A (c2c) p01.jpg", "Series A (c2c) p02.jpg", "Series A (c2c) p03.jpg")))
        // Mostly: a few tagged pages do not count.
        assertFalse(DirectionDetection.isMangaNaming(plainPages + mangaPages.take(4)))
    }

    @Test
    fun publisherWordsMatchFromTheStart() {
        assertEquals("VIZ", MangaPublishers.match("VIZ Media"))
        assertEquals("Kodansha", MangaPublishers.match("Kodansha USA Publishing"))
        assertEquals("Seven Seas", MangaPublishers.match("seven-seas entertainment"))
        assertEquals("Vertical", MangaPublishers.match("Vertical Comics"))
        assertNull(MangaPublishers.match("Vertigo"))
        assertNull(MangaPublishers.match("Digital"))
        assertNull(MangaPublishers.match("Dark Horse Comics"))
        assertEquals("Dark Horse Manga", MangaPublishers.match("Dark Horse Manga"))
        assertNull(MangaPublishers.match(null))
        assertNull(MangaPublishers.match("  "))
    }

    @Test
    fun encodedForStorage() {
        for (direction in ReadingDirection.entries) for (source in Source.entries) {
            val d = Detected(direction, source)
            assertTrue(d.encode() > 0)
            assertEquals(d, Detected.decode(d.encode()))
        }
        assertNull(Detected.decode(0))
        assertNull(Detected.decode(99))
    }
}
