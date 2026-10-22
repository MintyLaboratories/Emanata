package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/**
 * Reads a zip/cbz archive from a channel it closes (a file on a share, in place):
 * the central directory first, then only the entries asked for. Commons Compress
 * serializes the channel's reads, so entries stream concurrently.
 */
class ChannelZipReader(channel: SeekableByteChannel, charsetName: String) : ArchiveReader {
    private val zip: ZipFile = try {
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        ZipFile.builder().setSeekableByteChannel(channel).setCharset(charset).get()
    } catch (t: Throwable) {
        runCatching { channel.close() }
        throw t
    }

    private val native: List<ZipArchiveEntry>

    override val entries: List<ArchiveEntry>

    init {
        try {
            native = zip.entries.asSequence().filter { !it.isDirectory }.toList()
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
