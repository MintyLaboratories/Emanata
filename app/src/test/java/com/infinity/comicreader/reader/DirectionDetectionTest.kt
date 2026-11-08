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
    fun coverAddedLaterKeepsItsRank() {
        val publisher = DirectionDetection.fromContents(null, "Series B v01 [Kodansha Comics].cbz", plainPages)
        assertEquals(Source.PUBLISHER, publisher?.source)
        assertEquals(Detected(ReadingDirection.LTR, Source.COVER), DirectionDetection.withCover(publisher, Wraparound.Half.RIGHT))
        assertEquals(publisher, DirectionDetection.withCover(publisher, null))
        val comicInfo = Detected(ReadingDirection.RTL, Source.COMIC_INFO)
        assertTrue(DirectionDetection.outranksCover(comicInfo))
        assertEquals(comicInfo, DirectionDetection.withCover(comicInfo, Wraparound.Half.RIGHT))
        assertEquals(Detected(ReadingDirection.RTL, Source.COVER), DirectionDetection.withCover(null, Wraparound.Half.LEFT))
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
        val tagged = plainPages + "Series B v01 - p001 [Square Enix] [Scanner].jpg"
        assertEquals("Square Enix", detect(pages = tagged)?.detail)
        // A folder path is not a tag of the page.
        assertNull(detect(pages = listOf("[Square Enix]/001.jpg", "[Square Enix]/002.jpg")))
    }

    @Test
    fun westernPublishersAndOtherTagsTellNothing() {
        assertNull(detect(ComicInfo(publisher = "Publisher A")))
        assertNull(detect(file = "Series A 001 (2014) (Digital) [Webrip].cbz"))
        assertNull(detect(pages = plainPages + "zzz [Digital].jpg"))
    }

    @Test
    fun mangaNamingAloneDecidesNothing() {
        assertNull(detect(pages = mangaPages))
        assertEquals(Source.PUBLISHER, detect(pages = mangaPages + "c001 (v01) - p999 [VIZ Media].jpg")?.source)
    }

    @Test
    fun publisherWordsMatchFromTheStart() {
        assertEquals("VIZ", MangaPublishers.match("VIZ Media"))
        assertEquals("Kodansha", MangaPublishers.match("Kodansha USA Publishing"))
        assertEquals("Ghost Ship", MangaPublishers.match("ghost-ship press"))
        // Large manhwa and webtoon lines: left out.
        assertNull(MangaPublishers.match("Yen Press"))
        assertNull(MangaPublishers.match("Seven Seas Entertainment"))
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
        assertNull(Detected.decode(-1))
        assertNull(Detected.decode(99))
        val all = ReadingDirection.entries.flatMap { d -> Source.entries.map { Detected(d, it).encode() } }
        assertEquals(1..all.size, all.min()..all.max())
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun storedNumbersStayPut() {
        val bump = "stored numbers changed: bump BookFactsCache.VERSION"
        assertEquals(bump, 1, Detected(ReadingDirection.LTR, Source.COMIC_INFO).encode())
        assertEquals(bump, 5, Detected(ReadingDirection.RTL, Source.COVER).encode())
        assertEquals(bump, 8, Detected(ReadingDirection.RTL, Source.PUBLISHER).encode())
        assertEquals(bump, 12, Detected(ReadingDirection.TTB, Source.SHAPE).encode())
        assertEquals(bump, 15, Detected(ReadingDirection.TTB, Source.SEAMS).encode())
    }
}
