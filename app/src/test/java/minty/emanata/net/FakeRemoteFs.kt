package minty.emanata.net

import minty.emanata.testing.Waits
import java.io.File
import java.io.RandomAccessFile

/**
 * A share served from a local folder [dir], counting what is asked of it; [offline]
 * makes every call fail as an unreachable host would, [refuse] as a host turning the
 * sign-in down would, and [slow] holds every call until it opens, as a host slow to
 * answer would. Changes go to [dir], unless [readOnly] turns them down.
 */
class FakeRemoteFs(private val dir: File) : RemoteFs {
    @Volatile
    var offline = false

    @Volatile
    var slow: java.util.concurrent.CountDownLatch? = null

    /** When set, [slow] holds only reads of bytes in it, as a disk spun down under part of a file would. */
    @Volatile
    var slowReads: LongRange? = null

    /** How long [slow] holds a call at most, in real time: only so a test that never opens it still ends. */
    @Volatile
    var slowCapMs = Waits.LIVENESS_MS

    /** Calls [slow] holds now. */
    val held = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    var refuse: RemoteException.Reason? = null

    @Volatile
    var readOnly = false

    /** Fails each write after this many bytes as a dropped network would, going [offline]; never when negative. */
    @Volatile
    var dropWritesAfter = -1L

    /** Calls made, offline ones too. */
    @Volatile
    var tries = 0
        private set

    /** Calls that reached the host, failed or not. */
    @Volatile
    var attempts = 0
        private set

    @Volatile
    var lists = 0
        private set

    @Volatile
    var stats = 0
        private set

    @Volatile
    var bytesRead = 0L
        private set

    @Volatile
    var closed = false
        private set

    private fun at(path: String) = if (path.isEmpty()) dir else File(dir, path)

    /** Holds the call while [slow] does, unless it reads [count] bytes at [position] outside [slowReads]; then fails as set. */
    private fun check(position: Long = -1, count: Int = 0) {
        val reads = slowReads
        val holds = reads == null || position >= 0 && position <= reads.last && position + count > reads.first
        slow?.takeIf { holds }?.let {
            held.incrementAndGet()
            try {
                it.await(slowCapMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally {
                held.decrementAndGet()
            }
        }
        tries++
        if (offline) throw RemoteException(RemoteException.Reason.UNREACHABLE)
        attempts++
        refuse?.let { throw RemoteException(it, lasting = true) }
    }

    private fun statOf(f: File) = RemoteStat(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), f.lastModified() - 1000)

    override fun list(path: String): List<RemoteStat> {
        check()
        lists++
        return at(path).listFiles()?.map { statOf(it) } ?: throw RemoteException(RemoteException.Reason.MISSING, "Not a folder: $path")
    }

    override fun stat(path: String): RemoteStat? {
        check()
        stats++
        return at(path).takeIf { it.exists() }?.let { statOf(it) }
    }

    /** False to act as a server sending files only whole ([RemoteFs.seekable]). */
    var seekable = true

    override fun seekable(path: String): Boolean = seekable

    override fun open(path: String): RemoteInput {
        check()
        val raf = RandomAccessFile(at(path), "r")
        return object : RemoteInput {
            override val length: Long = raf.length()

            override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                check(position, count)
                synchronized(raf) {
                    raf.seek(position)
                    val n = raf.read(buffer, offset, count)
                    if (n > 0) bytesRead += n
                    return n
                }
            }

            override fun close() = raf.close()
        }
    }

    override fun space(): Pair<Long, Long>? {
        check()
        return 3_000_000_000L to 8_000_000_000L
    }

    private fun checkWrite() {
        check()
        if (readOnly) throw RemoteException(RemoteException.Reason.DENIED, "Read-only")
    }

    override fun mkdir(path: String) {
        checkWrite()
        if (!at(path).mkdir()) throw java.io.IOException("Could not make $path")
    }

    override fun create(path: String, input: java.io.InputStream, length: Long) {
        checkWrite()
        val f = at(path)
        if (!f.createNewFile()) throw java.io.IOException("Already there: $path")
        val limit = dropWritesAfter
        if (limit >= 0) {
            f.outputStream().use { out -> out.write(input.readNBytes(limit.toInt())) }
            offline = true
            throw RemoteException(RemoteException.Reason.UNREACHABLE, "dropped")
        }
        f.outputStream().use { input.copyTo(it) }
    }

    override fun rename(from: String, to: String) {
        checkWrite()
        if (at(to).exists()) throw java.io.IOException("Already there: $to")
        if (!at(from).renameTo(at(to))) throw java.io.IOException("Could not rename $from")
    }

    override fun delete(path: String) {
        checkWrite()
        if (!at(path).exists()) throw RemoteException(RemoteException.Reason.MISSING)
        if (!at(path).deleteRecursively()) throw java.io.IOException("Could not delete $path")
    }

    override fun remove(path: String) {
        checkWrite()
        if (!at(path).exists()) throw RemoteException(RemoteException.Reason.MISSING)
        if (!at(path).delete()) throw java.io.IOException("Could not delete $path")
    }

    override fun close() {
        closed = true
    }

    companion object {
        /** A share named [name] with id [id], as the form would make it. */
        fun share(id: String = "s1", name: String = "Share A") =
            Share(id, name, SharePath("nas.local", SharePath.DEFAULT_PORT, "books", ""), "user", "secret")
    }
}
