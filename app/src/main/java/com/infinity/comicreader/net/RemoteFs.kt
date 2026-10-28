package com.infinity.comicreader.net

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

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

    /**
     * False when [path] can be read only front to back, so a read behind or far ahead
     * fetches it again from its start: books there are fetched whole instead.
     */
    fun seekable(path: String): Boolean = true

    /** Free and total bytes of the share; null when it does not say. */
    fun space(): Pair<Long, Long>?

    /** Forgets a failure it remembered, so the next call tries the network again. */
    fun retry() {}

    /** Makes folder [path]; fails when anything is there. */
    fun mkdir(path: String): Unit = throw readOnly()

    /**
     * Writes [length] bytes from [input] as the new file [path]; fails when anything is
     * there. A failure may leave part of the file behind, for the caller to delete.
     * Failures of [input] itself, and cancellation, pass through as they are.
     */
    fun create(path: String, input: InputStream, length: Long): Unit = throw readOnly()

    /** Renames or moves [from] to [to] on this share; fails when anything is at [to]. */
    fun rename(from: String, to: String): Unit = throw readOnly()

    /**
     * Deletes the file or folder [path], with everything inside; a link inside (a junction
     * or symbolic link) goes itself, never what it points to.
     */
    fun delete(path: String): Unit = throw readOnly()

    /** Deletes the file, link or empty folder [path]; fails on a folder with anything inside. */
    fun remove(path: String): Unit = throw readOnly()

    /**
     * Copies file [from] to the new file [to] on the server itself, telling [progress] the
     * bytes copied so far; [progress] may throw to stop. False when the server cannot, so
     * the caller copies through the device instead.
     */
    fun copy(from: String, to: String, progress: (Long) -> Unit): Boolean = false
}

private fun readOnly() = ChangeRefused(ChangeRefused.Why.READ_ONLY)

/** A change the share turned down, by [why]; the share itself stays reachable. */
class ChangeRefused(val why: Why, message: String? = null) : IOException(message ?: why.name) {
    enum class Why {
        /** The share takes no changes. */
        READ_ONLY,

        /** No room left. */
        FULL,

        /** Another program has the item open, or locked it. */
        IN_USE,

        /** Something is there by that name. */
        TAKEN
    }
}

/**
 * A file or folder on a share: sizes in bytes (-1 for a file the server lists without
 * one), times in milliseconds since the epoch (0 = unknown); [isLink] for a junction or
 * symbolic link the server tells of.
 */
data class RemoteStat(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val created: Long,
    val isLink: Boolean = false
)

/** An open file on a share, read at any position. */
interface RemoteInput : Closeable {
    val length: Long

    /** Reads up to [count] bytes at [position] into [buffer] from [offset]; -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int

    /**
     * Reads [count] bytes at [position] into [buffer] from [offset], asking for all of them
     * at once (several requests in flight where the share allows), [heard] told as each
     * part arrives; fewer only at the end, or when the link failed part way (the next read
     * tries again); -1 at the end. For long reads that are sure to be used.
     */
    fun readFully(position: Long, buffer: ByteArray, offset: Int, count: Int, heard: () -> Unit = {}): Int {
        var n = 0
        while (n < count) {
            val r = try {
                read(position + n, buffer, offset + n, count - n)
            } catch (e: IOException) {
                if (n > 0) break
                throw e
            }
            if (r < 0) break
            n += r
            heard()
        }
        return if (n == 0 && count > 0) -1 else n
    }
}

/**
 * A network failure, by what the user can do about it. A [lasting] one (a sign-in or
 * share the host turned down) holds until the share is edited or the user retries:
 * trying again unasked could lock the account.
 */
class RemoteException(
    val reason: Reason,
    message: String? = null,
    cause: Throwable? = null,
    val lasting: Boolean = false
) : IOException(message ?: reason.name, cause) {
    enum class Reason {
        /** The host could not be reached: offline, wrong address, or it is off. */
        UNREACHABLE,

        /** The host turned the user name or password down, or denied access. */
        DENIED,

        /** The share named is not on the host. */
        NO_SHARE,

        /** Nothing is at the path on the share, or not what was asked for (a file listed as a folder). */
        MISSING,

        /** The share was removed from the app. */
        UNKNOWN_SHARE
    }
}
