package com.infinity.comicreader.net

import com.github.junrar.Archive
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.nio.ByteBuffer

/**
 * A rar archive on a share as junrar's single volume, read in place through a
 * [RemoteChannel]: junrar seeks past what it does not need, so listing the archive
 * and reading its first page fetch only those parts.
 */
class RemoteRar(private val source: RemoteChannel) : VolumeManager {
    override fun nextVolume(archive: Archive, last: Volume?): Volume? = if (last == null) RarVolume(archive) else null

    private inner class RarVolume(private val archive: Archive) : Volume {
        override fun getChannel(): SeekableReadOnlyByteChannel = RarChannel()

        override fun getLength(): Long = source.size()

        override fun getArchive(): Archive = archive
    }

    /** junrar's view of [source]; closing it leaves the channel to [RemoteRar]'s owner. */
    private inner class RarChannel : SeekableReadOnlyByteChannel {
        override fun getPosition(): Long = source.position()

        override fun setPosition(pos: Long) {
            source.position(pos)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (source.read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, count: Int): Int = source.read(buffer, off, count)

        override fun readFully(buffer: ByteArray, count: Int): Int {
            val bb = ByteBuffer.wrap(buffer, 0, count)
            while (bb.hasRemaining()) {
                if (source.read(bb) < 0) break
            }
            return bb.position()
        }

        override fun close() {
            // The channel outlives junrar's views of it; its owner closes it.
        }
    }
}
