package com.infinity.comicreader.net

import java.io.Closeable
import java.io.IOException

/**
 * One network share's files. Paths are relative to the share's folder, `/`-separated,
 * empty for that folder itself. Every call is blocking network work.
 */
interface RemoteFs : Closeable {
    /** The entries of folder [path], without `.` and `..`. */
    fun list(path: String): List<RemoteStat>

    /** What [path] is; null when nothing is there. */
    fun stat(path: String): RemoteStat?

    /** Opens the file [path] for reading at any position. */
    fun open(path: String): RemoteInput

    /** Free and total bytes of the share; null when it does not say. */
    fun space(): Pair<Long, Long>?
}

/** A file or folder on a share: sizes in bytes, times in milliseconds since the epoch (0 = unknown). */
data class RemoteStat(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long, val created: Long)

/** An open file on a share, read at any position. */
interface RemoteInput : Closeable {
    val length: Long

    /** Reads up to [count] bytes at [position] into [buffer] from [offset]; -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int
}

/** A network failure, by what the user can do about it. */
class RemoteException(val reason: Reason, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: reason.name, cause) {
    enum class Reason {
        /** The host could not be reached: offline, wrong address, or it is off. */
        UNREACHABLE,

        /** The host turned the user name or password down, or denied access. */
        DENIED,

        /** The share named is not on the host. */
        NO_SHARE,

        /** The share was removed from the app. */
        UNKNOWN_SHARE
    }
}
