package com.infinity.comicreader.net

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory

/** The WebDAV XML the app sends and reads: PROPFIND bodies and their multi-status answers. */
object DavXml {
    /** Asks for what a listing or a stat needs. */
    const val PROPS = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:creationdate/></d:prop></d:propfind>"""

    /** Asks for the space left and used (RFC 4331). */
    const val QUOTA = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:quota-available-bytes/><d:quota-used-bytes/></d:prop></d:propfind>"""

    /**
     * One resource of a multi-status answer: its [href] as sent, and what its
     * successful property groups told; sizes are -1 and times 0 when not told.
     */
    data class Entry(
        val href: String,
        val isCollection: Boolean,
        val size: Long,
        val modified: Long,
        val created: Long,
        val quotaAvailable: Long = -1,
        val quotaUsed: Long = -1
    )

    /** The resources a multi-status document lists, leaving out ones answered with an error status. */
    fun parse(input: InputStream): List<Entry> {
        val handler = Handler()
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // Never fetch anything a document points to.
        for (feature in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        )) runCatching { factory.setFeature(feature, false) }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.parse(InputSource(input))
        return handler.entries
    }

    /** Milliseconds since the epoch of an HTTP date (`getlastmodified`) or an ISO 8601 one (`creationdate`); 0 when neither. */
    fun millis(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
    }

    /** True for an HTTP status line saying 2xx, or none at all. */
    private fun ok(status: String?): Boolean {
        if (status == null) return true
        val code = status.trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return true
        return code in 200..299
    }

    private class Handler : DefaultHandler() {
        val entries = ArrayList<Entry>()

        private val text = StringBuilder()

        // The response being read.
        private var href: String? = null
        private var responseStatus: String? = null
        private var collection = false
        private var size = -1L
        private var modified = 0L
        private var created = 0L
        private var quotaAvailable = -1L
        private var quotaUsed = -1L

        // The property group being read, kept apart until its status says it succeeded.
        private var inPropstat = false
        private var groupStatus: String? = null
        private var groupCollection = false
        private var groupSize = -1L
        private var groupModified = 0L
        private var groupCreated = 0L
        private var groupQuotaAvailable = -1L
        private var groupQuotaUsed = -1L

        private fun isDav(uri: String?) = uri.isNullOrEmpty() || uri == "DAV:"

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            text.setLength(0)
            if (!isDav(uri)) return
            when (localName) {
                "response" -> {
                    href = null
                    responseStatus = null
                    collection = false
                    size = -1
                    modified = 0
                    created = 0
                    quotaAvailable = -1
                    quotaUsed = -1
                }
                "propstat" -> {
                    inPropstat = true
                    groupStatus = null
                    groupCollection = false
                    groupSize = -1
                    groupModified = 0
                    groupCreated = 0
                    groupQuotaAvailable = -1
                    groupQuotaUsed = -1
                }
                "collection" -> if (inPropstat) groupCollection = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (!isDav(uri)) return
            val value = text.toString().trim()
            when (localName) {
                "href" -> if (!inPropstat && href == null) href = value
                "status" -> if (inPropstat) groupStatus = value else responseStatus = value
                "getcontentlength" -> if (inPropstat) groupSize = value.toLongOrNull() ?: -1
                "getlastmodified" -> if (inPropstat) groupModified = millis(value)
                "creationdate" -> if (inPropstat) groupCreated = millis(value)
                "quota-available-bytes" -> if (inPropstat) groupQuotaAvailable = value.toLongOrNull() ?: -1
                "quota-used-bytes" -> if (inPropstat) groupQuotaUsed = value.toLongOrNull() ?: -1
                "propstat" -> {
                    inPropstat = false
                    if (ok(groupStatus)) {
                        collection = collection || groupCollection
                        if (groupSize >= 0) size = groupSize
                        if (groupModified > 0) modified = groupModified
                        if (groupCreated > 0) created = groupCreated
                        if (groupQuotaAvailable >= 0) quotaAvailable = groupQuotaAvailable
                        if (groupQuotaUsed >= 0) quotaUsed = groupQuotaUsed
                    }
                }
                "response" -> {
                    val h = href
                    if (h != null && ok(responseStatus)) {
                        entries += Entry(h, collection, size, modified, created, quotaAvailable, quotaUsed)
                    }
                }
            }
            text.setLength(0)
        }
    }
}
