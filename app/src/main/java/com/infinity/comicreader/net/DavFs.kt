package com.infinity.comicreader.net

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A [Share] over WebDAV: PROPFIND lists folders and states files, and files are read
 * with HTTP Range requests, so a zip is read in place; reads that follow on from each
 * other fetch further ahead each time, up to [MAX_AHEAD_BYTES]. A server that ignores
 * Range sends whole files: they are then read front to back only, and [seekable] says
 * so, for books to be fetched whole instead. Basic sign-in goes with each request when
 * a user or password is set; one turned down (401) is not tried again until the user
 * retries ([Remotes] remembers it). HTTPS, or plain HTTP only to this device
 * ([DavUrl]); redirects are followed, never between the two, and the sign-in never
 * goes to another host. Idle connections close after [IDLE_MS]. Closing the share or
 * a file cancels its requests, bodies still coming included, and never waits.
 */
class DavFs(private val share: Share, private val client: OkHttpClient = sharedClient) : RemoteFs {
    private val where = share.path as? DavUrl ?: throw IllegalArgumentException("Not a WebDAV share: $share")

    private val auth: String? =
        if (share.user.isEmpty() && share.password.isEmpty()) null else Credentials.basic(share.user, share.password, Charsets.UTF_8)

    /** Set under [calls]' lock, so no request starts once it is. */
    @Volatile
    private var closed = false

    /** Requests whose answers are not read through yet, and files open: canceled and closed with the share. */
    private val calls = HashSet<Call>()
    private val inputs = HashSet<DavInput>()

    /** Whether the server honors Range requests; null until a file larger than [HEAD_BYTES] was opened. */
    @Volatile
    private var ranges: Boolean? = null

    override fun list(path: String): List<RemoteStat> {
        val url = urlOf(path, folder = true)
        val entries = propfind(url, depth = 1, body = DavXml.PROPS, path = path)
        val base = segmentsOf(url)
        val self = entries.indexOfFirst { hrefSegments(it.href) == base }.takeIf { it >= 0 } ?: 0
        return entries.withIndex().mapNotNull { (i, e) ->
            if (i == self) return@mapNotNull null
            val name = hrefSegments(e.href).lastOrNull() ?: return@mapNotNull null
            statOf(name, e)
        }
    }

    override fun stat(path: String): RemoteStat? = try {
        propfind(urlOf(path, folder = false), depth = 0, body = DavXml.PROPS, path = path)
            .firstOrNull()?.let { statOf(path.substringAfterLast('/'), it) }
    } catch (e: RemoteException) {
        if (e.reason != RemoteException.Reason.MISSING) throw e
        null
    }

    override fun open(path: String): RemoteInput = DavInput(path).also { input ->
        synchronized(inputs) {
            if (closed) {
                input.close()
                throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
            }
            inputs += input
        }
    }

    override fun seekable(path: String): Boolean = ranges ?: open(path).use { ranges ?: true }

    /** Free and total bytes as the server's quota properties tell; null when it does not. */
    override fun space(): Pair<Long, Long>? {
        val e = propfind(urlOf("", folder = true), depth = 0, body = DavXml.QUOTA, path = "").firstOrNull() ?: return null
        if (e.quotaAvailable < 0 || e.quotaUsed < 0) return null
        val total = e.quotaAvailable + e.quotaUsed
        return if (total <= 0) null else e.quotaAvailable to total
    }

    /** Cancels requests under way, then closes open files; later calls fail. */
    override fun close() {
        val going = synchronized(calls) {
            closed = true
            calls.toList()
        }
        for (call in going) call.cancel()
        val open = synchronized(inputs) { inputs.toList().also { inputs.clear() } }
        for (input in open) runCatching { input.close() }
    }

    private fun statOf(name: String, e: DavXml.Entry) =
        RemoteStat(name, e.isCollection, if (e.isCollection) 0 else e.size.coerceAtLeast(0), e.modified, e.created)

