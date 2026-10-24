package com.infinity.comicreader.net

import java.io.File
import java.io.RandomAccessFile

/**
 * A share served from a local folder [dir], counting what is asked of it; [offline]
 * makes every call fail as an unreachable host would, [refuse] as a host turning the
 * sign-in down would, and [slow] holds every call until it opens, as a host slow to
 * answer would.
 */
class FakeRemoteFs(private val dir: File) : RemoteFs {
    @Volatile
    var offline = false

    @Volatile
    var slow: java.util.concurrent.CountDownLatch? = null

    @Volatile
    var refuse: RemoteException.Reason? = null

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

    private fun check() {
        slow?.await(10, java.util.concurrent.TimeUnit.SECONDS)
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

    override fun open(path: String): RemoteInput {
        check()
        val raf = RandomAccessFile(at(path), "r")
        return object : RemoteInput {
            override val length: Long = raf.length()

            override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                check()
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

    override fun close() {
        closed = true
    }

    companion object {
        /** A share named [name] with id [id], as the form would make it. */
        fun share(id: String = "s1", name: String = "Share A") =
            Share(id, name, SharePath("nas.local", SharePath.DEFAULT_PORT, "books", ""), "user", "secret")
    }
}
