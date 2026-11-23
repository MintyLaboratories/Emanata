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
import java.util.zip.CRC32

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

    /** [v] as RAR 5 variable-length integers. */
    private fun vints(vararg v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (x in v) {
            var rest = x
            while (rest ushr 7 != 0L) {
                out.write(((rest and 0x7F) or 0x80).toInt())
                rest = rest ushr 7
            }
            out.write(rest.toInt())
        }
        return out.toByteArray()
    }

    /** A block with [body] behind its size, under a valid CRC. */
    private fun block(body: ByteArray): ByteArray {
        val sized = vints(body.size.toLong()) + body
        val crc = CRC32().apply { update(sized) }.value
        return ByteArray(4) { (crc ushr (8 * it)).toByte() } + sized
    }

    /** A RAR 5 archive: signature, main header, then a file header whose [lead] fields come before its file fields and whose [extra] area ends it. */
    private fun withFileHeader(lead: LongArray, extra: ByteArray = ByteArray(0)): ByteArray {
        val sig = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        // File flags, unpacked size, attributes, compression, host OS, name length, name.
        val file = vints(*lead) + vints(0, 1, 0x20, 0, 0, 1) + "a".toByteArray() + extra
        return sig + block(vints(1, 0, 0)) + block(file) + block(vints(5, 0, 0))
    }

    @Test
    fun headerFieldsOutOfBoundsAreDamage() {
        // Type 2; header flags: 1 = extra area, 2 = data area; then their sizes.
        val extraTooLong = withFileHeader(longArrayOf(2, 1, 100))
        val recordTooLong = withFileHeader(longArrayOf(2, 1, 3), vints(1000, 7))
        val recordEmpty = withFileHeader(longArrayOf(2, 1, 2), vints(0, 7))
        val nameTooLong = withFileHeader(longArrayOf(2, 0)).let { b ->
            // Name length 1 -> 2^40: still a valid CRC, the name runs past the header.
            val sig = b.copyOf(8)
            sig + block(vints(1, 0, 0)) + block(vints(2, 0, 0, 1, 0x20, 0, 0, 1L shl 40) + "a".toByteArray())
        }
        for ((name, bytes) in listOf("extra" to extraTooLong, "record" to recordTooLong, "empty record" to recordEmpty, "name" to nameTooLong)) {
            assertThrows(name, IOException::class.java) { list(bytes) }
        }
        // In bounds, the same shapes list.
        assertEquals(listOf("a"), list(withFileHeader(longArrayOf(2, 1, 2), vints(1, 7))).map { String(it.name) })
        // A data area past the end is a cut archive: its header is listed, and is the last.
        assertEquals(listOf("a"), list(withFileHeader(longArrayOf(2, 2, Long.MAX_VALUE - 10))).map { String(it.name) })
    }

    @Test
    fun aSolidArchiveCutInAnEntrysDataListsThatEntryLast() {
        val bytes = Fixtures.rar5Bytes(entry("01.jpg", ByteArray(500)), entry("02.jpg", ByteArray(500)), entry("03.jpg", ByteArray(500)), solid = true)
        val starts = list(bytes).map { it.start.toInt() }
        assertEquals(listOf("01.jpg", "02.jpg"), list(bytes.copyOf(starts[2] - 100)).map { String(it.name) })
    }
}
