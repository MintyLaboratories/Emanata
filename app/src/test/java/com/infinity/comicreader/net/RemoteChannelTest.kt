package com.infinity.comicreader.net

import com.infinity.comicreader.reader.ChannelZipReader
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.RarReader
import com.infinity.comicreader.reader.TarReader
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** Runs background work at once, on the caller's thread. */
    private val now = Executor { it.run() }

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

    @After
    fun spendThrift() {
        RemoteChannel.thrifty = false
    }

    /** Reads [channel] to its end in pieces of [piece] bytes. */
    private fun drain(channel: RemoteChannel, piece: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(piece)
        while (true) {
            val n = channel.read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun aStreamFetchesTheNextRunAheadOnceItsRunsHaveGrown() {
        val big = ByteArray(40_000) { (it * 13).toByte() }
        val input = Bytes(big)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 8192, aheadFrom = 4096, background = now)
        assertArrayEquals(big, drain(channel, 500))
        // 1, 2, 4 KB grow as before; from the 4 KB run on, the next two runs are always coming.
        assertEquals(listOf(0L to 1024, 1024L to 2048, 3072L to 4096, 7168L to 8192, 15360L to 8192, 23552L to 8192, 31744L to 8192, 39936L to 64), input.asked)
        assertEquals(5, channel.aheads)
        channel.close()
        assertEquals(0L, RemoteChannel.aheadBytes)
    }

    @Test
    fun nothingComesAheadInBatterySaver() {
        RemoteChannel.thrifty = true
        val big = ByteArray(40_000) { (it * 13).toByte() }
        val channel = RemoteChannel(Bytes(big), minBlock = 1024, maxBlock = 8192, aheadFrom = 4096, background = now)
        assertArrayEquals(big, drain(channel, 500))
        assertEquals(0, channel.aheads)
    }

    @Test
    fun headerWalksNeverFetchAhead() {
        val input = Bytes(data)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 4096, background = now)
        for (at in listOf(0L, 3000L, 6000L, 9000L)) {
            channel.position(at)
            channel.read(ByteBuffer.allocate(100))
        }
        assertEquals(0, channel.aheads)
    }

    @Test
    fun anExpectedSpanIsFetchedFromItsFirstReadAndTheRestAhead() {
        val big = ByteArray(100_000) { (it * 5).toByte() }
        val input = Bytes(big)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 16_384, background = now)
        channel.expect(10_000, 30_000)
        channel.position(10_000)
        val out = ByteArray(30_000)
        var n = 0
        while (n < out.size) n += channel.read(out, n, minOf(700, out.size - n))
        assertArrayEquals(big.copyOfRange(10_000, 40_000), out)
        assertEquals(listOf(10_000L to 16_384, 26_384L to 13_616), input.asked)
        assertEquals(1, channel.aheads)
    }

    @Test
    fun nothingComesAheadPastAnEntryReadWhole() {
        val big = ByteArray(100_000) { (it * 5).toByte() }
        val input = Bytes(big)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 40_000, aheadFrom = 1024, background = now)
        channel.expect(10_000, 30_000)
        channel.position(10_000)
        assertEquals(30_000, channel.read(ByteBuffer.allocate(30_000)))
        assertEquals(listOf(10_000L to 30_000), input.asked)
        assertEquals(0, channel.aheads)
    }

    @Test
    fun aRunFetchedAheadButNotReadIsLetGo() {
        val big = ByteArray(100_000) { (it * 5).toByte() }
        val input = Bytes(big)
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 16_384, background = now)
        channel.expect(0, 50_000)
        channel.read(ByteBuffer.allocate(10))
        assertEquals(2, channel.aheads)
        // A seek elsewhere: the runs ahead go, and the bytes there are still right.
        channel.position(80_000)
        val far = ByteBuffer.allocate(100)
        channel.read(far)
        assertArrayEquals(big.copyOfRange(80_000, 80_100), far.array())
        assertEquals(0L, RemoteChannel.aheadBytes)
        channel.close()
    }

    @Test
    fun aRunAheadThatFailedIsFetchedAgain() {
        val big = ByteArray(100_000) { (it * 5).toByte() }
        var failing = true
        val input = object : RemoteInput {
            val asked = ArrayList<Long>()
            override val length = big.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                asked += position
                if (position > 0 && failing) throw java.io.IOException("Dropped")
                val n = minOf(count.toLong(), big.size - position).toInt()
                System.arraycopy(big, position.toInt(), buffer, offset, n)
                return n
            }

            override fun close() {}
        }
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 16_384, background = now)
        channel.expect(0, 30_000)
        channel.read(ByteBuffer.allocate(16_384))
        failing = false
        val next = ByteBuffer.allocate(1000)
        channel.read(next)
        assertArrayEquals(big.copyOfRange(16_384, 17_384), next.array())
        assertEquals(listOf(0L, 16_384L, 16_384L), input.asked)
    }

    /** [big] as a file whose reads past the first run wait for [go], each giving half of what it asks; [asked] lists them. */
    private class Gated(private val big: ByteArray, private val first: Int) : RemoteInput {
        val go = CountDownLatch(1)
        val started = CountDownLatch(1)
        val asked = java.util.Collections.synchronizedList(ArrayList<Long>())
        override val length = big.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            asked += position
            if (position >= first) {
                started.countDown()
                go.await(5, TimeUnit.SECONDS)
            }
            val n = minOf(if (position >= first) maxOf(1, count / 2) else count, big.size - position.toInt())
            System.arraycopy(big, position.toInt(), buffer, offset, n)
            return n
        }

        override fun readFully(position: Long, buffer: ByteArray, offset: Int, count: Int, heard: () -> Unit): Int =
            read(position, buffer, offset, count)

        override fun close() {}
    }

    @Test
    fun closingStopsTheRunsComingAhead() {
        val big = ByteArray(100_000) { (it * 5).toByte() }
        val input = Gated(big, first = 16_384)
        val background = Executors.newSingleThreadExecutor()
        try {
            val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 16_384, background = background)
            channel.expect(0, 60_000)
            channel.read(ByteBuffer.allocate(10))
            assertEquals(2, channel.aheads)
            assertTrue(input.started.await(5, TimeUnit.SECONDS))
            channel.close()
            assertEquals(0L, RemoteChannel.aheadBytes)
            input.go.countDown()
            background.shutdown()
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS))
            // The read under way was let finish; neither its rest nor the next run was asked for.
            assertEquals(listOf(0L, 16_384L), input.asked.toList())
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun runsComingAheadThatAStreamSkipsAreCanceled() {
        val big = ByteArray(100_000) { (it * 5).toByte() }
        val input = Gated(big, first = 16_384)
        val background = Executors.newSingleThreadExecutor()
        try {
            val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 16_384, background = background)
            channel.expect(0, 60_000)
            channel.read(ByteBuffer.allocate(10))
            assertTrue(input.started.await(5, TimeUnit.SECONDS))
            // Reading on inside the second run coming lets the first go unread.
            channel.position(40_000)
            // Let go once the read below has let the first run go and waits for the second.
            Thread { Thread.sleep(300); input.go.countDown() }.start()
            val far = ByteBuffer.allocate(100)
            channel.read(far)
            assertArrayEquals(big.copyOfRange(40_000, 40_100), far.array())
            channel.close()
            background.shutdown()
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(input.asked.toList().toString(), input.asked.none { it in 16_385L until 32_768L })
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun runsAreAskedForWhole() {
        val calls = ArrayList<Int>()
        val input = object : RemoteInput {
            override val length = data.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int = throw AssertionError("Read in pieces")
            override fun readFully(position: Long, buffer: ByteArray, offset: Int, count: Int, heard: () -> Unit): Int {
                calls += count
                System.arraycopy(data, position.toInt(), buffer, offset, count)
                return count
            }

            override fun close() {}
        }
        val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 4096)
        channel.position(2000)
        channel.read(ByteBuffer.allocate(3000))
        assertEquals(listOf(3976), calls)
    }

    @Test
    fun archiveReadersFetchAnEntryAboutToBeReadInOneRun() {
        val page = ByteArray(3 shl 20) { (it % 253).toByte() }
        val entries = arrayOf(Fixtures.entry("a.png"), Fixtures.entry("big.png", page), Fixtures.entry("z.png"))
        val archives = linkedMapOf(
            "zip" to Fixtures.zipBytes(*entries),
            "tar" to Fixtures.tarBytes(*entries),
            "rar4" to Fixtures.rar4Bytes(*entries),
            "rar5" to Fixtures.rar5Bytes(*entries)
        )
        for ((kind, bytes) in archives) {
            val input = Bytes(bytes)
            val channel = RemoteChannel(input, background = now)
            val reader = when (kind) {
                "zip" -> ChannelZipReader(channel, "UTF-8")
                "tar" -> TarReader(channel, "UTF-8")
                else -> RarReader.inPlace(channel)
            }
            reader.use { r ->
                val before = input.reads
                val got = r.read(r.entries.first { it.path == "big.png" }) { it.readBytes() }
                assertArrayEquals(kind, page, got)
                assertTrue("$kind: ${input.reads - before} reads", input.reads - before <= 2)
            }
        }
    }
}