    private fun propfind(url: HttpUrl, depth: Int, body: String, path: String): List<DavXml.Entry> {
        val request = request(url)
            .method("PROPFIND", body.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        execute(request).use { answer ->
            val response = answer.response
            if (response.code != 207 && response.code != 200) throw failure(response, top = path.isEmpty())
            val source = response.body?.byteStream() ?: throw IOException("Empty answer")
            return try {
                DavXml.parse(source)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Unreadable answer from ${share.name}", e)
            }
        }
    }

    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    /** A [response] whose [call] the share can cancel until the answer is closed. */
    private inner class Answer(val call: Call, val response: Response) : Closeable {
        override fun close() {
            response.close()
            synchronized(calls) { calls -= call }
        }
    }

    /**
     * Runs [request], canceled when the share closes, its body included; [started]
     * hears the call first. A transport failure reads as unreachable.
     */
    private fun execute(request: Request, started: (Call) -> Unit = {}): Answer {
        val call = client.newCall(request)
        synchronized(calls) {
            if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
            calls += call
        }
        var answered = false
        try {
            started(call)
            return Answer(call, call.execute()).also { answered = true }
        } catch (e: IOException) {
            if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } finally {
            if (!answered) synchronized(calls) { calls -= call }
        }
    }

    /** What an error [response] means; a missing [top] folder is a missing share. */
    private fun failure(response: Response, top: Boolean = false): IOException {
        val message = "HTTP ${response.code}"
        return when (response.code) {
            401 -> RemoteException(RemoteException.Reason.DENIED, message, lasting = true)
            403 -> RemoteException(RemoteException.Reason.DENIED, message)
            404, 410 -> RemoteException(if (top) RemoteException.Reason.NO_SHARE else RemoteException.Reason.MISSING, message)
            // No WebDAV there.
            405, 501 -> RemoteException(RemoteException.Reason.NO_SHARE, message)
            408, 429, in 500..599 -> RemoteException(RemoteException.Reason.UNREACHABLE, message)
            else -> IOException(message)
        }
    }

    /** The URL of [path] below the share's folder; a folder's ends with `/`. */
    private fun urlOf(path: String, folder: Boolean): HttpUrl {
        val b = HttpUrl.Builder().scheme(if (where.secure) "https" else "http").host(where.host).port(where.port)
        for (name in (where.dir.split('/') + path.split('/')).filter { it.isNotEmpty() }) b.addPathSegment(name)
        if (folder) b.addPathSegment("")
        return b.build()
    }

    private fun segmentsOf(url: HttpUrl): List<String> = url.pathSegments.filter { it.isNotEmpty() }

    /** The decoded names along an href, which may be a full URL or an absolute path. */
    private fun hrefSegments(href: String): List<String> {
        val path = if ("://" in href) href.substringAfter("://").let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } } else href
        return path.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }.map { DavUrl.decode(it) ?: it }
    }

    /**
     * A file open for reading. Opening asks for its first [HEAD_BYTES], which tells its
     * length and whether the server honors Range; with ranges, each read fetches what is
     * asked, or further ahead when it follows on from the last; without, the whole
     * file streams from its start, skipped forward to each read and started again for
     * a read behind it. Reads take turns; [close] cancels the one under way, never waiting.
     */
    private inner class DavInput(private val path: String) : RemoteInput {
        private val url = urlOf(path, folder = false)
        override val length: Long

        private val lock = ReentrantLock()

        @Volatile
        private var shut = false

        /** The request a read waits on now, or the stream's; canceled by [close]. */
        @Volatile
        private var going: Call? = null

        /** The file's first bytes, fetched on opening. */
        private var head = ByteArray(0)

        /** The bytes a ranged read fetched last, from [runStart]. */
        private var run = ByteArray(0)
        private var runStart = 0L

        /** Where the last read ended, and how far the last fetch reached ahead. */
        private var lastEnd = 0L
        private var ahead = 0

        /** Without ranges: the whole file streaming, at [streamAt]. */
        @Volatile
        private var stream: Answer? = null
        private var streamAt = 0L
        private val rangeless: Boolean

        init {
            val answer = get(0, HEAD_BYTES - 1L)
            val response = answer.response
            var keep = false
            try {
                when (response.code) {
                    206 -> {
                        val range = contentRange(response)
                        if (range == null || range.first != 0L || range.second >= HEAD_BYTES) throw IOException("Unasked range from ${share.name}")
                        length = if (range.third >= 0) range.third else knownLength()
                        head = readBody(response, (range.second + 1).toInt())
                        ranges = true
                        rangeless = false
                    }
                    200 -> {
                        length = response.body?.contentLength()?.takeIf { it >= 0 } ?: knownLength()
                        if (length <= HEAD_BYTES) {
                            // A small file may come whole either way: it tells nothing of ranges, and it is all here.
                            head = readBody(response, length.toInt())
                            rangeless = false
                        } else {
                            ranges = false
                            rangeless = true
                            stream = answer
                            keep = true
                        }
                    }
                    416 -> {
                        length = contentRange(response)?.third?.takeIf { it >= 0 } ?: knownLength()
                        rangeless = false
                    }
                    else -> throw failure(response)
                }
            } finally {
                if (!keep) answer.close()
            }
        }

        private fun knownLength(): Long = stat(path)?.size ?: throw RemoteException(RemoteException.Reason.MISSING, path)

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            lock.lock()
            try {
                if (shut) throw closedFailure()
                if (position >= length || count <= 0) return if (count <= 0) 0 else -1
                val wanted = minOf(count.toLong(), length - position).toInt()
                val n = copy(head, 0, position, buffer, offset, wanted).takeIf { it > 0 }
                    ?: copy(run, runStart, position, buffer, offset, wanted).takeIf { it > 0 }
                    ?: if (rangeless) streamed(position, buffer, offset, wanted) else ranged(position, buffer, offset, wanted)
                if (n > 0) lastEnd = position + n
                return n
            } finally {
                if (shut) release()
                lock.unlock()
            }
        }

        /** What reading a closed file throws: the share's closing, or this file's. */
        private fun closedFailure(): IOException =
            if (closed) RemoteException(RemoteException.Reason.UNKNOWN_SHARE) else IOException("Closed: $path")

        /** Copies what [bytes], from [start] in the file, hold at [position]; 0 when they hold nothing there. */
        private fun copy(bytes: ByteArray, start: Long, position: Long, out: ByteArray, offset: Int, count: Int): Int {
            val at = position - start
            if (at < 0 || at >= bytes.size) return 0
            val n = minOf(count.toLong(), bytes.size - at).toInt()
            System.arraycopy(bytes, at.toInt(), out, offset, n)
            return n
        }

        private fun ranged(position: Long, out: ByteArray, offset: Int, count: Int): Int {
            ahead = if (position == lastEnd) minOf(maxOf(ahead * 2, count), MAX_AHEAD_BYTES) else count
            val end = minOf(length, position + maxOf(ahead, count)) - 1
            try {
                get(position, end).use { answer ->
                    val response = answer.response
                    if (response.code != 206) throw failure(response)
                    val range = contentRange(response)
                    if (range == null || range.first != position || range.second > end) throw IOException("Unasked range from ${share.name}")
                    run = readBody(response, (range.second - range.first + 1).toInt())
                    runStart = position
                }
            } finally {
                going = null
            }
            return copy(run, runStart, position, out, offset, count).takeIf { it > 0 } ?: -1
        }

        private fun streamed(position: Long, out: ByteArray, offset: Int, count: Int): Int {
            if (stream == null || position < streamAt) {
                stream?.close()
                stream = null
                val answer = get(0, -1)
                if (answer.response.code != 200) {
                    answer.close()
                    throw failure(answer.response)
                }
                stream = answer
                streamAt = 0
            }
            val source: BufferedSource = stream!!.response.body?.source() ?: throw IOException("Empty answer")
            try {
                if (position > streamAt) {
                    source.skip(position - streamAt)
                    streamAt = position
                }
                val n = source.read(out, offset, count)
                if (n > 0) streamAt += n
                return n
            } catch (e: IOException) {
                stream?.close()
                stream = null
                if (shut || closed) throw closedFailure()
                throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
            }
        }

        /** GET of bytes [from] to [to] (inclusive); the whole file when [to] is negative. */
        private fun get(from: Long, to: Long): Answer {
            val r = request(url).get().header("Accept-Encoding", "identity")
            if (to >= 0) r.header("Range", "bytes=$from-$to")
            try {
                return execute(r.build()) { call ->
                    going = call
                    if (shut) call.cancel()
                }
            } catch (e: IOException) {
                if (shut) throw closedFailure()
                throw e
            }
        }

        /** Exactly [size] bytes of [response]'s body. */
        private fun readBody(response: Response, size: Int): ByteArray {
            val source = response.body?.source() ?: throw IOException("Empty answer")
            return try {
                source.readByteArray(size.toLong())
            } catch (e: IOException) {
                if (shut || closed) throw closedFailure()
                throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
            }
        }

        /** Cancels the request under way; what the file holds goes now, or as the read under way ends. */
        override fun close() {
            shut = true
            going?.cancel()
            stream?.call?.cancel()
            synchronized(inputs) { inputs -= this }
            if (lock.tryLock()) {
                try {
                    release()
                } finally {
                    lock.unlock()
                }
            }
        }

        private fun release() {
            stream?.close()
            stream = null
            head = ByteArray(0)
            run = ByteArray(0)
        }
    }

    companion object {
        /** What opening a file fetches at once: its length and the server's Range support come with it. */
        const val HEAD_BYTES = 64 * 1024

        /** The most a read fetches ahead of what was asked. */
        const val MAX_AHEAD_BYTES = 4 shl 20

        /** How long a connection stays open unused. */
        const val IDLE_MS = 60_000L

        private val XML = "application/xml; charset=utf-8".toMediaType()

        /** First, last and total bytes of a `Content-Range: bytes a-b/total` (total -1 when `*`), or of `bytes *\/total` (first and last -1). */
        internal fun contentRange(response: Response): Triple<Long, Long, Long>? = parseContentRange(response.header("Content-Range"))

        internal fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
            val v = value?.trim() ?: return null
            if (!v.startsWith("bytes ", ignoreCase = true)) return null
            val spec = v.substring(6).trim()
            val total = spec.substringAfter('/', "").trim().let { if (it == "*") -1L else it.toLongOrNull() ?: return null }
            val span = spec.substringBefore('/').trim()
            if (span == "*") return Triple(-1L, -1L, total)
            val first = span.substringBefore('-').toLongOrNull() ?: return null
            val last = span.substringAfter('-', "").toLongOrNull() ?: return null
            if (first < 0 || last < first || (total >= 0 && last >= total)) return null
            return Triple(first, last, total)
        }

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                // Never from HTTPS to plain HTTP.
                .followSslRedirects(false)
                .connectionPool(ConnectionPool(4, IDLE_MS, TimeUnit.MILLISECONDS))
                .build()
        }
    }
}
