package com.infinity.comicreader.net

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A WebDAV server on this machine serving local folder [dir] under [prefix], for
 * tests: PROPFIND (depth 0 and 1) and GET, with Range unless [ranges] is off, and
 * Basic sign-in when [password] is set. It records each request and the file bytes it sends.
 */
class FakeDavServer(private val dir: File, private val prefix: String = "/dav") : Closeable {
    private val server = MockWebServer()

    @Volatile
    var ranges = true

    /** Sends file bodies at [SLOW_BYTES_PER_SECOND] while on. */
    @Volatile
    var slow = false

    @Volatile
    var user = ""

    @Volatile
    var password = ""

    /** Free and used bytes the server tells; null to tell none. */
    @Volatile
    var quota: Pair<Long, Long>? = 3_000_000_000L to 5_000_000_000L

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var bytesSent = 0L
        private set

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    /** The share's address on this server, at folder [path] below [prefix]. */
    fun url(path: String = ""): DavUrl = DavUrl(false, "localhost", server.port, (prefix.trim('/') + "/" + path).trim('/'))

    fun share(id: String = "d1", name: String = "Share D", user: String = this.user, password: String = this.password) =
        Share(id, name, url(), user, password)

    /** The requests made with [method], by path. */
    fun paths(method: String): List<String> = requests.filter { it.method == method }.map { it.path!! }

    override fun close() = server.shutdown()

    private fun answer(request: RecordedRequest): MockResponse {
        if (password.isNotEmpty() && request.getHeader("Authorization") != okhttp3.Credentials.basic(user, password)) {
            return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"test\"")
        }
        val raw = request.requestUrl!!.encodedPath
        if (!raw.startsWith(prefix)) return MockResponse().setResponseCode(404)
        val rel = raw.removePrefix(prefix).split('/').filter { it.isNotEmpty() }.joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val file = if (rel.isEmpty()) dir else File(dir, rel)
        return when (request.method) {
            "PROPFIND" -> propfind(request, file, raw)
            "GET" -> get(request, file)
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(request: RecordedRequest, file: File, raw: String): MockResponse {
        if (!file.exists()) return MockResponse().setResponseCode(404)
        val body = request.body.readUtf8()
        val xml = StringBuilder("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        val self = if (file.isDirectory) raw.trimEnd('/') + "/" else raw
        xml.append(entry(self, file, body))
        if (request.getHeader("Depth") == "1" && file.isDirectory) {
            for (child in file.listFiles()!!.sortedBy { it.name }) {
                val href = self + encode(child.name) + if (child.isDirectory) "/" else ""
                xml.append(entry(href, child, body))
            }
        }
        xml.append("</D:multistatus>")
        return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(xml.toString())
    }

    private fun entry(href: String, file: File, asked: String): String {
        val props = StringBuilder()
        if ("quota-available-bytes" in asked) {
            val q = quota ?: return "<D:response><D:href>$href</D:href><D:propstat><D:prop><D:quota-available-bytes/><D:quota-used-bytes/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>"
            props.append("<D:quota-available-bytes>${q.first}</D:quota-available-bytes><D:quota-used-bytes>${q.second}</D:quota-used-bytes>")
        } else {
            props.append(if (file.isDirectory) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>")
            if (file.isFile) props.append("<D:getcontentlength>${file.length()}</D:getcontentlength>")
            props.append("<D:getlastmodified>${HTTP_DATE.format(Instant.ofEpochMilli(file.lastModified()))}</D:getlastmodified>")
        }
        return "<D:response><D:href>$href</D:href><D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    private fun get(request: RecordedRequest, file: File): MockResponse {
        if (!file.isFile) return MockResponse().setResponseCode(404)
        val length = file.length()
        val range = request.getHeader("Range")?.takeIf { ranges }?.removePrefix("bytes=")?.split('-')
        if (range == null) return paced(MockResponse().setResponseCode(200).setBody(slice(file, 0, length)))
        val from = range[0].toLong()
        if (from >= length) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */$length")
        val to = minOf(range[1].toLongOrNull() ?: (length - 1), length - 1)
        return paced(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $from-$to/$length")
                .setBody(slice(file, from, to - from + 1))
        )
    }

    private fun paced(response: MockResponse): MockResponse =
        if (slow) response.throttleBody(SLOW_BYTES_PER_SECOND, 1, TimeUnit.SECONDS) else response

    private fun slice(file: File, from: Long, count: Long): Buffer {
        val bytes = ByteArray(count.toInt())
        RandomAccessFile(file, "r").use {
            it.seek(from)
            it.readFully(bytes)
        }
        bytesSent += count
        return Buffer().write(bytes)
    }

    companion object {
        private val HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        const val SLOW_BYTES_PER_SECOND = 16L * 1024

        fun encode(name: String): String = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }
}
