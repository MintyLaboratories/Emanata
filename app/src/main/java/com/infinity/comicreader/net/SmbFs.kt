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
 * and encryption are used as the server requires; NTLM signs in, as a guest without
 * a user name. One connection per share, made on first use, made again once when it
 * drops, and closed after [IDLE_MS] without use while no file is open, so nothing
 * goes over the network unless the share is browsed or read.
 */
class SmbFs(private val share: Share) : RemoteFs {
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    private var lastUsed = 0L
    private val inputs = HashSet<SmbInput>()
    private var closed = false

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
    } catch (e: Missing) {
        null
    }

    override fun open(path: String): RemoteInput {
        val (file, length) = openFile(path)
        return SmbInput(path, file, length).also { synchronized(this) { inputs += it } }
    }

    override fun space(): Pair<Long, Long>? = call { d ->
        val info = d.shareInformation
        if (info.totalSpace <= 0) null else info.freeSpace to info.totalSpace
    }

    @Synchronized
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
        listOf(share.path.dir, path).filter { it.isNotEmpty() }.joinToString("/").replace('/', '\\')

    /** Runs [block] on the share, connecting first; once more on a new connection when the old one dropped. */
    private fun <T> call(block: (DiskShare) -> T): T {
        var retried = false
        while (true) {
            val d = synchronized(this) {
                lastUsed = System.currentTimeMillis()
                connected()
            }
            try {
                return block(d)
            } catch (e: Exception) {
                val failure = translate(e)
                if (failure !is Dropped || retried) throw failure.toRemote()
                retried = true
                synchronized(this) { disconnect() }
            }
        }
    }

    private fun connected(): DiskShare {
        if (closed) throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
        disk?.takeIf { connection?.isConnected == true }?.let { return it }
        disconnect()
        val p = share.path
        try {
            val c = client.connect(p.host, p.port)
            connection = c
            val auth = if (share.account.isEmpty()) AuthenticationContext.anonymous()
            else AuthenticationContext(share.account, share.password.toCharArray(), share.domain)
            val s = c.authenticate(auth)
            session = s
            val d = s.connectShare(p.share) as? DiskShare ?: throw RemoteException(RemoteException.Reason.NO_SHARE)
            disk = d
            return d
        } catch (e: Throwable) {
            disconnect()
            // A missing platform class (SMBJ code Android lacks) reads as a failure, not a crash.
            if (e !is Exception) Log.w(TAG, "Connecting failed", e)
            throw translate(e as? Exception ?: IOException(e)).toRemote()
        }
    }

    private fun disconnect() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        disk = null
        session = null
        connection = null
    }

    /** Closes the connection once it has been idle for [IDLE_MS] with no file open. */
    private fun idleCheck() {
        timer.schedule({
            val again = synchronized(this) {
                if (!closed && connection != null && inputs.isEmpty() && System.currentTimeMillis() - lastUsed >= IDLE_MS) disconnect()
                !closed
            }
            if (again) idleCheck()
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
                    synchronized(this@SmbFs) { lastUsed = System.currentTimeMillis() }
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
                    synchronized(this@SmbFs) { disconnect() }
                }
            }
        }

        override fun close() {
            synchronized(this) {
                runCatching { file?.close() }
                file = null
            }
            synchronized(this@SmbFs) {
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

    /** Thrown inside [stat] for a path that is not there. */
    private class Missing(cause: Throwable) : IOException(cause)

    private fun translate(e: Exception): Failure {
        if (e is RemoteException) return Known(e)
        if (e is Missing) return Known(e)
        if (e is SMBApiException) {
            return when (e.status) {
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_NOT_A_DIRECTORY -> Known(Missing(e))
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

        private fun isDirectory(attributes: Long) = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

        private fun millis(time: FileTime?): Long = time?.toEpochMillis()?.takeIf { it > 0 } ?: 0L
    }
}
