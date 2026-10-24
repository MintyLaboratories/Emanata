package com.infinity.comicreader.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Packet
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import android.util.Log
import com.infinity.comicreader.util.Timing
import java.io.IOException
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/**
 * A [Share] over SMB 2 or 3 (SMBJ): the dialect is negotiated, never SMB1; signing
 * and encryption are used as the server requires; NTLM signs in, without a user
 * name anonymously, else as a guest. One connection per share, its own even beside
 * another share on the same host, made on first use and made again silently when it
 * drops. Kept signed in while unused, echoed every [KEEPALIVE_MS], so coming back to
 * the share within [IDLE_MS] signs in no more; closed after [IDLE_MS] without use
 * while no file is open, or [OPEN_IDLE_MS] while one is (it opens again when next
 * read), so nothing goes over the network for long unless the share is browsed or
 * read. A sign-in or share the
 * host turned down is not tried again until [retry]; a host that could not be
 * reached, not for [Remotes.QUIET_MS]; so callers waiting to connect meanwhile fail
 * at once.
 */
class SmbFs(private val share: Share) : RemoteFs {
    private val where = share.path as? SharePath ?: throw IllegalArgumentException("Not an SMB share: $share")

    /** Guards the fields below; never held across network I/O, so [close] never waits on it. */
    private val lock = Any()

    /** Serializes connecting, so one sign-in at a time is under way. */
    private val connecting = Any()

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    private var lastUsed = 0L
    private var lastEcho = 0L
    private val inputs = HashSet<SmbInput>()

    /** True while [idleCheck] is scheduled. */
    private var checking = false

    @Volatile
    private var closed = false

    /** Why connecting last failed, and when. */
    private var failure: RemoteException? = null
    private var failedAt = 0L

    override fun list(path: String): List<RemoteStat> = Timing.span("smb list") {
        call { d ->
            d.list(smbPath(path))
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { RemoteStat(it.fileName, isDirectory(it.fileAttributes), it.endOfFile, millis(it.lastWriteTime), millis(it.creationTime)) }
        }
    }

    override fun stat(path: String): RemoteStat? = try {
        call { d ->
            val info = d.getFileInformation(smbPath(path))
            val basic = info.basicInformation
            val dir = info.standardInformation.isDirectory || isDirectory(basic.fileAttributes)
            RemoteStat(
                path.substringAfterLast('/'), dir, if (dir) 0 else info.standardInformation.endOfFile,
                millis(basic.lastWriteTime), millis(basic.creationTime)
            )
        }
    } catch (e: RemoteException) {
        if (e.reason != RemoteException.Reason.MISSING) throw e
        null
    }

    override fun open(path: String): RemoteInput {
        val (file, length) = openFile(path)
        return SmbInput(path, file, length).also { synchronized(lock) { inputs += it } }
    }

    override fun space(): Pair<Long, Long>? = call { d ->
        val info = d.shareInformation
        if (info.totalSpace <= 0) null else info.freeSpace to info.totalSpace
    }

    override fun retry() {
        synchronized(lock) { failure = null }
    }

    /** Closes the connection at once, even while another thread is connecting; that one then fails. */
    override fun close() {
        closed = true
        disconnect()
    }

