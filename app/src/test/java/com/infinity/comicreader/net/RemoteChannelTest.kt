package com.infinity.comicreader.net

import com.infinity.comicreader.reader.Fixtures
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RemoteChannelTest {

    /** [bytes] as an open file on a share, recording each read's position and length. */
    private class Bytes(private val bytes: ByteArray) : RemoteInput {
        val asked = ArrayList<Pair<Long, Int>>()
        val reads get() = asked.size
        override val length: Long = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            asked += position to count
            if (position >= bytes.size) return -1
            val n = minOf(count.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() {}
    }

    private val data = ByteArray(10_000) { (it * 7).toByte() }

    @Test
    fun smallReadsInARowFetchGrowingRuns() {
        val input = Bytes(data)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 4096)
        val out = ByteArrayOutputStream()
        val piece = ByteArray(100)
        while (true) {
            val n = channel.read(piece, 0, piece.size)
            if (n < 0) break
            out.write(piece, 0, n)
        }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(listOf(0L to 1024, 1024L to 2048, 3072L to 4096, 7168L to 2832), input.asked)
        assertEquals(10_000, channel.size())
    }

    @Test
    fun aSeekFetchesASmallRunAndKeptRunsAreReused() {
        val input = Bytes(data)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 4096)
        channel.read(ByteBuffer.allocate(10))
        channel.position(5000)
        channel.read(ByteBuffer.allocate(10))
        channel.position(600)
        assertEquals(100, channel.read(ByteBuffer.allocate(100)))
        // Following on from the last run fetched grows the next one.
        channel.position(5120)
        channel.read(ByteBuffer.allocate(10))
        assertEquals(listOf(0L to 1024, 4096L to 1024, 5120L to 2048), input.asked)
        // A large read fetches what it asks for in one run.
        channel.position(8000)
        val tail = ByteBuffer.allocate(2000)
        assertEquals(2000, channel.read(tail))
        assertArrayEquals(data.copyOfRange(8000, 10_000), tail.array())
        assertEquals(7168L to 2832, input.asked.last())
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)))
    }

    @Test
    fun readsGoingOnFromAnUnalignedRunKeepGrowing() {
        val input = Bytes(data)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 4096)
        channel.position(100)
        channel.read(ByteBuffer.allocate(1500))
        val piece = ByteBuffer.allocate(500)
        while (channel.read(piece.apply { clear() }) > 0) Unit
        assertEquals(listOf(0L to 1600, 1600L to 2048, 3648L to 4096, 7744L to 2256), input.asked)
    }

    @Test
    fun onlyTheMostRecentRunsAreKept() {
        val input = Bytes(data)
        val channel = RemoteChannel(input, minBlock = 1000, maxBlock = 1000, maxCached = 2000)
        for (at in listOf(0L, 3000L, 6000L, 0L)) {
            channel.position(at)
            channel.read(ByteBuffer.allocate(10))
        }
        assertEquals(4, channel.fetches)
    }

    @Test
    fun aLongStreamedReadReusesTheArraysOfRunsLetGo() {
        val big = ByteArray(200_000) { (it * 31 + it / 977).toByte() }
        val input = Bytes(big)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 4096, maxCached = 8192)
        val out = ByteArrayOutputStream()
        val piece = ByteArray(700)
        while (true) {
            val n = channel.read(piece, 0, piece.size)
            if (n < 0) break
            out.write(piece, 0, n)
        }
        assertArrayEquals(big, out.toByteArray())
        // About 50 runs of 4 KB; arrays only for the growing ones, the first few full ones and the last.
        assertTrue("${channel.fetches} runs", channel.fetches > 45)
        assertTrue("${channel.allocations} arrays", channel.allocations <= 8)

        // Reads back anywhere still get the file's bytes, never a reused array's old ones.
        val buffer = ByteArray(3000)
        for (at in listOf(150_000L, 3L, 199_000L, 150_500L, 77_777L, 150_000L)) {
            channel.position(at)
            val n = channel.read(buffer, 0, buffer.size)
            assertArrayEquals("at $at", big.copyOfRange(at.toInt(), at.toInt() + n), buffer.copyOf(n))
        }
    }

    @Test
    fun aZipReadsInPlaceFetchingOnlyWhatItNeeds() {
        val big = ByteArray(3 shl 20) { (it % 251).toByte() }
        val zip = Fixtures.zipBytes(Fixtures.entry("a.png", big), Fixtures.entry("b.png"), Fixtures.entry("ComicInfo.xml"))
        val input = Bytes(zip)
        ZipFile.builder().setSeekableByteChannel(RemoteChannel(input)).setIgnoreLocalFileHeader(true).get().use { file ->
            val names = file.entries.toList().map { it.name }
            assertEquals(listOf("a.png", "b.png", "ComicInfo.xml"), names)
            val b = file.getInputStream(file.getEntry("b.png")).use { it.readBytes() }
            assertArrayEquals("b.png".toByteArray(), b)
        }
        // The large page was never read: listing and one small page take a few blocks.
        assertTrue("read ${input.reads} times", input.reads <= 4)
    }
}
