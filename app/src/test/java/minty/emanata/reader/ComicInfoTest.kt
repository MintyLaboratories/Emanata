package minty.emanata.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicInfoTest {

    @Test
    fun readsTheFieldsTheAppUses() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <Title>Chapter One</Title>
              <Series>Series &amp; Sons</Series>
              <Number>12.5</Number>
              <Year>2014</Year>
              <Publisher>Publisher A</Publisher>
              <PageCount>3</PageCount>
              <Manga>YesAndRightToLeft</Manga>
              <Pages>
                <Page Image="0" Type="FrontCover" ImageWidth="1000" />
                <Page Image="1" DoublePage="True" />
                <Page Image="2" Type="BackCover" DoublePage="false" />
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val info = ComicInfo.parse(xml)
        assertEquals("Series & Sons", info.series)
        assertEquals("12.5", info.number)
        assertEquals(2014, info.year)
        assertEquals("Publisher A", info.publisher)
        assertEquals(3, info.pageCount)
        assertEquals(ComicInfo.Manga.YES_RTL, info.manga)
        assertEquals(
            listOf(
                ComicInfo.Page(0, "FrontCover", false),
                ComicInfo.Page(1, null, true),
                ComicInfo.Page(2, "BackCover", false)
            ),
            info.pages
        )
        // Every field with a value, for Book info: named as written, in file order, Pages left out.
        assertEquals(
            listOf("Title" to "Chapter One", "Series" to "Series & Sons", "Number" to "12.5", "Year" to "2014", "Publisher" to "Publisher A", "PageCount" to "3", "Manga" to "YesAndRightToLeft"),
            info.fields
        )
    }

    @Test
    fun mangaValues() {
        fun manga(v: String) = ComicInfo.parse("<ComicInfo><Manga>$v</Manga></ComicInfo>").manga
        assertEquals(ComicInfo.Manga.YES_RTL, manga(" yesandrighttoleft "))
        assertEquals(ComicInfo.Manga.YES, manga("Yes"))
        assertEquals(ComicInfo.Manga.NO, manga("No"))
        assertEquals(ComicInfo.Manga.UNKNOWN, manga("Unknown"))
        assertEquals(ComicInfo.Manga.UNKNOWN, ComicInfo.parse("<ComicInfo/>").manga)
    }

    @Test
    fun namesMatchWithoutCase() {
        val info = ComicInfo.parse("<comicinfo><year>1999</year><SERIES>Series B</SERIES></comicinfo>")
        assertEquals(1999, info.year)
        assertEquals("Series B", info.series)
    }

    @Test
    fun implausibleOrMissingValuesAreAbsent() {
        val info = ComicInfo.parse("<ComicInfo><Year>0</Year><PageCount>-4</PageCount><Series> </Series><Month>3</Month></ComicInfo>")
        assertEquals(0, info.year)
        assertEquals(0, info.pageCount)
        assertNull(info.series)
        assertEquals(0, ComicInfo.parse("<ComicInfo><Year>99999</Year></ComicInfo>").year)
    }

    @Test
    fun onlyTopLevelFieldsCount() {
        // A <Year> nested in another element is not the book's.
        val info = ComicInfo.parse("<ComicInfo><Extra><Year>1980</Year></Extra><Year>2001</Year></ComicInfo>")
        assertEquals(2001, info.year)
    }

    @Test
    fun brokenDocumentsKeepWhatParsed() {
        val info = ComicInfo.parse("<ComicInfo><Series>Series C</Series><Year>2010</Year><Number>4")
        assertEquals("Series C", info.series)
        assertEquals(2010, info.year)
        assertEquals(ComicInfo(), ComicInfo.parse("not xml at all"))
        assertEquals(ComicInfo(), ComicInfo.parse(""))
    }

    @Test
    fun byteOrderMarkAndUtf8() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "<ComicInfo><Series>Zażółć</Series></ComicInfo>".toByteArray(Charsets.UTF_8)
        assertEquals("Zażółć", ComicInfo.parse(bytes).series)
    }

    @Test
    fun encodingFromMarkOrDeclaration() {
        val body = "<ComicInfo><Series>Zażółć</Series><Year>2004</Year></ComicInfo>"
        val utf16 = ComicInfo.parse("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16\"?>$body".toByteArray(Charsets.UTF_16LE))
        assertEquals("Zażółć", utf16.series)
        assertEquals(2004, utf16.year)
        assertEquals("Zażółć", ComicInfo.parse("<?xml version=\"1.0\"?>$body".toByteArray(Charsets.UTF_16BE)).series)
        val latin2 = ComicInfo.parse("<?xml version='1.0' encoding='ISO-8859-2'?>$body".toByteArray(charset("ISO-8859-2")))
        assertEquals("Zażółć", latin2.series)
    }

    @Test
    fun undecodableBytesKeepTheRest() {
        // Latin-1 bytes with no declaration: read as UTF-8, the stray byte is replaced, not fatal.
        val info = ComicInfo.parse("<ComicInfo><Summary>café</Summary><Year>2004</Year></ComicInfo>".toByteArray(Charsets.ISO_8859_1))
        assertEquals(2004, info.year)
        assertEquals(Charsets.UTF_8, ComicInfo.charsetOf("<?xml version=\"1.0\" encoding=\"no-such\"?>".toByteArray()))
    }

    @Test
    fun externalEntitiesAreNeverRead() {
        val xml = """<?xml version="1.0"?><!DOCTYPE ComicInfo [<!ENTITY x SYSTEM "file:///etc/hostname">]>""" +
            "<ComicInfo><Series>A&x;</Series><Year>2003</Year></ComicInfo>"
        val info = ComicInfo.parse(xml)
        assertEquals(2003, info.year)
        assertFalse(info.series.orEmpty().length > 1)
    }

    @Test
    fun recognizesTheFileName() {
        assertTrue(ComicInfo.isComicInfo("ComicInfo.xml"))
        assertTrue(ComicInfo.isComicInfo("Book/comicinfo.XML"))
        assertFalse(ComicInfo.isComicInfo("ComicInfo.xml.bak"))
    }
}
