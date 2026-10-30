package com.infinity.comicreader.reader

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads a zip/cbz archive, its names in the charset [charsetName] gives ([NameCharset.resolve]),
 * or as their Unicode Path extra fields give them, as [ChannelZipReader] reads them.
 * [ZipFile] streams entries concurrently, so reads are not serialized.
 */
class ZipReader(file: File, charsetName: String) : ArchiveReader {
    /** The names as stored, in the zip's order ([ZipNames]). */
    private val stored = runCatching { RandomAccessFile(file, "r").use { ZipNames.read(it.channel) } }.getOrDefault(emptyList())

    override val nameCharset: Charset = NameCharset.resolve(charsetName, { stored })

    private val zip: ZipFile

    private val native: List<ZipEntry>

    override val entries: List<ArchiveEntry>

    init {
        // With every name known as stored, ZipFile reads them byte for byte (it skips Unicode
        // Path extra fields, and fails on names its charset does not decode); they are decoded here.
        var byBytes = if (stored.isEmpty()) null else runCatching { ZipFile(file, Charsets.ISO_8859_1) }.getOrNull()
        var all = byBytes?.let { z -> runCatching { z.entries().toList() }.getOrNull() }
        if (byBytes != null && all?.size != stored.size) {
            runCatching { byBytes?.close() }
            byBytes = null
        }
        zip = byBytes ?: runCatching { ZipFile(file, nameCharset) }.getOrElse { ZipFile(file) }
        try {
            if (byBytes == null) all = zip.entries().toList()
            val list = all.orEmpty()
            val files = list.indices.filter { !list[it].isDirectory }
            native = files.map { list[it] }
            entries = files.mapIndexed { i, at ->
                val name = if (byBytes == null) list[at].name else nameOf(stored[at])
                ArchiveEntry(name, list[at].size, i, list[at].crc)
            }
        } catch (t: Throwable) {
            runCatching { zip.close() }
            throw t
        }
    }

    /** [raw]'s name: its Unicode one, else its bytes as UTF-8 where marked so, else in [nameCharset]. */
    private fun nameOf(raw: NameCharset.Raw): String =
        raw.unicode ?: String(raw.bytes, if (raw.utf8) Charsets.UTF_8 else nameCharset)

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        zip.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { zip.close() }
    }
}
