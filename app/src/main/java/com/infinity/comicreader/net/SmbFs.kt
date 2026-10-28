package com.infinity.comicreader.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Packet
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.mssmb2.messages.SMB2ReadRequest
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.connection.SequenceWindow
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import android.util.Log
import com.infinity.comicreader.util.Timing
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
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

    /** The [Remotes.answerMs] the connection was made with. */
    private var answerMs = 0L

    /** Calls and reads under way ([busy]); a connection is made anew for another [Remotes.answerMs] only with one alone. */
    private var underWay = 0
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
                .map {
                    RemoteStat(
                        it.fileName, isDirectory(it.fileAttributes), it.endOfFile, millis(it.lastWriteTime), millis(it.creationTime),
                        isLink(it.fileAttributes)
                    )
                }
        }
    }

    override fun stat(path: String): RemoteStat? = try {
        call { d ->
            val info = d.getFileInformation(smbPath(path))
            val basic = info.basicInformation
            val dir = info.standardInformation.isDirectory || isDirectory(basic.fileAttributes)
            RemoteStat(
                path.substringAfterLast('/'), dir, if (dir) 0 else info.standardInformation.endOfFile,
                millis(basic.lastWriteTime), millis(basic.creationTime), isLink(basic.fileAttributes)
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

    override fun mkdir(path: String) {
        val p = smbPath(path)
        change({ d -> d.folderExists(p) }) { d -> d.mkdir(p) }
    }

    /** Written in [WRITE_BYTES] pieces, each marking the connection as used; never repeated, as [input] is consumed. */
    override fun create(path: String, input: InputStream, length: Long) = busy {
        val file = once { d ->
            d.openFile(
                smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
        }
        try {
            val buffer = ByteArray(WRITE_BYTES)
            var at = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                once { file.write(buffer, at, 0, n) }
                at += n
            }
        } finally {
            runCatching { file.close() }
        }
    }

    override fun rename(from: String, to: String) {
        val source = smbPath(from)
        val target = smbPath(to)
        val landed = { d: DiskShare ->
            if (source.equals(target, ignoreCase = true)) {
                // A change of case only: the item is found under either name, so its listed name tells.
                val name = target.substringAfterLast('\\')
                d.list(target.substringBeforeLast('\\', ""), name).any { it.fileName == name }
            } else {
                exists(d, target) && !exists(d, source)
            }
        }
        change(landed) { d ->
            d.open(
                source, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
            ).use { it.rename(target, false) }
        }
    }

    /** Entry by entry ([deleteTree]): SMBJ's own recursive delete walks into links, deleting what they point to. */
    override fun delete(path: String) {
        require(path.trim('/').isNotEmpty()) { "Not deleting the share" }
        deleteTree(entryAt(smbPath(path)), ::entriesOf, ::removeEntry)
    }

    override fun remove(path: String) {
        require(path.trim('/').isNotEmpty()) { "Not deleting the share" }
        removeEntry(entryAt(smbPath(path)))
    }

    /** What is at [p], a link as itself rather than what it points to. */
    private fun entryAt(p: String): Entry = call { d ->
        d.open(
            p, EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
        ).use { entryOf(p, it.getFileInformation(FileBasicInformation::class.java).fileAttributes) }
    }

    /** The entries of folder [dir], by their paths inside the share. */
    private fun entriesOf(dir: String): List<Entry> = call { d ->
        d.list(dir).filter { it.fileName != "." && it.fileName != ".." }.map { entryOf("$dir\\${it.fileName}", it.fileAttributes) }
    }

    /** Deletes one file, link or empty folder: a link opened as itself, so it never reaches what it points to. */
    private fun removeEntry(e: Entry) = change({ d -> !exists(d, e.path) }) { d ->
        when {
            e.isLink -> d.open(
                e.path, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            ).use { it.deleteOnClose() }
            e.isDirectory -> d.rmdir(e.path, false)
            else -> d.rm(e.path)
        }
    }

    /**
     * Copies with the server's own copy (SMB copychunk) in [COPY_BYTES] pieces, so
     * [progress] hears each and may stop it; false, leaving nothing at [to], when the
     * server turns the first piece down, as one without copychunk or denying it does.
     */
    override fun copy(from: String, to: String, progress: (Long) -> Unit): Boolean = busy {
        val source = once { d ->
            d.openFile(
                smbPath(from), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
        }
        try {
            val length = once { source.getFileInformation(FileStandardInformation::class.java).endOfFile }
            val target = once { d ->
                d.openFile(
                    smbPath(to), EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                )
            }
            var at = 0L
            try {
                while (at < length) {
                    val n = minOf(COPY_BYTES, length - at)
                    try {
                        once { source.remoteCopyTo(at, target, at, n) }
                    } catch (e: IOException) {
                        // Turned down by the server (no copychunk, or denied it): copied through the device instead.
                        val dropped = e is RemoteException && e.reason == RemoteException.Reason.UNREACHABLE
                        if (at > 0 || dropped || e.cause !is SMBApiException) throw e
                        runCatching { target.close() }
                        runCatching { once { d -> d.rm(smbPath(to)) } }
                        return@busy false
                    }
                    at += n
                    progress(at)
                }
            } finally {
                runCatching { target.close() }
            }
            true
        } finally {
            runCatching { source.close() }
        }
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

    /** Makes a change on the share ([changeOnce]), connecting first. */
    private fun change(landed: (DiskShare) -> Boolean, block: (DiskShare) -> Unit) = busy {
        changeOnce(::connected, ::failed, landed, block)
    }

    /** What failure [e] of a call means, and whether the connection dropped; a dropped one is let go. */
    private fun failed(e: Exception): Pair<IOException, Boolean> {
        val failure = translate(e)
        if (failure is Dropped) disconnect()
        return failure.toRemote() to (failure is Dropped)
    }

    /** True when anything is at [p], a link counting as itself. */
    private fun exists(d: DiskShare, p: String): Boolean = try {
        d.open(
            p, EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
        ).close()
        true
    } catch (e: SMBApiException) {
        if (e.status !in MISSING_STATUSES) throw e
        false
    }

    /** Runs [block] on the share, connecting first; once more on a new connection when the old one dropped. */
    private fun <T> call(block: (DiskShare) -> T): T = busy {
        val d = connected()
        try {
            block(d)
        } catch (e: Exception) {
            val failure = translate(e)
            if (failure !is Dropped) throw failure.toRemote()
            disconnect()
            val again = connected()
            try {
                block(again)
            } catch (e: Exception) {
                throw translate(e).toRemote()
            }
        }
    }

    /** Runs [block] on the share once, connecting first: for writes, which a new connection must not repeat. */
    private fun <T> once(block: (DiskShare) -> T): T = busy {
        val d = connected()
        try {
            block(d)
        } catch (e: Exception) {
            val failure = translate(e)
            if (failure is Dropped) disconnect()
            throw failure.toRemote()
        }
    }

    /**
     * Runs [block], counted in [underWay] throughout: every way to the connection goes through
     * here, so a write's open file and a read under way never lose it to a new [Remotes.answerMs].
     */
    private inline fun <T> busy(block: () -> T): T {
        synchronized(lock) { underWay++ }
        try {
            return block()
        } finally {
            synchronized(lock) { underWay-- }
        }
    }

    /**
     * The open connection's share, marked as used; null when there is none, when it was
     * made with another [Remotes.answerMs] and nothing else is under way ([remake]; then
     * dropped, its files opening again on the next), or when it went unheard for longer
     * than echoes keep it (the device slept) and fails an echo now.
     */
    private fun current(): DiskShare? {
        val now = System.currentTimeMillis()
        var open: List<SmbInput> = emptyList()
        val outdated = synchronized(lock) {
            if (!remake(connection != null, answerMs, Remotes.answerMs, underWay)) {
                null
            } else {
                open = inputs.toList()
                take()
            }
        }
        if (outdated != null) {
            open.forEach { it.forget() }
            shut(outdated)
            return null
        }
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
            val wait = Remotes.answerMs
            val own = SMBClient(config(wait))
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
                    answerMs = wait
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
     * keep it. Runs on [checks], so one share's slow echo never holds back another's.
     * Stops with the connection.
     */
    private fun idleCheck() {
        timer.schedule({ checks.execute(::idleStep) }, KEEPALIVE_MS, TimeUnit.MILLISECONDS)
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
        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int =
            busy { readNow(position, buffer, offset, count) }

        private fun readNow(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
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

        /**
         * Reads in pieces of up to [PIECE_BYTES], [DEPTH] in flight within the credits the
         * server granted ([Pipelined]); a read of one piece or less goes as one request.
         */
        override fun readFully(position: Long, buffer: ByteArray, offset: Int, count: Int, heard: () -> Unit): Int =
            busy { readMany(position, buffer, offset, count, heard) }

        private fun readMany(position: Long, buffer: ByteArray, offset: Int, count: Int, heard: () -> Unit): Int {
            if (position >= length) return -1
            val wanted = minOf(count.toLong(), length - position).toInt()
            if (wanted <= PIECE_BYTES) return super.readFully(position, buffer, offset, wanted, heard)
            var retried = false
            while (true) {
                val f = synchronized(this) { file ?: openFile(path).first.also { file = it } }
                try {
                    synchronized(lock) { lastUsed = System.currentTimeMillis() }
                    return Pipelined.fill(Reads(f), position, buffer, offset, wanted, PIECE_BYTES, DEPTH, Remotes.answerMs, heard)
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

    /** Reads of [file] sent as they are asked for, their answers awaited later. */
    private class Reads(private val file: SmbFile) : Pipelined.Source {
        private val tree = file.diskShare.treeConnect
        private val session = tree.session
        private val dialect = tree.negotiatedProtocol.dialect

        override fun start(position: Long, length: Int): Pipelined.Pending {
            val sent = session.send<SMB2ReadResponse>(SMB2ReadRequest(dialect, file.fileId, session.sessionId, tree.treeId, position, length))
            return Pipelined.Pending { timeoutMs ->
                val answer = try {
                    sent.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: ExecutionException) {
                    throw e.cause as? Exception ?: e
                }
                when (answer.header.statusCode) {
                    NtStatus.STATUS_SUCCESS.value -> answer.data
                    NtStatus.STATUS_END_OF_FILE.value -> ByteArray(0)
                    else -> throw SMBApiException(answer.header, "Read failed")
                }
            }
        }

        override fun room(length: Int): Int = room(credits(session.connection), length)
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
                in MISSING_STATUSES -> Known(RemoteException(RemoteException.Reason.MISSING, e.message, e))
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_ACCOUNT_DISABLED,
                NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED ->
                    Known(RemoteException(RemoteException.Reason.DENIED, e.message, e))
                NtStatus.STATUS_BAD_NETWORK_NAME -> Known(RemoteException(RemoteException.Reason.NO_SHARE, e.message, e))
                NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED,
                NtStatus.STATUS_NETWORK_SESSION_EXPIRED, NtStatus.STATUS_FILE_CLOSED -> Dropped(e)
                NtStatus.STATUS_DISK_FULL -> Known(ChangeRefused(ChangeRefused.Why.FULL, e.message))
                NtStatus.STATUS_SHARING_VIOLATION -> Known(ChangeRefused(ChangeRefused.Why.IN_USE, e.message))
                NtStatus.STATUS_OBJECT_NAME_COLLISION -> Known(ChangeRefused(ChangeRefused.Why.TAKEN, e.message))
                else -> Known(IOException(e.message, e))
            }
        }
        // Transport failures, timeouts and refused connections: the host is gone or the link dropped.
        return Dropped(e)
    }

    /** What [idleCheck] does to a connection. */
    internal enum class IdleAction { NONE, ECHO, CLOSE }

    /** A file, folder or link to delete, by its path inside the share. */
    internal data class Entry(val path: String, val isDirectory: Boolean, val isLink: Boolean)

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

        /** How long an echo of an unused connection gets: the host answers it without its disk, so a spin-up never delays it. */
        private const val ECHO_TIMEOUT_S = 10L

        /** How long a connection unheard for a while (the device slept) gets to answer before it is made anew. */
        private const val WAKE_ECHO_TIMEOUT_S = 2L

        /** How much one read asks for, at most: larger pieces measured slower than several of these in flight. */
        const val PIECE_BYTES = 1 shl 20

        /** How many reads of one file may be in flight at once. */
        const val DEPTH = 8

        /** Credits left for other requests (listings, echoes) while reads are in flight. */
        private const val SPARE_CREDITS = 16

        /** The bytes one credit pays for. */
        private const val CREDIT_BYTES = 64 * 1024

        /** SMBJ keeps the credits granted in a field of its own; read for [credits]. */
        private val WINDOW: Field? = runCatching { Connection::class.java.getDeclaredField("sequenceWindow").apply { isAccessible = true } }.getOrNull()

        /** The credits [c] has left now; null when SMBJ does not tell. */
        internal fun credits(c: Connection): Int? = runCatching { (WINDOW?.get(c) as? SequenceWindow)?.available() }.getOrNull()

        /**
         * How many more reads of [length] bytes fit in [credits] while keeping [SPARE_CREDITS]
         * (any number when unknown): one beyond them would wait on SMBJ, which gives up after
         * a few seconds, failing the read.
         */
        internal fun room(credits: Int?, length: Int): Int {
            credits ?: return Int.MAX_VALUE
            val charge = ((length.toLong() + CREDIT_BYTES - 1) / CREDIT_BYTES).toInt().coerceAtLeast(1)
            return ((credits - SPARE_CREDITS) / charge).coerceAtLeast(0)
        }

        /** How much a write sends at a time. */
        private const val WRITE_BYTES = 1 shl 20

        /** How much one server-side copy request copies, so progress shows and canceling is heard. */
        private const val COPY_BYTES = 16L shl 20

        /**
         * A connection's settings: its own client id, so the host never takes two connections
         * for one client; connecting and each request waited on for up to [answerMs].
         */
        internal fun config(answerMs: Long): SmbConfig =
            SmbConfig.builder()
                .withClientGuid(UUID.randomUUID())
                // Android's JCE lacks MD4 and AES-CMAC, which NTLM and SMB 3 signing need.
                .withSecurityProvider(BCSecurityProvider())
                // SPNEGO/Kerberos needs javax.security.auth.kerberos and org.ietf.jgss, absent on Android.
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withSocketFactory(ProxySocketFactory(answerMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                .withTimeout(answerMs, TimeUnit.MILLISECONDS)
                // No socket timeout: SMBJ's reader would take a quiet connection for a dead one
                // and drop it; answers are timed per request, and echoes find a dead link.
                .withSoTimeout(0, TimeUnit.SECONDS)
                .withSigningRequired(false)
                .withEncryptData(false)
                .withMultiProtocolNegotiate(false)
                .withDfsEnabled(false)
                .build()

        /**
         * True when a connection made with [madeMs] is made anew for [wantedMs]: only while
         * [underWay] counts the caller alone, as a write's later pieces or another thread's
         * read would fail on files of the closed one; until then it keeps [madeMs].
         */
        internal fun remake(connected: Boolean, madeMs: Long, wantedMs: Long, underWay: Int): Boolean =
            connected && madeMs != wantedMs && underWay <= 1

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

        /** Runs idle checks, each share's on its own thread while it waits on the network; threads go once unused. */
        private val checks: ExecutorService by lazy {
            Executors.newCachedThreadPool { r -> Thread(r, "smb-idle-check").apply { isDaemon = true } }
        }

        /** How to sign in to [share], in order: as its user, or without one anonymously, then as a guest. */
        internal fun authentications(share: Share): List<AuthenticationContext> =
            if (share.account.isEmpty()) {
                // Some hosts refuse anonymous sessions but take guests, others the other way round.
                listOf(AuthenticationContext.anonymous(), AuthenticationContext.guest())
            } else {
                listOf(AuthenticationContext(share.account, share.password.toCharArray(), share.domain))
            }

        /**
         * Makes a change with [block] on what [connect] gives. When the connection drops on
         * the way ([failed] says which failures are drops) the change may have been made all
         * the same: on a new connection it is made again only when [landed] does not find it
         * made, so a change made once never fails as repeated.
         */
        internal fun <D> changeOnce(connect: () -> D, failed: (Exception) -> Pair<IOException, Boolean>, landed: (D) -> Boolean, block: (D) -> Unit) {
            try {
                block(connect())
                return
            } catch (e: Exception) {
                val (error, dropped) = failed(e)
                if (!dropped) throw error
            }
            val d = connect()
            try {
                if (!landed(d)) block(d)
            } catch (e: Exception) {
                throw failed(e).first
            }
        }

        /** What a server answers for a path where nothing is. */
        private val MISSING_STATUSES = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_NOT_A_DIRECTORY
        )

        private fun isDirectory(attributes: Long) = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

        /** True for a reparse point: a junction, symbolic link or mount point. */
        private fun isLink(attributes: Long) = attributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L

        private fun entryOf(path: String, attributes: Long) = Entry(path, isDirectory(attributes), isLink(attributes))

        /**
         * Deletes [top] with everything inside, deepest first: a folder's entries from
         * [list], each by [remove], then the folder, emptied. A link is removed as itself,
         * never listed, so nothing it points to goes.
         */
        internal fun deleteTree(top: Entry, list: (String) -> List<Entry>, remove: (Entry) -> Unit) {
            if (top.isDirectory && !top.isLink) {
                for (e in list(top.path)) deleteTree(e, list, remove)
            }
            remove(top)
        }

        private fun millis(time: FileTime?): Long = time?.toEpochMillis()?.takeIf { it > 0 } ?: 0L
    }
}
