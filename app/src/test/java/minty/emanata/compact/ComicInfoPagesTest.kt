package minty.emanata.compact

import minty.emanata.reader.ComicInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.charset.Charset

/** A rebuilt book's ComicInfo.xml follows its pages, every other byte as it was. */
class ComicInfoPagesTest {

    private val p0 = """<Page Image="0" Type="FrontCover" ImageSize="1000" ImageWidth="800" ImageHeight="1200" />"""
    private val p1 = """<Page Image="1" ImageSize="1001" />"""
    private val p2 = """<Page Image="2" DoublePage="True" ImageSize="1002" />"""
    private val p3 = """<Page Image="3" ImageSize="1003"></Page>"""

    /** A ComicInfo.xml with [count] as its PageCount and [pages] as its Pages, either left out when null; lines end in [nl]. */
    private fun xml(count: String?, pages: List<String>?, nl: String = "\n", encoding: String = "utf-8", series: String = "Test") = (
        listOfNotNull(
            """<?xml version="1.0" encoding="$encoding"?>""",
            """<ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema">""",
            "  <Series>$series</Series>",
            count?.let { "  <PageCount>$it</PageCount>" }
        ) + (pages?.let { listOf("  <Pages>") + it.map { p -> "    $p" } + "  </Pages>" } ?: emptyList()) + listOf("</ComicInfo>", "")
        ).joinToString(nl)

    private val four = xml("4", listOf(p0, p1, p2, p3))

    private fun edit(text: String, moves: IntArray, count: Int, remade: Map<Int, ComicInfoPages.Remade> = emptyMap(), charset: Charset = Charsets.UTF_8): String =
        String(ComicInfoPages.edit(text.toByteArray(charset), moves, count, remade), charset)

    @Test
    fun skippingTheFirstPageRemovesItsEntryAndRenumbersTheRest() {
        val out = edit(four, intArrayOf(-1, 0, 1, 2), 3)
        assertEquals(
            xml("3", listOf("""<Page Image="0" ImageSize="1001" />""", """<Page Image="1" DoublePage="True" ImageSize="1002" />""", """<Page Image="2" ImageSize="1003"></Page>""")),
            out
        )
        val info = ComicInfo.parse(out)
        assertEquals(3, info.pageCount)
        assertEquals(listOf(ComicInfo.Page(0, null, false), ComicInfo.Page(1, null, true), ComicInfo.Page(2, null, false)), info.pages)
    }

    @Test
    fun skippingAMiddlePage() {
        assertEquals(
            xml("3", listOf(p0, """<Page Image="1" DoublePage="True" ImageSize="1002" />""", """<Page Image="2" ImageSize="1003"></Page>""")),
            edit(four, intArrayOf(0, -1, 1, 2), 3)
        )
    }

    @Test
    fun skippingTheLastPagesEvenAnElementWithAnEndTag() {
        assertEquals(xml("2", listOf(p0, p1)), edit(four, intArrayOf(0, 1, -1, -1), 2))
        // Every page gone: an empty Pages stays.
        assertEquals(xml("0", emptyList()), edit(four, intArrayOf(-1, -1, -1, -1), 0))
    }

    @Test
    fun entriesFollowPagesThatMoved() {
        val out = edit(xml("2", listOf(p0, p1)), intArrayOf(1, 0), 2)
        assertEquals(xml("2", listOf(p0.replace("Image=\"0\"", "Image=\"1\""), p1.replace("Image=\"1\"", "Image=\"0\""))), out)
    }

    @Test
    fun pagesMadeAnewTakeTheirNewSizeAndLoseAPixelSizeThatChanged() {
        val remade = mapOf(0 to ComicInfoPages.Remade(400, resized = true), 1 to ComicInfoPages.Remade(500, resized = false))
        assertEquals(
            xml("4", listOf("""<Page Image="0" Type="FrontCover" ImageSize="400" />""", """<Page Image="1" ImageSize="500" />""", p2, p3)),
            edit(four, intArrayOf(0, 1, 2, 3), 4, remade)
        )
        // A pixel size kept stays; sizes follow their pages by their old places.
        assertEquals(
            xml("3", listOf("""<Page Image="0" Type="FrontCover" ImageSize="400" ImageWidth="800" ImageHeight="1200" />""", """<Page Image="1" DoublePage="True" ImageSize="7" />""", """<Page Image="2" ImageSize="1003"></Page>""")),
            edit(four, intArrayOf(0, -1, 1, 2), 3, mapOf(0 to ComicInfoPages.Remade(400, resized = false), 2 to ComicInfoPages.Remade(7, resized = false)))
        )
    }

