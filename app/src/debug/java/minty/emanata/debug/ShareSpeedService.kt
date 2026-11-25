package minty.emanata.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2ReadRequest
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.connection.SequenceWindow
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import minty.emanata.R
import minty.emanata.files.ShareReads
import minty.emanata.net.RemoteChannel
import minty.emanata.net.Remotes
import minty.emanata.net.SharePath
import minty.emanata.net.SmbFs
import minty.emanata.reader.ArchiveReader
import minty.emanata.reader.NameCharset
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread
import com.hierynomus.smbj.share.File as SmbFile

/**
 * Debug builds only: measures how fast a large book on an SMB share reads, read-only,
 * through each way of reading it, and logs the link's terms (dialect, largest read,
 * credits, signing, encryption, socket buffers) under "ShareSpeed", ending with "done".
 * Runs in a process of its own, kept in the foreground. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/minty.emanata.debug.ShareSpeedService --es share first [--el bytes <n>] [--ei rounds <n>] [--es only <kinds>]`
 *
 * or `--es path <path>`. The first `bytes` (32 MB by default) are read once to wake the
 * server's disk and fill its cache, then through every way `rounds` times (2), in turn:
 * - `app`: the app's own reads ([Remotes.open]) through a [RemoteChannel] as the share provider
 *   makes one, in 128 KB reads, and `app-pages` as archive readers make one;
 * - `open`: the book opened in place as the reader opens it, its entries read in order;
 * - `single`: one read at a time of 1 MB and of the server's largest;
 * - `inflight`: 2, 4, 8 and 16 reads of 1 MB in flight, and 2 and 4 of the largest;
 * - `rcvbuf`: 8 of 1 MB in flight with the device's receive buffer set to 256 KB, 1 MB, 4 MB;
 * - `conns`: 2 and 4 connections, each with 4 reads of 1 MB in flight.
 * `only` picks some of them, comma-separated. Never writes to the share, never logs names.
 */
