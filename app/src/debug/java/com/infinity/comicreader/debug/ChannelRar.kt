package com.infinity.comicreader.debug

import com.github.junrar.Archive
import com.github.junrar.UnrarCallback
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import com.infinity.comicreader.reader.RarReader
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * A rar read by junrar through any [SeekableByteChannel], as a single volume, the way the app
 * reads one on a share (RemoteRar): the junrar side of the archive benchmark's channel runs.
 */
internal class ChannelRar(private val source: SeekableByteChannel) : VolumeManager {
    override fun nextVolume(archive: Archive, last: Volume?): Volume? = if (last == null) RarVolume(archive) else null

    private inner class RarVolume(private val archive: Archive) : Volume {
        override fun getChannel(): SeekableReadOnlyByteChannel = RarChannel()

        override fun getLength(): Long = source.size()

        override fun getArchive(): Archive = archive
    }

    /** junrar's view of [source]; closing it leaves the channel to [ChannelRar]'s owner. */
    private inner class RarChannel : SeekableReadOnlyByteChannel {
        override fun getPosition(): Long = source.position()

        override fun setPosition(pos: Long) {
            source.position(pos)
        }

        override fun read(): Int {
            val one = ByteBuffer.allocate(1)
            return if (source.read(one) <= 0) -1 else one.get(0).toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, count: Int): Int = source.read(ByteBuffer.wrap(buffer, off, count))

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

    companion object {
        /** A [RarReader] over [channel], which it closes. */
        fun reader(channel: SeekableByteChannel): RarReader {
            val archive = try {
                Archive(ChannelRar(channel), null as UnrarCallback?, null as String?)
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw t
            }
            return RarReader(archive, channel)
        }
    }
}
