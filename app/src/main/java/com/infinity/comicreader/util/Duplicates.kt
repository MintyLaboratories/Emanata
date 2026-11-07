package com.infinity.comicreader.util

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds bit-identical files: same size, then the same SHA-256 of their head and
 * tail, then of their whole content. Files are read through [Bytes], so files on
 * shares can be too. Free of Android types.
 */
object Duplicates {

    /** A file's bytes, read at any position. */
    interface Bytes : Closeable {
        val length: Long

        /** Reads up to [count] bytes at [position] into [buffer] from [offset]; -1 at the end. */
        fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int
    }

    /** [f] on the device. */
    fun local(f: File): Bytes = object : Bytes {
        private val raf = RandomAccessFile(f, "r")
        override val length: Long get() = raf.length()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            raf.seek(position)
            return raf.read(buffer, offset, count)
        }

        override fun close() = raf.close()
    }

    /** Copies of one content, [size] bytes each, in natural order of their paths. */
    data class Group(val size: Long, val files: List<File>) {
        /** Bytes the extra copies take. */
        val wasted: Long get() = size * (files.size - 1)
    }

    private const val HASH = "SHA-256"
    private const val QUICK_BYTES = 64 * 1024

    /**
     * The groups of two or more identical [files], read through [open], most wasted space
     * first; stops early once [alive] turns false. A file [open] fails for is left out.
     * [progress] hears files checked of those sharing a size with another.
     */
    fun find(
        files: Sequence<File>,
        open: (File) -> Bytes = ::local,
        alive: () -> Boolean = { true },
        progress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Group> {
        val bySize = files.filter { it.length() > 0 }.groupBy { it.length() }.filter { it.value.size > 1 }
        val total = bySize.values.sumOf { it.size }
        var done = 0
        progress(0, total)
        val groups = ArrayList<Group>()
        for ((size, same) in bySize) {
            if (!alive()) break
            // Cheap pre-check on head + tail, then a full checksum to be certain.
            val byQuick = same.groupBy { if (alive()) runCatching { quickHash(it, open) }.getOrNull() else null }
                .filter { it.key != null && it.value.size > 1 }
            for (candidates in byQuick.values) {
                candidates.groupBy { runCatching { fullHash(it, open, alive) }.getOrNull() }
                    .filter { it.key != null && it.value.size > 1 }
                    .values
                    .forEach { groups.add(Group(size, it.sortedWith(compareBy(NaturalOrder) { f -> f.absolutePath }))) }
            }
            done += same.size
            progress(done, total)
        }
        groups.sortByDescending { it.wasted }
        return groups
    }

    /** SHA-256 of [f]'s first and last [QUICK_BYTES]; of all of it when that is no more than both. */
    fun quickHash(f: File, open: (File) -> Bytes = ::local): String {
        val md = MessageDigest.getInstance(HASH)
        val buf = ByteArray(QUICK_BYTES)
        open(f).use { bytes ->
            val length = bytes.length
            if (length <= buf.size * 2L) {
                digest(bytes, 0, length, buf, md) { true }
            } else {
                digest(bytes, 0, buf.size.toLong(), buf, md) { true }
                digest(bytes, length - buf.size, buf.size.toLong(), buf, md) { true }
            }
        }
        return hex(md.digest())
    }

    /** SHA-256 of all of [f]; throws once [alive] turns false. */
    fun fullHash(f: File, open: (File) -> Bytes = ::local, alive: () -> Boolean = { true }): String {
        val md = MessageDigest.getInstance(HASH)
        val buf = ByteArray(1 shl 20)
        open(f).use { bytes -> digest(bytes, 0, bytes.length, buf, md, alive) }
        return hex(md.digest())
    }

    /** Adds [count] bytes of [bytes] at [from] to [md] through [buf]; throws when they end early or [alive] turns false. */
    private fun digest(bytes: Bytes, from: Long, count: Long, buf: ByteArray, md: MessageDigest, alive: () -> Boolean) {
        var at = from
        val end = from + count
        while (at < end) {
            if (!alive()) throw EOFException("Stopped")
            val n = bytes.read(at, buf, 0, minOf(buf.size.toLong(), end - at).toInt())
            if (n <= 0) throw EOFException("Ended at $at of $end")
            md.update(buf, 0, n)
            at += n
        }
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}
