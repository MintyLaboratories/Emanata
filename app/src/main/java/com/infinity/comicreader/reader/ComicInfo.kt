package com.infinity.comicreader.reader

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
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
    val pages: List<Page> = emptyList()
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
                reader.parse(InputSource(StringReader(xml.trimStart('﻿'))))
            } catch (e: SAXException) {
                // Lenient: keep what came before the error.
            } catch (e: Exception) {
                // An unreadable document gives nothing more.
            }
            return handler.result()
        }

        /** [bytes] as UTF-8 (the encoding ComicInfo writers use), parsed by [parse]. */
        fun parse(bytes: ByteArray): ComicInfo = parse(String(bytes, Charsets.UTF_8))

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
                val name = (localName?.takeIf { it.isNotEmpty() } ?: qName ?: "").lowercase(Locale.ROOT)
                val value = text.toString().trim()
                if (value.isNotEmpty()) values.putIfAbsent(name, value)
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
            pages = pages
        )
    }
}
