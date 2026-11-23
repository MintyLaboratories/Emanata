package com.infinity.comicreader.reader

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.ServiceLoader

/**
 * What [RarReader] asks of libarchive: a handle walks a RAR's headers once, in order, from a
 * [LibArchive.Source]; [next] moves to the next header, [read] reads its data. Not thread-safe
 * per handle.
 */
internal interface ArchiveEngine {
    /** A handle reading [source] from its current position (its start). Throws IOException. */
    fun open(source: LibArchive.Source): Long

    /**
     * The next header's name as UTF-8 bytes, with [meta] = size (-1 unknown), file type, header
     * position, encrypted (1) or not (0), at the [LibArchive] META_ indexes; null at the end.
     * Throws IOException for a header that cannot be read.
     */
    fun next(handle: Long, meta: LongArray): ByteArray?

    /** Up to [len] bytes of the current entry into [dst] at [off]; -1 at its end. Throws IOException. */
    fun read(handle: Long, dst: ByteArray, off: Int, len: Int): Int

    fun close(handle: Long)
}

/** libarchive's RAR 4 and 5 readers (archive_jni.c, Libarchive.cmake) as an [ArchiveEngine]. */
internal object LibArchive : ArchiveEngine {
    /** False where the library cannot load (test JVMs). */
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("archivejni") }.isSuccess }

    /**
     * libarchive; where it cannot load (test JVMs), an engine registered as a service stands
     * in for it; null without either.
     */
    val engine: ArchiveEngine? by lazy {
        if (loaded) this else ServiceLoader.load(ArchiveEngine::class.java, ArchiveEngine::class.java.classLoader).firstOrNull()
    }

    /** Indexes of [next]'s meta array, and its length. */
    const val META_SIZE = 0
    const val META_TYPE = 1
    const val META_POSITION = 2
    const val META_ENCRYPTED = 3
    const val META_COUNT = 4

    /** The file types in [META_TYPE] of a regular file and a folder (libarchive's AE_IFREG, AE_IFDIR). */
    const val TYPE_FILE = 0x8000L
    const val TYPE_DIR = 0x4000L

    /** The most bytes a [Source] read hands over: the native buffer's size. */
    const val BLOCK_BYTES = 256 * 1024

    /** An archive read through native calls back, as libarchive's read, seek and skip callbacks. */
    interface Source {
        /** Fills [buffer] from index 0 with the next bytes; how many, -1 at the end. */
        fun read(buffer: ByteBuffer): Int

        /** Moves to [offset] from the start ([whence] 0), the position (1) or the end (2); the new position. */
        fun seek(offset: Long, whence: Int): Long

        /** Moves forward up to [bytes], never past the end; how far it moved. */
        fun skip(bytes: Long): Long
    }

    /**
     * [channel] as a [Source], each read taking at most [chunk] bytes; closing stays with its
     * owner. A small [chunk] keeps a channel on a share from fetching a page's worth for a header.
     */
    class ChannelSource(private val channel: SeekableByteChannel, @Volatile var chunk: Int = BLOCK_BYTES) : Source {
        override fun read(buffer: ByteBuffer): Int {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity(), chunk.coerceAtLeast(1)))
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

    override fun open(source: Source): Long = nOpen(source)

    override fun next(handle: Long, meta: LongArray): ByteArray? = nNext(handle, meta)

    override fun read(handle: Long, dst: ByteArray, off: Int, len: Int): Int = nRead(handle, dst, off, len)

    override fun close(handle: Long) = nClose(handle)

    /** libarchive's version and those of its compression libraries. */
    @JvmStatic external fun nVersion(): String

    @JvmStatic private external fun nOpen(source: Source): Long

    @JvmStatic private external fun nNext(handle: Long, meta: LongArray): ByteArray?

    @JvmStatic private external fun nRead(handle: Long, dst: ByteArray, off: Int, len: Int): Int

    @JvmStatic private external fun nClose(handle: Long)
}
