package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.ByteArrayOutputStream
import java.io.File
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
}
