package com.infinity.comicreader.files

import com.infinity.comicreader.net.RemoteInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class ShareReadsTest {

    /** [bytes] as an open file on a share, counting what is asked of it. */
    private class Bytes(private val bytes: ByteArray) : RemoteInput {
        var reads = 0
        var closed = false
        override val length: Long = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            reads++
            if (position >= bytes.size) return -1
            val n = minOf(count.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private val data = Random(7).nextBytes(10_000_000)

    @Test
    fun opensAtTheFirstReadOnly() {
        var opened = 0
        val reads = ShareReads(data.size.toLong()) { opened++; Bytes(data) }
        assertEquals(0, opened)
        reads.read(0, 10, ByteArray(10))
        reads.read(10, 10, ByteArray(10))
        assertEquals(1, opened)
    }

    @Test
    fun streamingReadsGetTheWholeFileInFewRoundTrips() {
        val input = Bytes(data)
        val reads = ShareReads(data.size.toLong()) { input }
        val out = ByteArrayOutputStream()
        val piece = ByteArray(128 * 1024)
        var at = 0L
        while (true) {
            val n = reads.read(at, piece.size, piece)
            if (n == 0) break
            out.write(piece, 0, n)
            at += n
        }
        assertArrayEquals(data, out.toByteArray())
        // Runs grow from 128 KB to 4 MB: 10 MB in a handful, not one per 128 KB read (77).
        assertTrue("${input.reads} round trips", input.reads <= 8)
    }

    @Test
    fun readsAnywhereAndStopsAtTheEnd() {
        val reads = ShareReads(data.size.toLong()) { Bytes(data) }
        val buffer = ByteArray(4096)
        for (offset in listOf(9_000_000L, 17L, 5_000_000L, data.size - 100L)) {
            val n = reads.read(offset, buffer.size, buffer)
            val expected = minOf(buffer.size.toLong(), data.size - offset).toInt()
            assertEquals(expected, n)
            assertArrayEquals(data.copyOfRange(offset.toInt(), offset.toInt() + n), buffer.copyOf(n))
        }
        assertEquals(0, reads.read(data.size.toLong(), 10, buffer))
        assertEquals(0, reads.read(data.size + 5L, 10, buffer))
    }

    @Test
    fun closingLetsGoOfTheFile() {
        val input = Bytes(data)
        val reads = ShareReads(data.size.toLong()) { input }
        reads.read(0, 10, ByteArray(10))
        assertFalse(input.closed)
        reads.close()
        assertTrue(input.closed)
        try {
            reads.read(0, 10, ByteArray(10))
            throw AssertionError("read after close")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun aFailingShareFailsTheRead() {
        val reads = ShareReads(100) { throw IOException("unreachable") }
        try {
            reads.read(0, 10, ByteArray(10))
            throw AssertionError("read without a share")
        } catch (e: IOException) {
            assertEquals("unreachable", e.message)
        }
    }

    @Test
    fun aFileThatChangedSizeFailsEveryReadAndSaysSo() {
        for (now in listOf(data.copyOf(9_000_000), data + ByteArray(5))) {
            var changed = 0
            var opened = 0
            val input = Bytes(now)
            val reads = ShareReads(data.size.toLong(), onChanged = { changed++ }) { opened++; input }
            repeat(2) {
                try {
                    reads.read(0, 10, ByteArray(10))
                    throw AssertionError("read of a changed file")
                } catch (e: IOException) {
                    // Expected.
                }
            }
            assertEquals(1, changed)
            assertEquals(1, opened)
            assertTrue(input.closed)
            assertEquals(0, input.reads)
        }
    }

    @Test
    fun aFileOfTheSizeExpectedIsNotChanged() {
        var changed = 0
        val reads = ShareReads(data.size.toLong(), onChanged = { changed++ }) { Bytes(data) }
        assertEquals(10, reads.read(0, 10, ByteArray(10)))
        assertEquals(0, changed)
    }
}
