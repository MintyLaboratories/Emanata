package com.infinity.comicreader.reader

import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/** A file inside an archive: its [path] in the archive, its uncompressed [size] (-1 when unknown). */
class ArchiveEntry(val path: String, val size: Long, internal val index: Int)

/** Lists the files of one archive and streams their contents. */
interface ArchiveReader : Closeable {
    /** Files only; directories are left out. */
    val entries: List<ArchiveEntry>

    /**
     * False when reading an entry may unpack every entry stored before it (a solid
     * block), so reading out of [ArchiveEntry.index] order is costly.
     */
    val randomAccess: Boolean get() = true

    /** The charset its file names were read in, where the format leaves that open (zip, tar). */
    val nameCharset: Charset? get() = null

    /** Runs [block] on the contents of [entry]; implementations serialize this where the backend needs it. */
    fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T

    companion object {
        /**
         * Reader for [file] as [kind] (by default from its leading bytes), or null when it
         * is not an archive. One on a share is read in place, through a [RemoteChannel];
         * on a server that sends files only whole ([Remotes.seekable]) it throws.
         */
        fun open(
            file: File,
            charsetName: String,
            kind: ArchiveKind? = ArchiveKind.detect(file)
        ): ArchiveReader? = if (file is RemoteFile) inPlace(file, charsetName, kind) else when (kind) {
            ArchiveKind.ZIP -> ZipReader(file, charsetName)
            ArchiveKind.RAR -> RarReader(file)
            ArchiveKind.SEVEN_ZIP -> SevenZipReader(file)
            ArchiveKind.TAR -> TarReader(file, charsetName)
            ArchiveKind.PDF, null -> null
        }

        private fun inPlace(file: RemoteFile, charsetName: String, kind: ArchiveKind?): ArchiveReader? {
            if (kind == null || kind == ArchiveKind.PDF) return null
            // A server sending files only whole would send this one again for each seek.
            if (!Remotes.seekable(file.path)) throw IOException("Not readable in place: ${file.name}")
            val channel = RemoteChannel.open(file.path)
            return when (kind) {
                ArchiveKind.ZIP -> ChannelZipReader(channel, charsetName)
                ArchiveKind.RAR -> RarReader.inPlace(channel)
                ArchiveKind.SEVEN_ZIP -> closingOnFailure(channel) { SevenZipReader(channel) }
                ArchiveKind.TAR -> closingOnFailure(channel) {
                    // Each entry's header sits before its data: small fetches while walking them.
                    channel.seekBlock = RemoteChannel.HEADER_BLOCK_BYTES
                    TarReader(channel, charsetName).also { channel.seekBlock = RemoteChannel.MIN_BLOCK_BYTES }
                }
                ArchiveKind.PDF -> null
            }
        }

        private inline fun closingOnFailure(channel: Closeable, open: () -> ArchiveReader): ArchiveReader = try {
            open()
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }
}
