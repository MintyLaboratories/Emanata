package com.infinity.comicreader.reader

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/**
 * The parts of a book's ComicInfo.xml the app uses. Element names are matched
 * without regard to case; empty or unparsable values count as absent.
 */
data class ComicInfo(
    val series: String? = null,
    val number: String? = null,
    /** 0 when absent or implausible. */
    val year: Int = 0,
    val publisher: String? = null,
    val manga: Manga = Manga.UNKNOWN,
    /** Pages as ComicInfo counts them; 0 when absent. */
    val pageCount: Int = 0,
    val pages: List<Page> = emptyList(),
    /** Every field of the root with a value, named as in the file, in file order. */
    val fields: List<Pair<String, String>> = emptyList()
) {
    /** The `<Manga>` value: whether the book is manga, and whether it reads right to left. */
    enum class Manga { UNKNOWN, NO, YES, YES_RTL }

    /** One `<Page>`: the [image] index it describes, its `Type` and whether it is a two-page spread. */
    data class Page(val image: Int, val type: String?, val doublePage: Boolean)

    companion object {
        const val FILE_NAME = "ComicInfo.xml"

        /** Largest ComicInfo.xml read; real ones are a few kilobytes. */
        const val MAX_BYTES = 256 * 1024

        private val PLAUSIBLE_YEARS = 1800..2200

        /** True for a path whose file name is ComicInfo.xml. */
        fun isComicInfo(path: String): Boolean = path.substringAfterLast('/').substringAfterLast('\\').equals(FILE_NAME, ignoreCase = true)

        /** [xml] read leniently: what parsed before an error is kept. */
        fun parse(xml: String): ComicInfo {
            val handler = Handler()
            try {
                val factory = SAXParserFactory.newInstance()
                // Never fetch anything a document points to.
                for (feature in listOf(
                    "http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities",
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd"
                )) runCatching { factory.setFeature(feature, false) }
                val reader = factory.newSAXParser().xmlReader
                reader.contentHandler = handler
                reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
                reader.parse(InputSource(StringReader(xml.trimStart('\uFEFF'))))
            } catch (e: SAXException) {
                // Lenient: keep what came before the error.
            } catch (e: Exception) {
                // An unreadable document gives nothing more.
            }
            return handler.result()
        }

        /** [bytes] in the encoding [charsetOf] finds, parsed by [parse]; bytes that do not decode become U+FFFD. */
        fun parse(bytes: ByteArray): ComicInfo = parse(String(bytes, charsetOf(bytes)))

        private val DECLARED = Regex("""^<\?xml[^>]*?\sencoding\s*=\s*["']([A-Za-z][A-Za-z0-9._:-]*)["']""")

        /**
         * The encoding of an XML document: its byte order mark, else UTF-16 by the
         * pattern of `<?`, else its declaration, else UTF-8 (what ComicInfo writers use).
         */
        internal fun charsetOf(bytes: ByteArray): Charset {
            fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Charsets.UTF_8
                at(0) == 0xFE && at(1) == 0xFF || at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16
                at(0) == 0x3C && at(1) == 0 && at(2) == 0x3F && at(3) == 0 -> Charsets.UTF_16LE
                at(0) == 0 && at(1) == 0x3C && at(2) == 0 && at(3) == 0x3F -> Charsets.UTF_16BE
                else -> {
                    val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
                    DECLARED.find(head)?.groupValues?.get(1)
                        ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() } ?: Charsets.UTF_8
                }
            }
        }

        /**
         * The ComicInfo.xml nearest the top of [reader]; null when there is none or it cannot
         * be read, or when it is not stored before entry index [before].
         */
        fun read(reader: ArchiveReader, before: Int = Int.MAX_VALUE): ComicInfo? {
            val entry = entryOf(reader) ?: return null
            if (entry.index >= before) return null
            return runCatching { reader.read(entry) { parse(readUpTo(it, MAX_BYTES)) } }.getOrNull()
        }

        /** The ComicInfo.xml entry [read] reads; null when there is none. */
        fun entryOf(reader: ArchiveReader): ArchiveEntry? = reader.entries.filter { isComicInfo(it.path) }
            .minByOrNull { e -> e.path.count { it == '/' || it == '\\' } }

        /** At most [max] bytes of [input]. */
        private fun readUpTo(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        internal fun mangaOf(value: String?): Manga = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "yesandrighttoleft" -> Manga.YES_RTL
            "yes" -> Manga.YES
            "no" -> Manga.NO
            else -> Manga.UNKNOWN
        }
    }

    private class Handler : DefaultHandler() {
        private val text = StringBuilder()
        private val values = HashMap<String, String>()
        private val fields = ArrayList<Pair<String, String>>()
        private val pages = ArrayList<Page>()
        private var depth = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            depth++
            text.setLength(0)
            val name = (localName?.takeIf { it.isNotEmpty() } ?: qName ?: "").lowercase(Locale.ROOT)
            if (name == "page") {
                fun attr(key: String): String? =
                    (0 until attributes.length).firstOrNull { attributes.getQName(it).equals(key, ignoreCase = true) || attributes.getLocalName(it).equals(key, ignoreCase = true) }
                        ?.let { attributes.getValue(it) }
                val image = attr("Image")?.trim()?.toIntOrNull() ?: return
                pages += Page(image, attr("Type")?.trim()?.takeIf { it.isNotEmpty() }, attr("DoublePage")?.trim().equals("true", ignoreCase = true))
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            // Only the root's own children are fields.
            if (depth == 2) {
                val named = localName?.takeIf { it.isNotEmpty() } ?: qName ?: ""
                val value = text.toString().trim()
                if (value.isNotEmpty() && values.putIfAbsent(named.lowercase(Locale.ROOT), value) == null) fields += named to value
            }
            text.setLength(0)
            depth--
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        fun result() = ComicInfo(
            series = values["series"],
            number = values["number"],
            year = values["year"]?.toIntOrNull()?.takeIf { it in PLAUSIBLE_YEARS } ?: 0,
            publisher = values["publisher"],
            manga = mangaOf(values["manga"]),
            pageCount = values["pagecount"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
            pages = pages,
            fields = fields
        )
    }
}
