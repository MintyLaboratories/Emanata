package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Fixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class Rar5HeadersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun list(bytes: ByteArray, found: MutableList<Rar5Headers.Header> = ArrayList()): List<Rar5Headers.Header> {
        val f = File(tmp.newFolder(), "a.cbr").apply { writeBytes(bytes) }
        return FileChannel.open(f.toPath(), StandardOpenOption.READ).use { Rar5Headers.list(it, found) }
    }

    @Test
    fun aRealArchivesHeadersPastItsLocatorAndServiceHeader() {
        // Made by RAR itself: compressed entries, a main header with a locator record and a quick-open service header before the end.
        val bytes = javaClass.getResourceAsStream("/archive/rar5.cbr")!!.use { it.readBytes() }
        val headers = list(bytes)
        assertEquals((1..5).map { "00$it.png" }, headers.map { String(it.name) })
        assertEquals(listOf(15654L, 15870, 16117, 16006, 15973), headers.map { it.size })
        assertTrue(headers.all { it.file })
        assertEquals(25L, headers.first().start)
    }

    @Test
    fun aRealSolidArchivesHeaders() {
        // From the CC0 RAR test files (resources/rar/README.md): its second entry uses the first's data.
        val bytes = javaClass.getResourceAsStream("/rar/testfile.rar5.solid.cbr")!!.use { it.readBytes() }
        assertEquals(RarMain(5, true, 23), RarMain.of(bytes.copyOf(RarMain.HEAD_BYTES)))
        val headers = list(bytes)
        assertEquals(listOf("testfile.jpg", "testfile.png"), headers.map { String(it.name) })
        assertEquals(listOf(220L, 87L), headers.map { it.size })
        assertEquals(listOf(23L, 298L), headers.map { it.start })
        assertTrue(headers.all { it.file })
    }

    @Test
    fun fileHeadersInOrderWithWhereTheyStart() {
        val bytes = Fixtures.rar5Bytes(entry("b/02.jpg", ByteArray(300)), entry("a/01.jpg", ByteArray(5)), solid = true)
        val headers = list(bytes)
        assertEquals(listOf("b/02.jpg", "a/01.jpg"), headers.map { String(it.name) })
        assertEquals(listOf(300L, 5L), headers.map { it.size })
        // Main header: CRC, size and 3 bytes after the signature.
        assertEquals(16L, headers[0].start)
        assertTrue(headers[1].start > headers[0].start + 300)
    }

    @Test
    fun encryptionIsTurnedDown() {
        assertThrows(RefusedRar::class.java) { list(Fixtures.rar5Bytes(entry("01.jpg"), encrypted = true)) }
        assertThrows(RefusedRar::class.java) { list(Fixtures.rar5Bytes(entry("01.jpg"), headersEncrypted = true)) }
    }

    @Test
    fun theDictionaryIsDecodedFromTheCompressionInformation() {
        assertEquals(128L * 1024, Rar5Headers.dictOf(0))
        assertEquals(32L shl 20, Rar5Headers.dictOf(8L shl 10))
        // RAR 7 (version 1): 5 bits of size, then 1/32 steps; RAR 5.0 has no fraction.
        assertEquals(4L shl 30, Rar5Headers.dictOf(15L shl 10 or 1))
        assertEquals((64L shl 20) + (64L shl 20) / 32 * 3, Rar5Headers.dictOf(3L shl 15 or (9L shl 10) or 1))
        assertEquals(64L shl 20, Rar5Headers.dictOf(3L shl 15 or (9L shl 10)))
        val headers = list(Fixtures.rar5Bytes(entry("01.jpg"), solid = true, dictBits = 9))
        assertEquals(listOf(64L shl 20), headers.map { it.dict })
    }

    @Test
    fun aDictionaryOver64MiBIsTurnedDown() {
        val e = assertThrows(RefusedRar::class.java) { list(Fixtures.rar5Bytes(entry("01.jpg"), solid = true, dictBits = 10)) }
        assertEquals("Dictionary over 64 MiB", e.message)
    }

    @Test
    fun aDamagedOrCutHeaderStopsTheListingKeepingThoseBefore() {
        val bytes = Fixtures.rar5Bytes(entry("01.jpg"), entry("02.jpg"), entry("03.jpg"))
        val starts = list(bytes).map { it.start.toInt() }
        val damaged = bytes.copyOf().also { it[starts[2] + 6] = (it[starts[2] + 6] + 1).toByte() }
        val found = ArrayList<Rar5Headers.Header>()
        assertThrows(IOException::class.java) { list(damaged, found) }
        assertEquals(listOf("01.jpg", "02.jpg"), found.map { String(it.name) })
        found.clear()
        assertThrows(IOException::class.java) { list(bytes.copyOf(starts[1] + 3), found) }
        assertEquals(listOf("01.jpg"), found.map { String(it.name) })
    }
}
