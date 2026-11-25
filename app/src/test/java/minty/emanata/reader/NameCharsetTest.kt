package minty.emanata.reader

import minty.emanata.testing.bookId
import minty.emanata.data.BookId
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
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

    /**
     * A zip of [names] stored in [encoding], each also in a Unicode Path extra field, as
     * Info-ZIP writes them, unless [unicodeFields] is false; [zip64] adds ZIP64 end records.
     */
    private fun unicodeZipBytes(names: List<String>, encoding: String, unicodeFields: Boolean = true, zip64: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipArchiveOutputStream(out).use { zip ->
            zip.setEncoding(encoding)
            zip.setUseLanguageEncodingFlag(false)
            zip.setFallbackToUTF8(false)
            zip.setCreateUnicodeExtraFields(
                if (unicodeFields) ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS else ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER
            )
            if (zip64) zip.setUseZip64(Zip64Mode.Always)
            for (n in names) {
                zip.putArchiveEntry(ZipArchiveEntry(n))
                zip.write(n.length)
                zip.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun zipNamesTakeUnicodePathFields() {
        val names = ZipNames.read(SeekableInMemoryByteChannel(unicodeZipBytes(japanese, "Shift_JIS")))
        assertEquals(japanese, names.map { it.unicode })
        assertTrue(names.all { it.utf8 })
        // Stored as is beside them.
        assertEquals(japanese, names.map { String(it.bytes, sjis) })
        val plain = ZipNames.read(SeekableInMemoryByteChannel(unicodeZipBytes(japanese, "Shift_JIS", unicodeFields = false)))
        assertTrue(plain.none { it.utf8 || it.unicode != null })
    }

    @Test
    fun zipNamesSkipUnicodeFieldsMadeForOtherBytes() {
        val bytes = unicodeZipBytes(listOf("表紙.jpg"), "Shift_JIS")
        // The field's CRC-32 of the name it was made for, in the central directory: changed, the name was too.
        val dir = (bytes.size - 22 downTo 0).first { at -> bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4b.toByte() && bytes[at + 2] == 1.toByte() && bytes[at + 3] == 2.toByte() }
        val nameLength = (bytes[dir + 28].toInt() and 0xFF) or ((bytes[dir + 29].toInt() and 0xFF) shl 8)
        val crcAt = dir + 46 + nameLength + 4 + 1
        bytes[crcAt] = (bytes[crcAt] + 1).toByte()
        val name = ZipNames.read(SeekableInMemoryByteChannel(bytes)).single()
        assertNull(name.unicode)
        assertFalse(name.utf8)
    }

    @Test
    fun zipNamesReadZip64EndRecords() {
        val bytes = unicodeZipBytes(listOf("a.jpg", "b.jpg"), "UTF-8", unicodeFields = false, zip64 = true)
        // The plain end record defers to the ZIP64 one, as in a zip too large for it.
        val end = bytes.size - 22
        for (i in 8 until 20) bytes[end + i] = 0xFF.toByte()
        assertEquals(listOf("a.jpg", "b.jpg"), ZipNames.read(SeekableInMemoryByteChannel(bytes)).map { String(it.bytes) })
    }

    @Test
    fun localAndInPlaceZipsReadUnicodePathFieldsAlike() {
        // Names Info-ZIP could not store in IBM437 are stored as "?" beside their Unicode ones.
        for (encoding in listOf("Shift_JIS", "IBM437")) {
            val bytes = unicodeZipBytes(japanese, encoding)
            val file = File(tmp.root, "book-$encoding.cbz").apply { writeBytes(bytes) }
            for (setting in listOf(NameCharset.AUTO, "windows-1251")) {
                ZipReader(file, setting).use { reader ->
                    assertEquals(japanese, reader.entries.map { it.path })
                    assertEquals(japanese[3].length, reader.read(reader.entries[3]) { it.read() })
                }
                ChannelZipReader(SeekableInMemoryByteChannel(bytes), setting).use { reader ->
                    assertEquals(japanese, reader.entries.map { it.path })
                }
            }
        }
    }

    @Test
    fun zipsOpenInACharsetThatMapsNotEveryByte() {
        // Windows-1251 leaves byte 0x98 unmapped; the names read wrongly, but the book opens.
        val bytes = zipBytes(listOf("表紙.jpg", "第一話.jpg"), sjis)
        val file = File(tmp.root, "book.cbz").apply { writeBytes(bytes) }
        ZipReader(file, "windows-1251").use { reader ->
            assertEquals(2, reader.entries.size)
            assertEquals("表紙.jpg".length, reader.read(reader.entries[0]) { it.read() })
        }
        ChannelZipReader(SeekableInMemoryByteChannel(bytes), "windows-1251").use { assertEquals(2, it.entries.size) }
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
        NameCharset.overrides = mapOf(BookId(1) to "Shift_JIS")
        assertEquals("Shift_JIS", NameCharset.forBook(BookId(1), NameCharset.AUTO))
        assertEquals(NameCharset.AUTO, NameCharset.forBook(BookId(2), NameCharset.AUTO))
        assertEquals(NameCharset.AUTO, NameCharset.forBook(null as BookId?, NameCharset.AUTO))
    }

    @Test
    fun pageSourcesReadABookWithItsOwnChoice() {
        val file = File(tmp.root, "book.cbz").apply { writeBytes(zipBytes(listOf("b.jpg", "a.jpg"), sjis)) }
        NameCharset.overrides = mapOf(bookId(file) to "windows-1251")
        PageSources.open(file, NameCharset.AUTO, tmp.newFolder("cache")).use {
            assertEquals(Charset.forName("windows-1251"), it.nameCharset)
        }
    }
}
