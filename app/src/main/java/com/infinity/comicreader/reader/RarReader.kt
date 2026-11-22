package com.infinity.comicreader.reader

import com.github.junrar.Archive
import com.github.junrar.UnrarCallback
import com.github.junrar.rarfile.FileHeader
import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.RemoteRar
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Reads a rar/cbr archive, RAR4 or RAR5 (via junrar); [owned] closes with it. */
class RarReader internal constructor(private val archive: Archive, private val owned: Closeable?) : ArchiveReader {
    constructor(file: File) : this(Archive(file), null)

    private val native: List<FileHeader>

    override val entries: List<ArchiveEntry>

    /** By its main header; null when that cannot be read. */
    override val solid: Boolean? = runCatching { archive.mainHeader.isSolid }.getOrNull()

    /** False for a solid archive, where each entry unpacks those stored before it. */
    override val randomAccess: Boolean = solid == false

    init {
        try {
            native = archive.fileHeaders.filter { !it.isDirectory }
            entries = native.mapIndexed { i, h -> ArchiveEntry(h.fileName, h.fullUnpackSize, i) }
        } catch (t: Throwable) {
            runCatching { archive.close() }
            runCatching { owned?.close() }
            throw t
        }
    }

    /** Serializes reads first come, first served: a page for the screen waits for the read in progress, never a run of background reads. */
    private val lock = ReentrantLock(true)

    /**
     * junrar's [Archive] is not thread-safe; the adapter loads neighbors
     * concurrently, so access is serialized here ([lock]). junrar unpacks on a thread of
     * its own, so an entry [block] reads only the start of is read to its end before the
     * next read may begin.
     */
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T = lock.withLock {
        val header = native[entry.index]
        // On a share: its header and packed data, about to be read whole.
        (owned as? RemoteChannel)?.expect(header.positionInFile, header.fullPackSize + HEADER_SLACK_BYTES)
        archive.getInputStream(header).use { input ->
            try {
                block(input)
            } finally {
                runCatching {
                    val sink = ByteArray(DRAIN_BYTES)
                    while (input.read(sink) >= 0) Unit
                }
            }
        }
    }

    override fun close() {
        runCatching { archive.close() }
        runCatching { owned?.close() }
    }

    companion object {
        private const val DRAIN_BYTES = 64 * 1024

        /** Room for an entry's header before its packed data: names and extra fields rarely take more. */
        private const val HEADER_SLACK_BYTES = 4096L

        /** Reads the rar on a share through [channel], in place; the reader closes it. */
        fun inPlace(channel: RemoteChannel): RarReader {
            // Each entry's header sits before its data: small fetches while walking them.
            channel.seekBlock = RemoteChannel.HEADER_BLOCK_BYTES
            val archive = try {
                Archive(RemoteRar(channel), null as UnrarCallback?, null as String?)
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw t
            }
            channel.seekBlock = RemoteChannel.MIN_BLOCK_BYTES
            return RarReader(archive, channel)
        }
    }
}
