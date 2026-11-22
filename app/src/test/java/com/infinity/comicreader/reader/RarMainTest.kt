package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RarMainTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val rar4 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val rar5 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    @Test
    fun rar4SolidFlagIsReadFromTheMainHeaderFlags() {
        // HEAD_CRC, HEAD_TYPE 0x73, HEAD_FLAGS, HEAD_SIZE
        assertEquals(RarMain(4, true), RarMain.of(rar4 + bytes(0x12, 0x34, 0x73, 0x08, 0x00, 0x0D, 0x00)))
        assertEquals(RarMain(4, false), RarMain.of(rar4 + bytes(0x12, 0x34, 0x73, 0x00, 0x01, 0x0D, 0x00)))
        // Volume (0x0001) and locked (0x0004) flags are not solid.
        assertEquals(RarMain(4, false), RarMain.of(rar4 + bytes(0x12, 0x34, 0x73, 0x05, 0x00, 0x0D, 0x00)))
    }

    @Test
    fun rar5SolidFlagIsReadFromTheArchiveFlags() {
        // CRC32, header size, type 1, header flags 0, archive flags
        assertEquals(RarMain(5, true), RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x03, 0x01, 0x00, 0x04)))
        assertEquals(RarMain(5, false), RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x03, 0x01, 0x00, 0x01)))
        // An extra area size and a data size come before the archive flags.
        assertEquals(RarMain(5, true), RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x06, 0x01, 0x03, 0x88, 0x01, 0x02, 0x04)))
        // A multi-byte header size.
        assertEquals(RarMain(5, true), RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x80, 0x01, 0x01, 0x00, 0x0C)))
    }

    @Test
    fun theFixtureIsANonSolidRar5() {
        val head = javaClass.getResourceAsStream("/archive/rar5.cbr")!!.use { it.readNBytes(RarMain.HEAD_BYTES) }
        assertEquals(RarMain(5, false), RarMain.of(head))
    }

    @Test
    fun otherDataIsNoRar() {
        assertNull(RarMain.of(ByteArray(0)))
        assertNull(RarMain.of(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertNull(RarMain.of(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x02, 0x00, 1, 2, 3, 4, 3, 1, 0, 4)))
        // Cut short, or a first block that is not the main header.
        assertNull(RarMain.of(rar4 + bytes(0x12, 0x34)))
        assertNull(RarMain.of(rar4 + bytes(0x12, 0x34, 0x74, 0x08, 0x00)))
        assertNull(RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x03, 0x01, 0x00)))
        assertNull(RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x03, 0x02, 0x00, 0x04)))
        assertNull(RarMain.of(rar5 + bytes(1, 2, 3, 4, 0x80)))
    }
}
