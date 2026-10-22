package com.infinity.comicreader.net

import com.infinity.comicreader.reader.Fixtures
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class RemoteChannelTest {

    /** [bytes] as an open file on a share, counting reads. */
    private class Bytes(private val bytes: ByteArray) : RemoteInput {
        var reads = 0
        override val length: Long = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            reads++
            if (position >= bytes.size) return -1
            val n = minOf(count.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() {}
    }

    private val data = ByteArray(10_000) { (it * 7).toByte() }

    @Test
    fun readsAcrossBlocksAndSeeks() {
        val input = Bytes(data)
        val channel = RemoteChannel(input, blockSize = 1024, blocks = 4)
        val buf = ByteBuffer.allocate(3000)
        channel.position(500)
        assertEquals(3000, channel.read(buf))
        assertArrayEquals(data.copyOfRange(500, 3500), buf.array())
        assertEquals(3500, channel.position())
        assertEquals(4, channel.fetches)
        // Kept blocks are not fetched again.
        channel.position(600)
        assertEquals(100, channel.read(ByteBuffer.allocate(100)))
        assertEquals(4, channel.fetches)
        channel.position(9_990)
        val tail = ByteBuffer.allocate(100)
        assertEquals(10, channel.read(tail))
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)))
        assertEquals(10_000, channel.size())
    }

    @Test
    fun onlyTheLastBlocksAreKept() {
        val channel = RemoteChannel(Bytes(data), blockSize = 1000, blocks = 2)
        for (at in listOf(0L, 1000L, 2000L, 0L)) {
            channel.position(at)
            channel.read(ByteBuffer.allocate(10))
        }
        assertEquals(4, channel.fetches)
    }

    @Test
    fun aZipReadsInPlaceFetchingOnlyWhatItNeeds() {
        val big = ByteArray(3 shl 20) { (it % 251).toByte() }
        val zip = Fixtures.zipBytes(Fixtures.entry("a.png", big), Fixtures.entry("b.png"), Fixtures.entry("ComicInfo.xml"))
        val input = Bytes(zip)
        ZipFile.builder().setSeekableByteChannel(RemoteChannel(input, blockSize = 64 * 1024)).get().use { file ->
            val names = file.entries.toList().map { it.name }
            assertEquals(listOf("a.png", "b.png", "ComicInfo.xml"), names)
            val b = file.getInputStream(file.getEntry("b.png")).use { it.readBytes() }
            assertArrayEquals("b.png".toByteArray(), b)
        }
        // The large page was never read: listing and one small page take a few blocks.
        assertTrue("read ${input.reads} times", input.reads <= 4)
    }
}
