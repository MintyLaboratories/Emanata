package com.infinity.comicreader.reader

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reads a RAR (4 or 5), 7z or ZIP archive through libarchive ([LibArchive]), from a file
 * (its descriptor) or a channel; measured against junrar (F119), not used by the app yet.
 * libarchive reads headers in order: an entry at or before the last one read reopens the
 * archive and walks its headers again, and in a solid archive unpacks every entry before it.
 */
class LibArchiveReader private constructor(
    private val open: () -> Long,
    private val owned: Closeable,
    head: ByteArray
) : ArchiveReader {
    /** libarchive's name for the format, as the listing found it. */
    val format: String?

    override val entries: List<ArchiveEntry>

    /** Of a RAR, by its main header; null for other formats, where libarchive does not tell. */
    override val solid: Boolean? = RarMain.of(head)?.solid

    override val randomAccess: Boolean = solid != true

    /** Each entry's place among all headers, directories included. */
    private val ordinals: IntArray

    private val lock = ReentrantLock(true)
    private var handle = 0L

    /** The header the handle is on: -1 before the first. */
    private var current = -1

    init {
        try {
            handle = open()
            val meta = LongArray(3)
            val found = ArrayList<ArchiveEntry>()
            val places = ArrayList<Int>()
            var ordinal = 0
            while (true) {
                val name = LibArchive.nNext(handle, meta) ?: break
                if (meta[LibArchive.META_TYPE] == LibArchive.TYPE_FILE) {
                    found += ArchiveEntry(String(name, Charsets.UTF_8), meta[LibArchive.META_SIZE], found.size)
                    places += ordinal
                }
                ordinal++
            }
            format = LibArchive.nFormat(handle)
            entries = found
            ordinals = places.toIntArray()
            // At the end: the next read opens it anew.
            drop()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /** Puts the handle on header [ordinal], reopening when it is at or past it. */
    private fun moveTo(ordinal: Int) {
        if (handle == 0L || current >= ordinal) {
            drop()
            handle = open()
            current = -1
        }
        val meta = LongArray(3)
        while (current < ordinal) {
            LibArchive.nNext(handle, meta) ?: throw IOException("Entry $ordinal missing")
            current++
        }
    }

    /** Closes the handle; the next read opens the archive anew. */
    private fun drop() {
        if (handle != 0L) LibArchive.nClose(handle)
        handle = 0
    }

    /** Runs [block] on the handle, dropping it when [block] fails: libarchive reads no further after an error. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        drop()
        throw t
    }

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T = lock.withLock { guarded {
        moveTo(ordinals[entry.index])
        val h = handle
        block(object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                return LibArchive.nRead(h, b, off, len)
            }
        })
    } }

    /** The whole contents of [entry] in one native call. */
    fun bytes(entry: ArchiveEntry): ByteArray = lock.withLock { guarded {
        moveTo(ordinals[entry.index])
        LibArchive.nReadAll(handle, entry.size)
    } }

    override fun close() {
        lock.withLock { drop() }
        runCatching { owned.close() }
    }

    companion object {
        /** Reads [file] through its descriptor. */
        fun of(file: File): LibArchiveReader {
            check(LibArchive.available) { "libarchive not loaded" }
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                val head = file.inputStream().use { input ->
                    val bytes = ByteArray(RarMain.HEAD_BYTES)
                    var n = 0
                    while (n < bytes.size) {
                        val got = input.read(bytes, n, bytes.size - n)
                        if (got < 0) break
                        n += got
                    }
                    bytes.copyOf(n)
                }
                LibArchiveReader({ LibArchive.nOpenFd(pfd.fd) }, pfd, head)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        /** Reads through [channel], from its start each time it opens; the reader closes it. */
        fun of(channel: SeekableByteChannel): LibArchiveReader {
            check(LibArchive.available) { "libarchive not loaded" }
            return try {
                val head = ByteBuffer.allocate(RarMain.HEAD_BYTES)
                channel.position(0)
                while (head.hasRemaining() && channel.read(head) >= 0) Unit
                val source = LibArchive.ChannelSource(channel)
                LibArchiveReader({
                    channel.position(0)
                    LibArchive.nOpenSource(source)
                }, channel, head.array().copyOf(head.position()))
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw t
            }
        }
    }
}
