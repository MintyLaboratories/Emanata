package com.infinity.comicreader.util

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds bit-identical files: same size, then the same SHA-256 of their head and
 * tail, then of their whole content. Free of Android types.
 */
object Duplicates {

    /** Copies of one content, [size] bytes each, in natural order of their paths. */
    data class Group(val size: Long, val files: List<File>) {
        /** Bytes the extra copies take. */
        val wasted: Long get() = size * (files.size - 1)
    }

    private const val HASH = "SHA-256"
    private const val QUICK_BYTES = 64 * 1024

    /** The groups of two or more identical [files], most wasted space first; stops early once [alive] turns false. */
    fun find(files: Sequence<File>, alive: () -> Boolean = { true }): List<Group> {
        val bySize = files.filter { it.length() > 0 }.groupBy { it.length() }.filter { it.value.size > 1 }
        val groups = ArrayList<Group>()
        for ((size, same) in bySize) {
            if (!alive()) break
            // Cheap pre-check on head + tail, then a full checksum to be certain.
            val byQuick = same.groupBy { runCatching { quickHash(it) }.getOrNull() }
                .filter { it.key != null && it.value.size > 1 }
            for (candidates in byQuick.values) {
                candidates.groupBy { runCatching { fullHash(it) }.getOrNull() }
                    .filter { it.key != null && it.value.size > 1 }
                    .values
                    .forEach { groups.add(Group(size, it.sortedWith(compareBy(NaturalOrder) { f -> f.absolutePath }))) }
            }
        }
        groups.sortByDescending { it.wasted }
        return groups
    }

    /** SHA-256 of [f]'s first and last [QUICK_BYTES]. */
    fun quickHash(f: File): String {
        val md = MessageDigest.getInstance(HASH)
        val buf = ByteArray(QUICK_BYTES)
        RandomAccessFile(f, "r").use { raf ->
            var n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
            if (raf.length() > buf.size * 2L) {
                raf.seek(raf.length() - buf.size)
                n = raf.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    /** SHA-256 of all of [f]. */
    fun fullHash(f: File): String {
        val md = MessageDigest.getInstance(HASH)
        val buf = ByteArray(1 shl 20)
        f.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
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
