package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archive file names read as UTF-8, a chosen charset, or the one Auto finds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NameCharsetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        NameCharset.overrides = emptyMap()
    }

    private val sjis = Charset.forName("Shift_JIS")

    /** Japanese page names, enough of them for a detector to tell their charset. */
    private val japanese = listOf(
        "表紙.jpg", "目次.jpg", "第一話/本文001.jpg", "第一話/本文002.jpg", "第一話/本文003.jpg",
        "第二話/本文004.jpg", "第二話/本文005.jpg", "あとがき.jpg", "奥付.jpg", "裏表紙.jpg"
    )

    /** A zip of [names] written in [charset]; java.util.zip marks names as UTF-8 only when that is it. */
    private fun zipBytes(names: List<String>, charset: Charset): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { zip ->
            for (n in names) {
                zip.putNextEntry(ZipEntry(n))
                zip.write(n.length)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tarBytes(names: List<String>, encoding: String): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out, encoding).use { tar ->
            for (n in names) {
                val e = TarArchiveEntry(n)
                e.size = 1
                tar.putArchiveEntry(e)
                tar.write(n.length)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun raw(text: String, charset: Charset = Charsets.UTF_8, utf8: Boolean = false) =
        NameCharset.Raw(text.toByteArray(charset), utf8)

    @Test
    fun aNamedSettingIsTakenAsIs() {
        assertEquals(Charset.forName("windows-1251"), NameCharset.resolve("windows-1251", { error("not asked") }))
        assertEquals(Charsets.UTF_8, NameCharset.resolve("no-such-charset", { error("not asked") }))
    }

    @Test
    fun charsetsWidenToWhatWindowsWrote() {
        assertEquals(Charset.forName("windows-31j"), NameCharset.charset("Shift_JIS"))
        assertEquals(Charset.forName("windows-1252"), NameCharset.charset("ISO-8859-1"))
        // The setting's Korean entry reads on any platform that knows Korean at all.
        assertTrue(NameCharset.charset("CP949")!!.newDecoder().maxCharsPerByte() > 0)
        assertNull(NameCharset.charset("no-such-charset"))
    }

    @Test
    fun autoReadsUtf8WhenEveryNameDecodes() {
        var asked = false
        val detector = NameCharset.Detector { asked = true; "Shift_JIS" }
        assertEquals(Charsets.UTF_8, NameCharset.resolve(NameCharset.AUTO, { listOf(raw("page.jpg"), raw("café/été.jpg")) }, detector))
        assertFalse(asked)
    }

    @Test
    fun autoIgnoresNamesMarkedUtf8() {
        val detector = NameCharset.Detector { error("not asked") }
        // Bytes that are not UTF-8, but the archive says they are: the reader's library decides.
        val names = listOf(raw("page.jpg"), NameCharset.Raw(byteArrayOf(0x81.toByte(), 0x40), utf8 = true))
        assertEquals(Charsets.UTF_8, NameCharset.resolve(NameCharset.AUTO, { names }, detector))
    }

    @Test
    fun autoAsksTheDetectorAboutTheOtherNamesOnly() {
        var text: ByteArray? = null
        val detector = NameCharset.Detector { text = it; "Shift_JIS" }
        val names = listOf(raw("plain.jpg"), raw("表紙.jpg", sjis), raw("目次.jpg", sjis))
        assertEquals(Charset.forName("windows-31j"), NameCharset.resolve(NameCharset.AUTO, { names }, detector))
        assertEquals("表紙.jpg\n目次.jpg\n", String(text!!, sjis))
    }

    @Test
    fun autoFallsBackToZipsOwnDefault() {
        val names = listOf(raw("café.jpg", Charset.forName("IBM437")))
        assertEquals(Charset.forName("IBM437"), NameCharset.resolve(NameCharset.AUTO, { names }, { null }))
    }

    private fun names(vararg names: String, charset: String) =
        names.joinToString("\n", postfix = "\n").toByteArray(Charset.forName(charset))

    @Test
    fun guessTellsScriptsApart() {
        assertEquals("Shift_JIS", NameCharset.guess(names(*japanese.toTypedArray(), charset = "Shift_JIS"), "en"))
        assertEquals("windows-1251", NameCharset.guess(names("Глава 01.jpg", "Обложка.jpg", "Глава 2/стр 3.jpg", charset = "windows-1251"), "en"))
        assertEquals("windows-1252", NameCharset.guess(names("Café.jpg", "Größe 2.jpg", "Été/page.jpg", charset = "windows-1252"), "en"))
        assertEquals("IBM437", NameCharset.guess(names("Café.jpg", "Été/page.jpg", charset = "IBM437"), "en"))
    }

    @Test
    fun guessLeansOnTheDevicesLanguageWhereBytesFitTwoScripts() {
        val korean = names("표지.jpg", "제1화/본문.jpg", "제2화/본문.jpg", "후기.jpg", charset = "x-windows-949")
        assertEquals("CP949", NameCharset.guess(korean, "ko"))
        val chinese = names("封面.jpg", "第一话/正文.jpg", "第二话/正文.jpg", "后记.jpg", charset = "GBK")
        assertEquals("GBK", NameCharset.guess(chinese, "zh"))
        assertEquals("GBK", NameCharset.guess(chinese, "en"))
    }

    @Test
    fun guessFindsNothingInWhatNoCharsetReads() {
        assertNull(NameCharset.guess(byteArrayOf(0x01, 0x02), "en"))
    }

    @Test
    fun autoFindsJapanese() {
        val names = japanese.map { raw(it, sjis) }
        assertEquals(Charset.forName("windows-31j"), NameCharset.resolve(NameCharset.AUTO, { names }))
    }

    @Test
    fun zipNamesAreReadAsStored() {
        val names = ZipNames.read(SeekableInMemoryByteChannel(zipBytes(listOf("a.jpg", "表紙.jpg"), sjis)))
        assertEquals(listOf("a.jpg", "表紙.jpg"), names.map { String(it.bytes, sjis) })
        assertTrue(names.none { it.utf8 })
        val marked = ZipNames.read(SeekableInMemoryByteChannel(zipBytes(listOf("表紙.jpg"), Charsets.UTF_8)))
        assertTrue(marked.single().utf8)
        assertEquals("表紙.jpg", String(marked.single().bytes, Charsets.UTF_8))
    }

    @Test
    fun zipNamesOfSomethingElseAreNone() {
        assertTrue(ZipNames.read(SeekableInMemoryByteChannel(ByteArray(10))).isEmpty())
        assertTrue(ZipNames.read(SeekableInMemoryByteChannel(ByteArray(4096))).isEmpty())
    }

    @Test
    fun localZipReadsAutoNames() {
        val file = File(tmp.root, "book.cbz").apply { writeBytes(zipBytes(japanese, sjis)) }
        ZipReader(file, NameCharset.AUTO).use { reader ->
            assertEquals(japanese, reader.entries.map { it.path })
            assertEquals(Charset.forName("windows-31j"), reader.nameCharset)
        }
    }

    @Test
    fun localZipOfUtf8NamesStaysUtf8() {
        val file = File(tmp.root, "book.cbz").apply { writeBytes(zipBytes(japanese, Charsets.UTF_8)) }
        ZipReader(file, NameCharset.AUTO).use { assertEquals(japanese, it.entries.map { e -> e.path }) }
    }

    @Test
    fun zipInPlaceReadsAutoNames() {
        ChannelZipReader(SeekableInMemoryByteChannel(zipBytes(japanese, sjis)), NameCharset.AUTO).use { reader ->
            assertEquals(japanese, reader.entries.map { it.path })
            assertEquals(Charset.forName("windows-31j"), reader.nameCharset)
            // Entries still read by their place, not their names.
            assertEquals(japanese[3].length, reader.read(reader.entries[3]) { it.read() })
        }
        ChannelZipReader(SeekableInMemoryByteChannel(zipBytes(japanese, Charsets.UTF_8)), NameCharset.AUTO).use { reader ->
            assertEquals(japanese, reader.entries.map { it.path })
            assertEquals(Charsets.UTF_8, reader.nameCharset)
        }
    }

    @Test
    fun zipInPlaceTakesAChosenCharset() {
        ChannelZipReader(SeekableInMemoryByteChannel(zipBytes(japanese, sjis)), "Shift_JIS").use { reader ->
            assertEquals(japanese, reader.entries.map { it.path })
        }
    }

    @Test
    fun tarReadsAutoNames() {
        val file = File(tmp.root, "book.cbt").apply { writeBytes(tarBytes(japanese, "Shift_JIS")) }
        TarReader(file, NameCharset.AUTO).use { reader ->
            assertEquals(japanese, reader.entries.map { it.path })
            assertEquals(Charset.forName("windows-31j"), reader.nameCharset)
        }
        val utf8 = File(tmp.root, "utf8.cbt").apply { writeBytes(tarBytes(japanese, "UTF-8")) }
        TarReader(utf8, NameCharset.AUTO).use { assertEquals(japanese, it.entries.map { e -> e.path }) }
        TarReader(file, "Shift_JIS").use { assertEquals(japanese, it.entries.map { e -> e.path }) }
    }

    @Test
    fun latin1NamesBeyondLatin1StayAsGiven() {
        // A name with a character past U+00FF came as Unicode (a PAX header), not as bytes.
        val (names, charset) = NameCharset.fromLatin1(listOf("表紙.jpg", "plain.jpg"), { error("not asked") })
        assertEquals(listOf("表紙.jpg", "plain.jpg"), names)
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun aBooksOwnChoiceWins() {
        NameCharset.overrides = mapOf("/books/a.cbz" to "Shift_JIS")
        assertEquals("Shift_JIS", NameCharset.forBook("/books/a.cbz", NameCharset.AUTO))
        assertEquals(NameCharset.AUTO, NameCharset.forBook("/books/b.cbz", NameCharset.AUTO))
    }

    @Test
    fun pageSourcesReadABookWithItsOwnChoice() {
        val file = File(tmp.root, "book.cbz").apply { writeBytes(zipBytes(listOf("b.jpg", "a.jpg"), sjis)) }
        NameCharset.overrides = mapOf(file.absolutePath to "windows-1251")
        PageSources.open(file, NameCharset.AUTO, tmp.newFolder("cache")).use {
            assertEquals(Charset.forName("windows-1251"), it.nameCharset)
        }
    }
}