    @Test
    fun nothingToChangeGivesTheSameBytes() {
        val bytes = four.toByteArray()
        assertSame(bytes, ComicInfoPages.edit(bytes, intArrayOf(0, 1, 2, 3), 4, emptyMap()))
        // Pages made anew whose entries hold no size, and a book without a page list or count.
        val bare = xml(null, listOf("""<Page Image="0" />""", """<Page Image="1" Type="Story" />""")).toByteArray()
        assertSame(bare, ComicInfoPages.edit(bare, intArrayOf(0, 1), 2, mapOf(0 to ComicInfoPages.Remade(5, resized = true))))
        val empty = "<ComicInfo/>".toByteArray()
        assertSame(empty, ComicInfoPages.edit(empty, intArrayOf(-1, 0), 1, emptyMap()))
    }

    @Test
    fun withoutAPageListOnlyThePageCountChangesAndNoneIsAdded() {
        assertEquals(xml("3", null), edit(xml("4", null), intArrayOf(0, -1, 1, 2), 3))
        assertEquals(xml(null, listOf(p0, p2.replace("Image=\"2\"", "Image=\"1\""))), edit(xml(null, listOf(p0, p1, p2)), intArrayOf(0, -1, 1), 2))
    }

    @Test
    fun utf16WithItsByteOrderMarkStaysSo() {
        for ((bom, charset) in listOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE)) {
            val text = xml("4", listOf(p0, p1, p2, p3), nl = "\r\n", encoding = "utf-16", series = "Été 漫画")
            val out = ComicInfoPages.edit(bom + text.toByteArray(charset), intArrayOf(0, -1, 1, 2), 3, emptyMap())
            val expected = xml(
                "3", listOf(p0, """<Page Image="1" DoublePage="True" ImageSize="1002" />""", """<Page Image="2" ImageSize="1003"></Page>"""),
                nl = "\r\n", encoding = "utf-16", series = "Été 漫画"
            )
            assertArrayEquals(charset.name(), bom + expected.toByteArray(charset), out)
            assertEquals(3, ComicInfo.parse(out).pageCount)
        }
        // Without a mark, found by its "<?".
        val le = xml("4", listOf(p0, p1), encoding = "utf-16").toByteArray(Charsets.UTF_16LE)
        assertArrayEquals(xml("1", listOf(p0), encoding = "utf-16").toByteArray(Charsets.UTF_16LE), ComicInfoPages.edit(le, intArrayOf(0, -1), 1, emptyMap()))
    }

    @Test
    fun utf8WithItsByteOrderMarkAndOtherBytesStaysSo() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val text = xml("4", listOf(p0, p1, p2, p3), nl = "\r\n", series = "Été – 漫画")
        val out = ComicInfoPages.edit(bom + text.toByteArray(), intArrayOf(-1, 0, 1, 2), 3, emptyMap())
        val expected = xml(
            "3", listOf("""<Page Image="0" ImageSize="1001" />""", """<Page Image="1" DoublePage="True" ImageSize="1002" />""", """<Page Image="2" ImageSize="1003"></Page>"""),
            nl = "\r\n", series = "Été – 漫画"
        )
        assertArrayEquals(bom + expected.toByteArray(), out)
        // A single-byte encoding, a byte that is not valid UTF-8 kept as it is.
        val latin = xml("2", listOf(p0, p1), encoding = "ISO-8859-1", series = "Été").toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(xml("1", listOf(p0), encoding = "ISO-8859-1", series = "Été").toByteArray(Charsets.ISO_8859_1), ComicInfoPages.edit(latin, intArrayOf(0, -1), 1, emptyMap()))
    }

    @Test
    fun namesInAnyCaseOnOneLineCommentsLeftAlone() {
        val text = """<comicinfo><!-- <PageCount>3</PageCount> --><pagecount> 3 </pagecount><pages><page image="0"/><page  IMAGE = '1' imagesize="9" /><page image="2"/></pages></comicinfo>"""
        assertEquals(
            """<comicinfo><!-- <PageCount>3</PageCount> --><pagecount> 2 </pagecount><pages><page image="0"/><page image="1"/></pages></comicinfo>""",
            edit(text, intArrayOf(0, -1, 1), 2)
        )
    }

    @Test
    fun aDocumentTypeOfAnyLengthIsLeftAlone() {
        val doctype = "<!DOCTYPE ComicInfo [<!ENTITY a \"<Page Image='0'/>\">" + "<!-- x -->".repeat(5000) + "]>"
        val text = "$doctype<ComicInfo><Pages><Page Image=\"0\"/><Page Image=\"1\"/></Pages><![CDATA[<Page Image=\"1\"/>]]></ComicInfo>"
        assertEquals("$doctype<ComicInfo><Pages><Page Image=\"0\"/></Pages><![CDATA[<Page Image=\"1\"/>]]></ComicInfo>", edit(text, intArrayOf(-1, 0), 1))
    }

    @Test
    fun entriesForNoPageOfTheBookStayAsTheyAre() {
        val text = xml("2", listOf("""<Page Image="0" />""", """<Page Image="x" />""", """<Page Image="7" />""", """<Page Type="Story" />"""))
        assertEquals(xml("1", listOf("""<Page Image="x" />""", """<Page Image="7" />""", """<Page Type="Story" />""")), edit(text, intArrayOf(-1, 0), 1))
    }
}
