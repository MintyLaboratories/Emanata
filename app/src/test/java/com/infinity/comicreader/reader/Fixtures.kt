package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic archives for tests. An entry's content defaults to its own path, so reads can be traced. */
object Fixtures {
    fun entry(path: String, content: ByteArray = path.toByteArray()) = path to content

    fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun zip(file: File, vararg entries: Pair<String, ByteArray>): File =
        file.apply { writeBytes(zipBytes(*entries)) }

    fun sevenZip(file: File, vararg entries: Pair<String, ByteArray>): File {
        SevenZOutputFile(file).use { sz ->
            for ((path, content) in entries) {
                val e = sz.createArchiveEntry(File(path), path)
                sz.putArchiveEntry(e)
                sz.write(content)
                sz.closeArchiveEntry()
            }
        }
        return file
    }

    /** A POSIX (ustar) tar; [posix] false writes pre-POSIX headers, which carry no magic. */
    fun tarBytes(vararg entries: Pair<String, ByteArray>, posix: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((path, content) in entries) {
                val e = TarArchiveEntry(path)
                e.size = content.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        val bytes = out.toByteArray()
        if (!posix) {
            // Blanks every header's magic and version, then writes its checksum again.
            var at = 0
            for ((_, content) in entries) {
                for (i in 257 until 265) bytes[at + i] = 0
                for (i in 148 until 156) bytes[at + i] = ' '.code.toByte()
                val sum = (0 until 512).sumOf { bytes[at + it].toInt() and 0xFF }
                "%06o".format(sum).toByteArray().copyInto(bytes, at + 148)
                bytes[at + 154] = 0
                at += 512 + (content.size + 511) / 512 * 512
            }
        }
        return bytes
    }

    fun tar(file: File, vararg entries: Pair<String, ByteArray>): File =
        file.apply { writeBytes(tarBytes(*entries)) }

    /** A RAR 4 archive with every entry stored (method 0x30), per the RAR 2.9-4.x format notes. */
    fun rar4Bytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        // Main header: type, flags, size, reserved.
        rar4Block(out, LeBytes().u8(0x73).u16(0).u16(13).u16(0).u32(0).bytes())
        for ((path, content) in entries) {
            val name = path.replace('/', '\\').toByteArray(Charsets.ISO_8859_1)
            val head = LeBytes().u8(0x74).u16(0x8000).u16(32 + name.size)
                .u32(content.size.toLong()).u32(content.size.toLong())
                .u8(2) // Win32
                .u32(crc(content))
                .u32(0x00210000) // DOS time: 1980-01-01
                .u8(20).u8(0x30)
                .u16(name.size).u32(0x20)
                .raw(name).bytes()
            rar4Block(out, head)
            out.write(content)
        }
        out.write(bytes(0xC4, 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00))
        return out.toByteArray()
    }

    /** A RAR 5 archive with every entry stored (method 0), per the RAR 5.0 format notes. */
    fun rar5Bytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        // Main header: type 1, no header flags, no archive flags.
        rar5Block(out, VarBytes().v(1).v(0).v(0).bytes())
        for ((path, content) in entries) {
            val name = path.toByteArray(Charsets.UTF_8)
            val head = VarBytes().v(2).v(0x0002).v(content.size.toLong()) // type, data area, data size
                .v(0x0004).v(content.size.toLong()).v(0x20) // CRC present, unpacked size, attributes
                .u32(crc(content))
                .v(0).v(0) // stored, Windows
                .v(name.size.toLong()).raw(name).bytes()
            rar5Block(out, head)
            out.write(content)
        }
        rar5Block(out, VarBytes().v(5).v(0).v(0).bytes())
        return out.toByteArray()
    }

    private fun rar4Block(out: ByteArrayOutputStream, head: ByteArray) {
        val crc = crc(head) and 0xFFFF
        out.write(crc.toInt() and 0xFF)
        out.write((crc.toInt() ushr 8) and 0xFF)
        out.write(head)
    }

    private fun rar5Block(out: ByteArrayOutputStream, body: ByteArray) {
        val sized = VarBytes().v(body.size.toLong()).raw(body).bytes()
        out.write(LeBytes().u32(crc(sized)).bytes())
        out.write(sized)
    }

    private fun crc(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private class LeBytes {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v).u8(v ushr 8) }
        fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()).u16(((v ushr 16) and 0xFFFF).toInt()) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** RAR 5 fields: little-endian integers and 7-bit variable-length integers. */
    private class VarBytes {
        private val out = ByteArrayOutputStream()
        fun v(value: Long) = apply {
            var x = value
            while (x >= 0x80) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        fun v(value: Int) = v(value.toLong())
        fun u32(v: Long) = apply { out.write(LeBytes().u32(v).bytes()) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
