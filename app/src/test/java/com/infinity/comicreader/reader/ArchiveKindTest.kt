package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun zipSignatures() {
        assertEquals(ArchiveKind.ZIP, ArchiveKind.sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertEquals(ArchiveKind.ZIP, ArchiveKind.sniff(bytes(0x50, 0x4B, 0x05, 0x06)))
        assertEquals(ArchiveKind.ZIP, ArchiveKind.sniff(bytes(0x50, 0x4B, 0x07, 0x08)))
        assertEquals(ArchiveKind.ZIP, ArchiveKind.sniff(bytes(0x50, 0x4B, 0x30, 0x30)))
    }

    @Test
    fun rarSignatures() {
        assertEquals(ArchiveKind.RAR, ArchiveKind.sniff("Rar!".toByteArray() + bytes(0x1A, 0x07, 0x00, 0xCF)))
        assertEquals(ArchiveKind.RAR, ArchiveKind.sniff("Rar!".toByteArray() + bytes(0x1A, 0x07, 0x01, 0x00)))
        assertNull(ArchiveKind.sniff("Rar!".toByteArray() + bytes(0x1A, 0x07, 0x02, 0x00)))
    }

    @Test
    fun sevenZipAndPdfSignatures() {
        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveKind.sniff(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0x00, 0x04)))
        assertEquals(ArchiveKind.PDF, ArchiveKind.sniff("%PDF-1.7".toByteArray()))
    }

    @Test
    fun shortOrUnknownHeaders() {
        assertNull(ArchiveKind.sniff(ByteArray(0)))
        assertNull(ArchiveKind.sniff(bytes(0x50, 0x4B, 0x03)))
        assertNull(ArchiveKind.sniff(bytes(0x50, 0x4B, 0x03, 0x04), length = 3))
        assertNull(ArchiveKind.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun contentBeatsExtension() {
        val f = File(tmp.root, "book.cbr")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertEquals(ArchiveKind.ZIP, ArchiveKind.detect(f))
    }

    @Test
    fun extensionWhenContentUnknown() {
        val f = File(tmp.root, "book.cb7").apply { writeBytes(bytes(0, 1, 2, 3)) }
        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveKind.detect(f))
        assertEquals(ArchiveKind.RAR, ArchiveKind.detect(File(tmp.root, "missing.cbr")))
        assertNull(ArchiveKind.detect(File(tmp.root, "notes.txt").apply { writeText("x") }))
    }
}
