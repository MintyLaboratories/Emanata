package com.infinity.comicreader.reader

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reads a zip/cbz archive. [ZipFile] streams entries concurrently, so reads are not serialized. */
class ZipReader(file: File, charsetName: String) : ArchiveReader {
    private val zip: ZipFile = runCatching { ZipFile(file, Charset.forName(charsetName)) }
        .getOrElse { ZipFile(file) }

    private val native: List<ZipEntry>

    override val entries: List<ArchiveEntry>

    init {
        try {
            native = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            entries = native.mapIndexed { i, e -> ArchiveEntry(e.name, e.size, i) }
        } catch (t: Throwable) {
            runCatching { zip.close() }
            throw t
        }
    }

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        zip.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { zip.close() }
    }
}
