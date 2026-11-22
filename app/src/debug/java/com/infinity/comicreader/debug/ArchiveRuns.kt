package com.infinity.comicreader.debug

import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The archive benchmark's plan and comparisons, apart from the device ([ArchiveCheckService]). */
internal object ArchiveRuns {
    /** Who reads: the app's reader for the format (junrar for RAR, Commons Compress for 7z, its own for ZIP) or libarchive. */
    enum class Engine(val key: String) {
        CURRENT("junrar"),
        LIBARCHIVE("libarchive");

        companion object {
            /** By [key]; "current" also names the app's reader. */
            fun of(key: String): Engine? = key.trim().lowercase().let { k -> if (k == "current") CURRENT else entries.firstOrNull { it.key == k } }
        }
    }

    /** How the archive is read: its file (a descriptor for libarchive), or a channel, as books on a share are. */
    enum class Via(val key: String) {
        FILE("file"),
        CHANNEL("channel");

        companion object {
            /** By [key]; "both" names both. */
            fun list(key: String?): List<Via>? = when (key?.trim()?.lowercase()) {
                null, "file" -> listOf(FILE)
                "channel" -> listOf(CHANNEL)
                "both" -> listOf(FILE, CHANNEL)
                else -> null
            }
        }
    }

    /** The pages a reader shows, in its order: image entries by natural order of their paths. */
    fun pages(entries: List<ArchiveEntry>): List<ArchiveEntry> =
        entries.filter { Formats.isImageName(it.path) }.sortedWith(compareBy(NaturalOrder) { it.path })

    /**
     * [take] distinct indexes below [count] in a pseudo-random order fixed by [seed], the same on
     * every device and JVM: a Fisher-Yates shuffle driven by SplitMix64.
     */
    fun randomOrder(count: Int, take: Int, seed: Long): List<Int> {
        val order = IntArray(count) { it }
        var state = seed
        for (i in count - 1 downTo 1) {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            val j = java.lang.Long.remainderUnsigned(z, (i + 1).toLong()).toInt()
            val t = order[i]
            order[i] = order[j]
            order[j] = t
        }
        return order.take(take.coerceIn(0, count))
    }

    /** How listing [b] differs from listing [a]: paths in one only, sizes that differ, and whether the shared paths come in the same order. */
    data class ListingDiff(val onlyA: Int, val onlyB: Int, val sizeDiffers: Int, val sameOrder: Boolean) {
        val same: Boolean get() = onlyA == 0 && onlyB == 0 && sizeDiffers == 0 && sameOrder
    }

    fun compare(a: List<ArchiveEntry>, b: List<ArchiveEntry>): ListingDiff {
        val bySizeA = a.associate { it.path to it.size }
        val bySizeB = b.associate { it.path to it.size }
        val shared = a.map { it.path }.filter { it in bySizeB }
        val sizeDiffers = shared.count { bySizeA[it] != bySizeB[it] }
        return ListingDiff(
            onlyA = a.count { it.path !in bySizeB },
            onlyB = b.count { it.path !in bySizeA },
            sizeDiffers = sizeDiffers,
            sameOrder = shared == b.map { it.path }.filter { it in bySizeA }
        )
    }

    /** Entries for the self-test archives: compressible and incompressible bytes, some empty, made from [seed]. */
    fun selfTestEntries(seed: Long = 1): List<Pair<String, ByteArray>> {
        val random = java.util.Random(seed)
        fun noise(n: Int) = ByteArray(n).also { random.nextBytes(it) }
        val line = "a line of a test page\n"
        fun text(n: Int) = ByteArray(n) { i -> line[i % line.length].code.toByte() }
        return listOf(
            "001.jpg" to noise(150_000),
            "002.png" to text(300_000),
            "empty.txt" to ByteArray(0),
            "sub/003.jpg" to noise(70_001),
            "sub/004.webp" to text(1),
            "005.jpg" to noise(1_200_000)
        )
    }

    /** A ZIP of [entries], text entries deflated and the rest stored, with the archive [comment]. */
    fun writeZip(file: File, entries: List<Pair<String, ByteArray>>, comment: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setComment(comment)
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name.endsWith(".jpg")) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** A 7z of [entries], LZMA2-compressed (Commons Compress's default). */
    fun write7z(file: File, entries: List<Pair<String, ByteArray>>) {
        SevenZOutputFile(file).use { out ->
            for ((name, bytes) in entries) {
                val entry = out.createArchiveEntry(File(name), name)
                entry.size = bytes.size.toLong()
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }
}