class ShareSpeedService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), TYPE)
        val path = intent?.getStringExtra("path")
        val share = intent?.getStringExtra("share")
        val bytes = intent?.getLongExtra("bytes", DEFAULT_BYTES) ?: DEFAULT_BYTES
        val rounds = intent?.getIntExtra("rounds", 2) ?: 2
        val only = intent?.getStringExtra("only")?.split(',')?.map { it.trim() }?.toSet()
        thread(name = "share-speed") {
            try {
                val target = path ?: DebugBooks.shareId(share)?.let { DebugBooks.firstBook(Remotes.rootOf(it), 3, largest = true) }
                if (target == null) log("no book found") else measure(target, bytes, rounds, only)
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                log("done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Share speed", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Measuring share speed")
            .setOngoing(true)
            .build()
    }

    /** A way of reading [bytes] from the start of the file, by name. */
    private class Way(val kind: String, val name: String, val read: (Long) -> Unit)

    private fun measure(path: String, wanted: Long, rounds: Int, only: Set<String>?) {
        val share = Remotes.share(Remotes.idOf(path) ?: return log("not on a share")) ?: return log("no such share")
        val where = share.path as? SharePath ?: return log("not an SMB share")
        val smbPath = listOf(where.dir, Remotes.relOf(path)).filter { it.isNotEmpty() }.joinToString("/").replace('/', '\\')
        val size = Remotes.open(path).use { it.length }
        val bytes = minOf(wanted, size)
        log("file of ${size / MB} MB; reading its first ${bytes / MB} MB")

        var t = System.nanoTime()
        Remotes.open(path).use { input -> drain(bytes) { at, buf, n -> input.read(at, buf, 0, n) } }
        log("warm-up (app path, wakes the disk): ${ms(t)} ms")

        val probe = Link.open(where, share, smbPath, rcvBuf = 0, readBuffer = MAX_CONFIG_READ)
        val maxRead = probe.maxRead
        probe.describe()
        probe.close()

        val ways = buildList {
            add(Way("app", "app (provider channel, 128 KB reads)") { n ->
                RemoteChannel(Remotes.open(path), maxBlock = ShareReads.MAX_RUN_BYTES, maxCached = ShareReads.MAX_KEPT_BYTES).use { ch ->
                    drain(n) { at, buf, len ->
                        ch.position(at)
                        ch.read(buf, 0, minOf(len, PROVIDER_READ))
                    }
                }
            })
            add(Way("app", "app-pages (reader channel, 256 KB reads)") { n ->
                RemoteChannel.open(path).use { ch ->
                    drain(n) { at, buf, len ->
                        ch.position(at)
                        ch.read(buf, 0, minOf(len, 256 * 1024))
                    }
                }
            })
            add(Way("open", "open in place (archive reader, entries in order)") { n -> openInPlace(path, n) })
            for (piece in listOf(MB.toInt(), maxRead).distinct()) {
                add(Way("single", "single ${kb(piece)}") { n -> Link.open(where, share, smbPath, 0, MAX_CONFIG_READ).use { link -> link.single(n, piece) } })
            }
            for (depth in listOf(2, 4, 8, 16)) {
                add(Way("inflight", "inflight $depth x 1 MB") { n -> Link.open(where, share, smbPath, 0, MAX_CONFIG_READ).use { it.pipelined(n, MB.toInt(), depth) } })
            }
            if (maxRead > MB) {
                for (depth in listOf(2, 4)) {
                    add(Way("inflight", "inflight $depth x ${kb(maxRead)}") { n ->
                        Link.open(where, share, smbPath, 0, MAX_CONFIG_READ).use { it.pipelined(n, maxRead, depth) }
                    })
                }
            }
            for (buf in listOf(256 * 1024, MB.toInt(), 4 * MB.toInt())) {
                add(Way("rcvbuf", "rcvbuf ${kb(buf)}, inflight 8 x 1 MB") { n ->
                    Link.open(where, share, smbPath, buf, MAX_CONFIG_READ).use { it.pipelined(n, MB.toInt(), 8) }
                })
            }
            for (count in listOf(2, 4)) {
                add(Way("conns", "conns $count, each inflight 4 x 1 MB") { n -> spread(where, share, smbPath, n, count) })
            }
        }.filter { only == null || it.kind in only }

        val results = HashMap<String, MutableList<Double>>()
        repeat(rounds) { round ->
            for (way in ways) {
                t = System.nanoTime()
                val rate = runCatching { way.read(bytes) }.fold(
                    onSuccess = { bytes / 1e6 / (ms(t).coerceAtLeast(1) / 1e3) },
                    onFailure = { log("${way.name}: failed: ${it.javaClass.simpleName}: ${it.message}"); null }
                ) ?: continue
                results.getOrPut(way.name) { ArrayList() } += rate
                log("round ${round + 1}: ${way.name}: ${ms(t)} ms, ${"%.2f".format(rate)} MB/s")
            }
        }
        log("summary (best of $rounds, MB/s):")
        for (way in ways) results[way.name]?.let { log("  ${way.name}: ${"%.2f".format(it.max())} (all ${it.joinToString { r -> "%.2f".format(r) }})") }
    }

    /** Opens the book as the reader does, in place, and reads its entries in order until [bytes] of them came. */
    private fun openInPlace(path: String, bytes: Long) {
        val t = System.nanoTime()
        val reader = ArchiveReader.open(Remotes.file(path), NameCharset.AUTO) ?: throw IOException("Not an archive")
        reader.use { r ->
            log("  open: ${r.javaClass.simpleName}, ${r.entries.size} entries listed in ${ms(t)} ms, random access ${r.randomAccess}")
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            for ((i, e) in r.entries.withIndex()) {
                if (total >= bytes) break
                total += r.read(e) { s ->
                    var n = 0L
                    while (true) {
                        val got = s.read(buffer)
                        if (got < 0) break
                        n += got
                    }
                    n
                }
                if (i == 0) log("  first entry, ${total / 1024} KB, read by ${ms(t)} ms")
            }
        }
    }

    /** Reads [bytes] from the start through [count] connections, each taking every [count]th piece of 1 MB, 4 in flight. */
    private fun spread(where: SharePath, share: minty.emanata.net.Share, smbPath: String, bytes: Long, count: Int) {
        val links = (0 until count).map { Link.open(where, share, smbPath, 0, MAX_CONFIG_READ) }
        try {
            val failures = ArrayList<Throwable>()
            val threads = links.mapIndexed { i, link ->
                thread(name = "spread-$i") {
                    runCatching { link.pipelined(bytes, MB.toInt(), 4, first = i, stride = count) }.onFailure { synchronized(failures) { failures += it } }
                }
            }
            threads.forEach { it.join() }
            failures.firstOrNull()?.let { throw it }
        } finally {
            links.forEach { runCatching { it.close() } }
        }
    }

    /** One SMB connection of its own with the file open, read-only. */
    private class Link(
        private val client: SMBClient,
        private val connection: Connection,
        private val session: Session,
        private val disk: DiskShare,
        private val file: SmbFile,
        private val sockets: BufferedSockets
    ) : AutoCloseable {
        val maxRead: Int get() = connection.negotiatedProtocol.maxReadSize

        fun describe() {
            val p = connection.negotiatedProtocol
            val ctx = connection.connectionContext
            log("dialect ${p.dialect}, max read ${kb(p.maxReadSize)}, max write ${kb(p.maxWriteSize)}, max transact ${kb(p.maxTransactSize)}")
            log("multi-credit ${ctx.supportsMultiCredit()}, credits now ${credits()}")
            log("signing: server requires ${ctx.isServerRequiresSigning}, session signs ${session.isSigningRequired}; encryption ${runCatching { session.shouldEncryptData() }.getOrNull()}")
            log("guest ${session.isGuest}, anonymous ${session.isAnonymous}")
            sockets.last?.let { log("socket: receive buffer ${kb(it.receiveBufferSize)}, send buffer ${kb(it.sendBufferSize)}, no delay ${it.tcpNoDelay}") }
            // A burst of reads, so the credits granted along the way show.
            pipelined(16 * MB, MB.toInt(), 8)
            log("credits after 16 x 1 MB, 8 in flight: ${credits()}")
        }

        fun credits(): Int? = runCatching { (WINDOW?.get(connection) as? SequenceWindow)?.available() }.getOrNull()

        /** Reads [bytes] one request at a time, each up to [piece]. */
        fun single(bytes: Long, piece: Int) {
            val buffer = ByteArray(piece)
            var at = 0L
            while (at < bytes) {
                val n = file.read(buffer, at, 0, minOf(piece.toLong(), bytes - at).toInt())
                if (n <= 0) break
                at += n
            }
        }

        /**
         * Reads pieces [first], [first] + [stride], … of [piece] bytes up to [bytes], with
         * [depth] requests in flight, never more than the credits granted allow.
         */
        fun pipelined(bytes: Long, piece: Int, depth: Int, first: Int = 0, stride: Int = 1) {
            val tc = disk.treeConnect
            val dialect = tc.negotiatedProtocol.dialect
            val queue = ArrayDeque<Future<SMB2ReadResponse>>()
            var next = first.toLong() * piece
            val charge = (piece + CREDIT_BYTES - 1) / CREDIT_BYTES
            while (next < bytes || queue.isNotEmpty()) {
                while (next < bytes && queue.size < depth && (queue.isEmpty() || (credits() ?: Int.MAX_VALUE) > charge + 4)) {
                    val length = minOf(piece.toLong(), bytes - next).toInt()
                    queue.addLast(session.send(SMB2ReadRequest(dialect, file.fileId, session.sessionId, tc.treeId, next, length)))
                    next += piece.toLong() * stride
                }
                val response = queue.removeFirst().get(60, TimeUnit.SECONDS)
                val status = response.header.statusCode
                if (status == NtStatus.STATUS_END_OF_FILE.value) continue
                if (status != NtStatus.STATUS_SUCCESS.value) throw SMBApiException(response.header, "Read failed")
                response.data
            }
        }

        override fun close() {
            runCatching { file.close() }
            runCatching { disk.close() }
            runCatching { session.close() }
            runCatching { connection.close(true) }
            runCatching { client.close() }
        }

        companion object {
            private const val CREDIT_BYTES = 64 * 1024

            private val WINDOW = runCatching { Connection::class.java.getDeclaredField("sequenceWindow").apply { isAccessible = true } }.getOrNull()

            /** Connects as the app does, with reads of up to [readBuffer] and, unless 0, a receive buffer of [rcvBuf]. */
            fun open(where: SharePath, share: minty.emanata.net.Share, smbPath: String, rcvBuf: Int, readBuffer: Int): Link {
                val sockets = BufferedSockets(rcvBuf, CONNECT_MS)
                val config = SmbConfig.builder()
                    .withClientGuid(UUID.randomUUID())
                    .withSecurityProvider(BCSecurityProvider())
                    .withAuthenticators(NtlmAuthenticator.Factory())
                    .withSocketFactory(sockets)
                    .withTimeout(60, TimeUnit.SECONDS)
                    .withSoTimeout(0, TimeUnit.SECONDS)
                    .withReadBufferSize(readBuffer)
                    .withSigningRequired(false)
                    .withEncryptData(false)
                    .withMultiProtocolNegotiate(false)
                    .withDfsEnabled(false)
                    .build()
                val client = SMBClient(config)
                val t = System.nanoTime()
                val connection = client.connect(where.host, where.port)
                var session: Session? = null
                for (auth in SmbFs.authentications(share)) {
                    session = runCatching { connection.authenticate(auth) }.getOrNull()
                    if (session != null) break
                }
                session ?: throw IllegalStateException("Sign-in refused")
                val disk = session.connectShare(where.share) as DiskShare
                val file = disk.openFile(smbPath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                Log.d(TAG, "connected and opened in ${ms(t)} ms")
                return Link(client, connection, session, disk, file, sockets)
            }
        }
    }

    /** Sockets connected with a receive buffer of [rcvBuf] bytes set first (0: the system's own sizing). */
    private class BufferedSockets(private val rcvBuf: Int, private val connectMs: Int) : SocketFactory() {
        @Volatile
        var last: Socket? = null

        override fun createSocket(): Socket = Socket().also { if (rcvBuf > 0) it.receiveBufferSize = rcvBuf }

        override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = connect(InetSocketAddress(host, port))

        override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = connect(InetSocketAddress(address, port))

        private fun connect(to: InetSocketAddress): Socket = createSocket().also {
            it.connect(to, connectMs)
            last = it
        }
    }

    private companion object {
        const val TAG = "ShareSpeed"
        const val CHANNEL = "share-speed"
        const val NOTIFICATION_ID = 0x5d
        const val MB = 1L shl 20
        const val DEFAULT_BYTES = 32 * MB
        const val PROVIDER_READ = 128 * 1024
        const val CONNECT_MS = 60_000

        /** The read size SMBJ is allowed; the server's own maximum caps it. */
        const val MAX_CONFIG_READ = 16 shl 20
        val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        fun log(text: String) {
            Log.i(TAG, text)
        }

        fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

        fun kb(bytes: Int) = if (bytes >= MB && bytes % MB == 0L) "${bytes / MB} MB" else "${bytes / 1024} KB"

        /** Reads [bytes] from the start through [read], 1 MB asked at a time. */
        fun drain(bytes: Long, read: (Long, ByteArray, Int) -> Int) {
            val buffer = ByteArray(MB.toInt())
            var at = 0L
            while (at < bytes) {
                val n = read(at, buffer, minOf(buffer.size.toLong(), bytes - at).toInt())
                if (n <= 0) break
                at += n
            }
        }
    }
}
