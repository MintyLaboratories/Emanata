package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Stored zip entries are read whole at their place in the file, the same bytes a stream gives. */
class ZipWholeReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    /** A zip of [entries], each stored, or deflated where [deflated] says. */
    private fun zip(name: String, entries: List<Pair<String, ByteArray>>, deflated: (String) -> Boolean = { false }): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, content) in entries) {
                val e = ZipEntry(path)
                if (!deflated(path)) {
                    e.method = ZipEntry.STORED
                    e.size = content.size.toLong()
                    e.compressedSize = content.size.toLong()
                    e.crc = CRC32().apply { update(content) }.value
                }
                out.putNextEntry(e)
                out.write(content)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun aStoredEntryReadWholeIsWhatItsStreamGives() {
        val pages = listOf("01.jpg" to bytes(300_000, 1), "dir/02.png" to bytes(5, 2), "03.webp" to ByteArray(0))
        ZipReader(zip("book.cbz", pages), "UTF-8").use { r ->
            for ((i, e) in r.entries.withIndex()) {
                val whole = r.readWhole(e)
                assertNotNull(e.path, whole)
                assertArrayEquals(e.path, pages[i].second, whole)
                assertArrayEquals(e.path, r.read(e) { it.readBytes() }, whole)
            }
        }
    }

    @Test
    fun aDeflatedEntryIsStreamed() {
        val pages = listOf("01.jpg" to bytes(1000, 3), "02.txt" to "text ".repeat(200).toByteArray())
        ZipReader(zip("book.cbz", pages, deflated = { it.endsWith(".txt") }), "UTF-8").use { r ->
            assertNotNull(r.readWhole(r.entries[0]))
            assertNull(r.readWhole(r.entries[1]))
        }
    }

    @Test
    fun manyThreadsReadEntriesWholeAtOnce() {
        val pages = (1..12).map { "%02d.jpg".format(it) to bytes(200_000 + it, it) }
        ZipReader(zip("book.cbz", pages), "UTF-8").use { r ->
            val pool = Executors.newFixedThreadPool(6)
            try {
                val reads = (0 until 60).map { n -> pool.submit<Pair<Int, ByteArray?>> { (n % 12) to r.readWhole(r.entries[n % 12]) } }
                for (f in reads) {
                    val (i, got) = f.get()
                    assertArrayEquals(pages[i].second, got)
                }
            } finally {
                pool.shutdown()
            }
        }
    }

    @Test
    fun theDirectoryTellsWhereEachEntryIsWithZip64Too() {
        val pages = listOf("a.jpg" to bytes(1234, 4), "b.jpg" to bytes(77, 5))
        val file = File(tmp.root, "z64.cbz")
        ZipArchiveOutputStream(file).use { out ->
            out.setUseZip64(Zip64Mode.Always)
            for ((path, content) in pages) {
                val e = ZipArchiveEntry(path)
                e.method = ZipEntry.STORED
                e.size = content.size.toLong()
                e.crc = CRC32().apply { update(content) }.value
                out.putArchiveEntry(e)
                out.write(content)
                out.closeArchiveEntry()
            }
        }
        val listed = RandomAccessFile(file, "r").use { ZipNames.entries(it.channel) }
        assertEquals(listOf(1234L, 77L), listed.map { it.size })
        assertEquals(listOf(1234L, 77L), listed.map { it.compressedSize })
        assertEquals(0L, listed[0].offset)
        assertEquals(listOf(0, 0), listed.map { it.method })
        ZipReader(file, "UTF-8").use { r -> assertArrayEquals(pages[1].second, r.readWhole(r.entries[1])) }
    }

    @Test
    fun aKnownSizeIsReadIntoOneArrayAndALongerStreamStillWhole() {
        val data = bytes(100_000, 6)
        assertArrayEquals(data, BitmapUtils.readCapped(ByteArrayInputStream(data), data.size.toLong()))
        assertArrayEquals(data, BitmapUtils.readCapped(ByteArrayInputStream(data), 40_000))
        assertArrayEquals(data, BitmapUtils.readCapped(ByteArrayInputStream(data), 150_000))
        assertArrayEquals(data, BitmapUtils.readCapped(ByteArrayInputStream(data)))
    }
}