    private fun openFile(path: String): Pair<SmbFile, Long> = call { d ->
        val file = d.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null
        )
        file to file.getFileInformation(FileStandardInformation::class.java).endOfFile
    }

    /** [path] inside the share, with the share's folder in front and `\` between names. */
    private fun smbPath(path: String): String =
        listOf(where.dir, path).filter { it.isNotEmpty() }.joinToString("/").replace('/', '\\')

    /** Runs [block] on the share, connecting first; once more on a new connection when the old one dropped. */
    private fun <T> call(block: (DiskShare) -> T): T {
        var retried = false
        while (true) {
            val d = connected()
            try {
                return block(d)
            } catch (e: Exception) {
                val failure = translate(e)
                if (failure !is Dropped || retried) throw failure.toRemote()
                retried = true
                disconnect()
            }
        }
    }

    /**
     * The open connection's share, marked as used; null when there is none, or when it
     * went unheard for longer than echoes keep it (the device slept) and fails an echo now.
     */
    private fun current(): DiskShare? {
        val now = System.currentTimeMillis()
        val (d, c, quiet) = synchronized(lock) {
            if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
            val quiet = now - maxOf(lastUsed, lastEcho) > KEEPALIVE_MS + ECHO_SLACK_MS
            lastUsed = now
            Triple(disk?.takeIf { connection?.isConnected == true }, connection, quiet)
        }
        if (d == null || c == null || !quiet) return d
        // Checked quickly, so a connection the network dropped meanwhile is not waited on for a whole timeout.
        return d.takeIf { echo(c, WAKE_ECHO_TIMEOUT_S) }
    }

    private fun connected(): DiskShare {
        current()?.let { return it }
        synchronized(connecting) {
            current()?.let { return it }
            remembered()?.let { throw it }
            disconnect()
            val p = where
            // Its own client: SMBJ's shares one connection per host among its callers, and
            // after one closes it may hand out a connection that never answers again.
            val own = SMBClient(config())
            var c: Connection? = null
            var s: Session? = null
            try {
                c = Timing.span("smb connect") { own.connect(p.host, p.port) }
                val (signed, d) = Timing.span("smb sign-in") { signIn(c) }
                s = signed
                synchronized(lock) {
                    if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
                    client = own
                    connection = c
                    session = s
                    disk = d
                    lastEcho = System.currentTimeMillis()
                    if (!checking) {
                        checking = true
                        idleCheck()
                    }
                }
                return d
            } catch (e: Throwable) {
                runCatching { s?.close() }
                runCatching { c?.close(true) }
                runCatching { own.close() }
                // A missing platform class (SMBJ code Android lacks) reads as a failure, not a crash.
                if (e !is Exception) Log.w(TAG, "Connecting failed", e)
                throw remember(translate(e as? Exception ?: IOException(e)).toRemote())
            }
        }
    }

    /** A session on [c] and the share in it, trying each of [authentications] while the host turns them down. */
    private fun signIn(c: Connection): Pair<Session, DiskShare> {
        val ways = authentications(share)
        for ((i, auth) in ways.withIndex()) {
            var s: Session? = null
            try {
                s = c.authenticate(auth)
                val d = s.connectShare(where.share) as? DiskShare ?: throw RemoteException(RemoteException.Reason.NO_SHARE)
                return s to d
            } catch (e: SMBApiException) {
                runCatching { s?.close() }
                if (i == ways.lastIndex) throw e
            } catch (e: Exception) {
                runCatching { s?.close() }
                throw e
            }
        }
        throw IllegalStateException("No way to sign in")
    }

    /** The failure to repeat instead of connecting: a refusal until [retry], else one from the last [Remotes.QUIET_MS]. */
    private fun remembered(): RemoteException? = synchronized(lock) {
        val f = failure ?: return null
        if (!f.lasting && System.currentTimeMillis() - failedAt >= Remotes.QUIET_MS) {
            failure = null
            return null
        }
        RemoteException(f.reason, f.message, f, f.lasting)
    }

    /** [error] from connecting, made lasting when the host turned the sign-in or share down, and kept for [remembered]. */
    private fun remember(error: IOException): IOException {
        if (error !is RemoteException || error.reason == RemoteException.Reason.UNKNOWN_SHARE) return error
        val kept = if (error.reason == RemoteException.Reason.DENIED || error.reason == RemoteException.Reason.NO_SHARE) {
            RemoteException(error.reason, error.message, error.cause, lasting = true)
        } else {
            error
        }
        synchronized(lock) {
            failure = kept
            failedAt = System.currentTimeMillis()
        }
        return kept
    }

    /** Drops the connection, closing it outside [lock]. */
    private fun disconnect() {
        synchronized(lock) { take() }?.let(::shut)
    }

    /** A connection's parts, closed together. */
    private class Parts(val disk: DiskShare?, val session: Session?, val connection: Connection?, val client: SMBClient?)

    /** Takes the connection's parts away for [shut]; null when there is none. Under [lock]. */
    private fun take(): Parts? {
        if (connection == null && session == null && disk == null && client == null) return null
        return Parts(disk, session, connection, client).also {
            disk = null
            session = null
            connection = null
            client = null
        }
    }

    private fun shut(parts: Parts) {
        runCatching { parts.disk?.close() }
        runCatching { parts.session?.close() }
        runCatching { parts.connection?.close(true) }
        runCatching { parts.client?.close() }
    }

    /**
     * Every [KEEPALIVE_MS] while connected ([idleAction]): closes the connection once it
     * has been unused for [IDLE_MS] with no file open ([OPEN_IDLE_MS] with one), else
     * echoes it when unused since the last check, so the host and the network between
     * keep it. Stops with the connection.
     */
    private fun idleCheck() {
        timer.schedule(::idleStep, KEEPALIVE_MS, TimeUnit.MILLISECONDS)
    }

    private fun idleStep() {
        val now = System.currentTimeMillis()
        var echoing: Connection? = null
        var open: List<SmbInput> = emptyList()
        var unused = 0L
        val dropped = synchronized(lock) {
            when (idleAction(now, lastUsed, lastEcho, inputs.isNotEmpty(), connection != null)) {
                IdleAction.CLOSE -> {
                    open = inputs.toList()
                    unused = now - lastUsed
                    take()
                }
                IdleAction.ECHO -> {
                    echoing = connection
                    null
                }
                IdleAction.NONE -> null
            }
        }
        if (dropped != null) {
            Timing.mark("smb idle close after ${unused / 1000} s, ${open.size} files open")
            open.forEach { it.forget() }
            shut(dropped)
        }
        echoing?.let { echo(it, ECHO_TIMEOUT_S) }
        val again = synchronized(lock) {
            checking = !closed && connection != null
            checking
        }
        if (again) idleCheck()
    }

    /**
     * Echoes [c], waiting up to [timeoutS] seconds; true when it answered. One that does
     * not is dropped, to be made again on next use.
     */
    private fun echo(c: Connection, timeoutS: Long): Boolean {
        val answer = runCatching {
            c.send<SMB2Packet>(SMB2Echo(c.negotiatedProtocol.dialect)).get(timeoutS, TimeUnit.SECONDS)
        }
        val ok = answer.isSuccess
        Timing.mark("smb echo ${answer.exceptionOrNull()?.let { "failed: $it" } ?: "answered"}")
        synchronized(lock) {
            if (ok) {
                lastEcho = System.currentTimeMillis()
                null
            } else if (connection === c) {
                take()
            } else {
                null
            }
        }?.let(::shut)
        return ok
    }

    /** A file open for reading; it opens again on a new connection when the old one dropped. */
    private inner class SmbInput(private val path: String, private var file: SmbFile?, override val length: Long) : RemoteInput {
        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= length) return -1
            var retried = false
            while (true) {
                val f = synchronized(this) { file ?: openFile(path).first.also { file = it } }
                try {
                    synchronized(lock) { lastUsed = System.currentTimeMillis() }
                    val n = f.read(buffer, position, offset, count)
                    return if (n <= 0) -1 else n
                } catch (e: Exception) {
                    val failure = translate(e)
                    if (failure !is Dropped || retried) throw failure.toRemote()
                    retried = true
                    synchronized(this) {
                        runCatching { f.close() }
                        file = null
                    }
                    disconnect()
                }
            }
        }

        /** Lets go of the file on a connection closing for idleness; the next read opens it again. */
        fun forget() {
            synchronized(this) {
                runCatching { file?.close() }
                file = null
            }
        }

        override fun close() {
            forget()
            synchronized(lock) {
                inputs -= this
                lastUsed = System.currentTimeMillis()
            }
        }
    }

    /** What went wrong, sorted for [call]: a connection that dropped is worth one more try. */
    private sealed class Failure(val cause: Throwable) {
        abstract fun toRemote(): IOException
    }

    private class Dropped(cause: Throwable) : Failure(cause) {
        override fun toRemote() = RemoteException(RemoteException.Reason.UNREACHABLE, cause.message, cause)
    }

    private class Known(private val error: IOException) : Failure(error) {
        override fun toRemote() = error
    }

    private fun translate(e: Exception): Failure {
        if (e is RemoteException) return Known(e)
        if (e is SMBApiException) {
            return when (e.status) {
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_NOT_A_DIRECTORY ->
                    Known(RemoteException(RemoteException.Reason.MISSING, e.message, e))
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_ACCOUNT_DISABLED,
                NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED ->
                    Known(RemoteException(RemoteException.Reason.DENIED, e.message, e))
                NtStatus.STATUS_BAD_NETWORK_NAME -> Known(RemoteException(RemoteException.Reason.NO_SHARE, e.message, e))
                NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED,
                NtStatus.STATUS_NETWORK_SESSION_EXPIRED, NtStatus.STATUS_FILE_CLOSED -> Dropped(e)
                else -> Known(IOException(e.message, e))
            }
        }
        // Transport failures, timeouts and refused connections: the host is gone or the link dropped.
        return Dropped(e)
    }

    /** What [idleCheck] does to a connection. */
    internal enum class IdleAction { NONE, ECHO, CLOSE }

    companion object {
        private const val TAG = "SmbFs"

        /** How long a connection stays open unused. */
        const val IDLE_MS = 10 * 60_000L

        /** How long a connection stays open unused while a file on it is open, as in a book left open. */
        const val OPEN_IDLE_MS = 30 * 60_000L

        /** How often an unused connection is echoed, and checked for [IDLE_MS]. */
        const val KEEPALIVE_MS = 2 * 60_000L

        /** Lets a check that runs a little early still echo. */
        private const val ECHO_SLACK_MS = 5_000L
        private const val ECHO_TIMEOUT_S = 10L

        /** How long a connection unheard for a while (the device slept) gets to answer before it is made anew. */
        private const val WAKE_ECHO_TIMEOUT_S = 2L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_S = 30L

        /** A connection's settings: its own client id, so the host never takes two connections for one client. */
        private fun config(): SmbConfig =
            SmbConfig.builder()
                .withClientGuid(UUID.randomUUID())
                // Android's JCE lacks MD4 and AES-CMAC, which NTLM and SMB 3 signing need.
                .withSecurityProvider(BCSecurityProvider())
                // SPNEGO/Kerberos needs javax.security.auth.kerberos and org.ietf.jgss, absent on Android.
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withSocketFactory(ProxySocketFactory(CONNECT_TIMEOUT_MS))
                .withTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                // No socket timeout: SMBJ's reader would take a quiet connection for a dead one
                // and drop it; answers are timed per request, and echoes find a dead link.
                .withSoTimeout(0, TimeUnit.SECONDS)
                .withSigningRequired(false)
                .withEncryptData(false)
                .withMultiProtocolNegotiate(false)
                .withDfsEnabled(false)
                .build()

        /**
         * What a connection last used at [lastUsed] and last echoed at [lastEcho] needs at
         * [now]: closing once unused for [IDLE_MS] with no file [open] ([OPEN_IDLE_MS] with
         * one), an echo when unused
         * since the last [KEEPALIVE_MS], else nothing; nothing without a connection.
         */
        internal fun idleAction(now: Long, lastUsed: Long, lastEcho: Long, open: Boolean, connected: Boolean): IdleAction = when {
            !connected -> IdleAction.NONE
            now - lastUsed >= if (open) OPEN_IDLE_MS else IDLE_MS -> IdleAction.CLOSE
            now - maxOf(lastUsed, lastEcho) >= KEEPALIVE_MS - ECHO_SLACK_MS -> IdleAction.ECHO
            else -> IdleAction.NONE
        }

        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "smb-idle").apply { isDaemon = true } }
        }

        /** How to sign in to [share], in order: as its user, or without one anonymously, then as a guest. */
        internal fun authentications(share: Share): List<AuthenticationContext> =
            if (share.account.isEmpty()) {
                // Some hosts refuse anonymous sessions but take guests, others the other way round.
                listOf(AuthenticationContext.anonymous(), AuthenticationContext.guest())
            } else {
                listOf(AuthenticationContext(share.account, share.password.toCharArray(), share.domain))
            }

        private fun isDirectory(attributes: Long) = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

        private fun millis(time: FileTime?): Long = time?.toEpochMillis()?.takeIf { it > 0 } ?: 0L
    }
}
