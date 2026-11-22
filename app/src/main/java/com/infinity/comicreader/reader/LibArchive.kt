package com.infinity.comicreader.reader

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * libarchive's readers (archive_jni.c, Libarchive.cmake): RAR 4 and 5, 7z and ZIP, from a
 * file descriptor or a [Source]. A handle walks the archive's headers once, in order: [nNext]
 * moves to the next header, [nRead] and [nReadAll] read its data. Not thread-safe per handle.
 */
internal object LibArchive {
    /** False where the library cannot load (test JVMs). */
    val available: Boolean by lazy { runCatching { System.loadLibrary("archivejni") }.isSuccess }

    /** Indexes of [nNext]'s meta array. */
    const val META_SIZE = 0
    const val META_TYPE = 1
    const val META_POSITION = 2

    /** The file type of a regular file in [META_TYPE] (libarchive's AE_IFREG). */
    const val TYPE_FILE = 0x8000L

    /** An archive read through native calls back, as libarchive's read, seek and skip callbacks. */
    interface Source {
        /** Fills [buffer] from index 0 with the next bytes; how many, -1 at the end. */
        fun read(buffer: ByteBuffer): Int

        /** Moves to [offset] from the start ([whence] 0), the position (1) or the end (2); the new position. */
        fun seek(offset: Long, whence: Int): Long

        /** Moves forward up to [bytes], never past the end; how far it moved. */
        fun skip(bytes: Long): Long
    }

    /** [channel] as a [Source]; closing stays with its owner. */
    class ChannelSource(private val channel: SeekableByteChannel) : Source {
        override fun read(buffer: ByteBuffer): Int {
            buffer.clear()
            while (true) {
                val n = channel.read(buffer)
                if (n != 0) return n
                // A blocking channel returns 0 only when the buffer is full; it never is here.
                if (!buffer.hasRemaining()) return 0
            }
        }

        override fun seek(offset: Long, whence: Int): Long {
            val target = when (whence) {
                SEEK_SET -> offset
                SEEK_CUR -> channel.position() + offset
                SEEK_END -> channel.size() + offset
                else -> throw IOException("Bad whence: $whence")
            }
            if (target < 0) throw IOException("Seek before the start: $target")
            channel.position(target)
            return target
        }

        override fun skip(bytes: Long): Long {
            val at = channel.position()
            val n = bytes.coerceIn(0, (channel.size() - at).coerceAtLeast(0))
            channel.position(at + n)
            return n
        }
    }

    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2

    /** libarchive's version and those of its compression libraries. */
    @JvmStatic external fun nVersion(): String

    /** A handle reading [fd] from its start; the fd stays open and owned by the caller. Throws IOException. */
    @JvmStatic external fun nOpenFd(fd: Int): Long

    /** A handle reading [source] from its current position (its start). Throws IOException. */
    @JvmStatic external fun nOpenSource(source: Source): Long

    /** The next header's name as UTF-8 bytes, with [meta] = size (-1 unknown), file type, header position; null at the end. */
    @JvmStatic external fun nNext(handle: Long, meta: LongArray): ByteArray?

    /** Up to [len] bytes of the current entry into [dst] at [off]; -1 at its end. */
    @JvmStatic external fun nRead(handle: Long, dst: ByteArray, off: Int, len: Int): Int

    /** The rest of the current entry, which holds [size] bytes (-1 when unknown). */
    @JvmStatic external fun nReadAll(handle: Long, size: Long): ByteArray

    /** The format's name ("RAR5", "7-Zip", "ZIP 2.0 (deflation)"...), once a header was read. */
    @JvmStatic external fun nFormat(handle: Long): String?

    @JvmStatic external fun nClose(handle: Long)
}
