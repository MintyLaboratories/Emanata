package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** The channel side of libarchive's callbacks: what native code sees for reads, seeks and skips. */
class LibArchiveSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val data = ByteArray(1000) { (it * 7).toByte() }

    private fun <T> withSource(block: (LibArchive.ChannelSource, FileChannel) -> T): T {
        val file = temp.newFile("data.bin").apply { writeBytes(data) }
        return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { block(LibArchive.ChannelSource(it), it) }
    }

    @Test
    fun seeksFromTheStartThePositionOrTheEnd() = withSource { source, channel ->
        assertEquals(100, source.seek(100, LibArchive.SEEK_SET))
        assertEquals(150, source.seek(50, LibArchive.SEEK_CUR))
        assertEquals(120, source.seek(-30, LibArchive.SEEK_CUR))
        assertEquals(978, source.seek(-22, LibArchive.SEEK_END))
        assertEquals(978, channel.position())
        // Past the end is allowed; reads there find the end.
        assertEquals(1010, source.seek(10, LibArchive.SEEK_END))
        assertEquals(-1, source.read(ByteBuffer.allocate(16)))
    }

    @Test
    fun aSeekBeforeTheStartOrWithAnUnknownWhenceFails() = withSource { source, channel ->
        source.seek(10, LibArchive.SEEK_SET)
        assertThrows(IOException::class.java) { source.seek(-11, LibArchive.SEEK_CUR) }
        assertThrows(IOException::class.java) { source.seek(-1001, LibArchive.SEEK_END) }
        assertThrows(IOException::class.java) { source.seek(0, 3) }
        assertEquals(10, channel.position())
    }

    @Test
    fun skipsStopAtTheEnd() = withSource { source, channel ->
        assertEquals(400, source.skip(400))
        assertEquals(600, source.skip(10_000))
        assertEquals(1000, channel.position())
        assertEquals(0, source.skip(5))
        source.seek(0, LibArchive.SEEK_SET)
        assertEquals(0, source.skip(-5))
        assertEquals(0, channel.position())
    }

    @Test
    fun readsFillTheBufferFromItsStart() = withSource { source, _ ->
        val buffer = ByteBuffer.allocateDirect(256)
        // Left at another position by an earlier caller: a read starts at index 0 anyway.
        buffer.position(100)
        source.seek(900, LibArchive.SEEK_SET)
        assertEquals(100, source.read(buffer))
        val got = ByteArray(100).also { buffer.flip(); buffer.get(it) }
        assertArrayEquals(data.copyOfRange(900, 1000), got)
        assertEquals(-1, source.read(buffer))
    }

    @Test
    fun aReadTakesNoMoreThanItsChunk() = withSource { source, channel ->
        val buffer = ByteBuffer.allocateDirect(256)
        source.chunk = 40
        assertEquals(40, source.read(buffer))
        assertEquals(40, channel.position())
        source.chunk = 1 shl 20
        assertEquals(256, source.read(buffer))
        assertArrayEquals(data.copyOfRange(40, 296), ByteArray(256).also { buffer.flip(); buffer.get(it) })
    }
}
