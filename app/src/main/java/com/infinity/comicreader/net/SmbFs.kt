package com.infinity.comicreader.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
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
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/**
 * A [Share] over SMB 2 or 3 (SMBJ): the dialect is negotiated, never SMB1; signing
 * and encryption are used as the server requires; NTLM signs in, without a user
 * name anonymously, else as a guest. One connection per share, made on first use, made again once when it
 * drops, and closed after [IDLE_MS] without use while no file is open, so nothing
 * goes over the network unless the share is browsed or read. A sign-in or share the
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

    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    private var lastUsed = 0L
    private val inputs = HashSet<SmbInput>()

    @Volatile
    private var closed = false

    /** Why connecting last failed, and when. */
    private var failure: RemoteException? = null
    private var failedAt = 0L

    init {
        idleCheck()
    }

    override fun list(path: String): List<RemoteStat> = call { d ->
        d.list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { RemoteStat(it.fileName, isDirectory(it.fileAttributes), it.endOfFile, millis(it.lastWriteTime), millis(it.creationTime)) }
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

    /** The open connection's share, marked as used; null when there is none. */
    private fun current(): DiskShare? = synchronized(lock) {
        if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
        lastUsed = System.currentTimeMillis()
        disk?.takeIf { connection?.isConnected == true }
    }

    private fun connected(): DiskShare {
        current()?.let { return it }
        synchronized(connecting) {
            current()?.let { return it }
            remembered()?.let { throw it }
            disconnect()
            val p = where
            var c: Connection? = null
            var s: Session? = null
            try {
                c = client.connect(p.host, p.port)
                val (signed, d) = signIn(c)
                s = signed
                synchronized(lock) {
                    if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
                    connection = c
                    session = s
                    disk = d
                }
                return d
            } catch (e: Throwable) {
                runCatching { s?.close() }
                runCatching { c?.close() }
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

    /** Takes the connection's parts away for [shut]; null when there is none. Under [lock]. */
    private fun take(): Triple<DiskShare?, Session?, Connection?>? {
        if (connection == null && session == null && disk == null) return null
        return Triple(disk, session, connection).also {
            disk = null
            session = null
            connection = null
        }
    }

    private fun shut(parts: Triple<DiskShare?, Session?, Connection?>) {
        runCatching { parts.first?.close() }
        runCatching { parts.second?.close() }
        runCatching { parts.third?.close() }
    }

    /** Closes the connection once it has been idle for [IDLE_MS] with no file open. */
    private fun idleCheck() {
        timer.schedule({
            synchronized(lock) {
                if (inputs.isEmpty() && System.currentTimeMillis() - lastUsed >= IDLE_MS) take() else null
            }?.let(::shut)
            if (!closed) idleCheck()
        }, IDLE_MS / 2, TimeUnit.MILLISECONDS)
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

        override fun close() {
            synchronized(this) {
                runCatching { file?.close() }
                file = null
            }
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

    companion object {
        private const val TAG = "SmbFs"

        /** How long a connection stays open unused. */
        const val IDLE_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_S = 30L

        private val config: SmbConfig by lazy {
            SmbConfig.builder()
                // Android's JCE lacks MD4 and AES-CMAC, which NTLM and SMB 3 signing need.
                .withSecurityProvider(BCSecurityProvider())
                // SPNEGO/Kerberos needs javax.security.auth.kerberos and org.ietf.jgss, absent on Android.
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withSocketFactory(ProxySocketFactory(CONNECT_TIMEOUT_MS))
                .withTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .withSoTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .withSigningRequired(false)
                .withEncryptData(false)
                .withMultiProtocolNegotiate(false)
                .withDfsEnabled(false)
                .build()
        }

        private val client: SMBClient by lazy { SMBClient(config) }

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
