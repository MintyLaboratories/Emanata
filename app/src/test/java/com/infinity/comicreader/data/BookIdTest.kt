package com.infinity.comicreader.data

import com.infinity.comicreader.testing.Xxh3Port
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookIdTest {

    private fun hex(hash: Long) = BookId(hash).hex

    private fun ramp(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun portGivesTheReferenceValues() {
        assertEquals("2d06800538d394c2", hex(Xxh3Port.hash(ByteArray(0), 0)))
        assertEquals("78af5f94892f3950", hex(Xxh3Port.hash("abc".toByteArray(), 3)))
        assertEquals("34542675f8ba79ea", hex(Xxh3Port.hash(ByteArray(65_544), 65_544)))
        assertEquals("f3b879e4d702d1f2", hex(Xxh3Port.hash(ramp(65_544), 65_544)))
    }

    /** Values of xxHash's own code, one per length class of XXH3-64 and its edges. */
    @Test
    fun portMatchesXxhashAcrossLengthClasses() {
        val expected = mapOf(
            4 to "60dab036a58211f2", 8 to "3a1c2d7c85af88f8", 9 to "e9612598145bb9dc", 16 to "8355e3a6f61770db",
            17 to "9ef341a99de37328", 64 to "6187eb9089b0ed55", 128 to "85c6174c7ff4c46b", 129 to "ec7642b431ba3e5a",
            240 to "375a384d957fe865", 241 to "02e8cd95421c6d02", 1024 to "a870f92984398d22", 1025 to "78c86e91ee939852"
        )
        for ((n, value) in expected) assertEquals("length $n", value, hex(Xxh3Port.hash(ramp(1100), n)))
    }

    @Test
    fun hashesOnlyTheLengthGiven() {
        val bytes = ramp(300)
        assertEquals(Xxh3Port.hash(bytes.copyOf(200), 200), Xxh3Port.hash(bytes, 200))
    }

    @Test
    fun idsReadAndWriteAsSixteenHexDigits() {
        assertEquals("0000000000000001", BookId(1).hex)
        assertEquals("ffffffffffffffff", BookId(-1).hex)
        assertEquals(BookId(-0x1234_5678_9abc_def0), BookId.parse(BookId(-0x1234_5678_9abc_def0).hex))
        assertEquals(BookId(0x0123456789abcdefL), BookId.parse("0123456789ABCDEF"))
        assertNull(BookId.parse("/storage/a.cbz"))
        assertNull(BookId.parse("0123"))
        assertNull(BookId.parse("0123456789abcdeg"))
        assertNull(BookId.parse(null))
    }

    @Test
    fun smallFilesAreTakenWholeAfterTheirSize() {
        val file = ramp(1000)
        val layout = BookIdBytes.of(file.size.toLong()) { position, into, offset, length ->
            System.arraycopy(file, position.toInt(), into, offset, length)
        }
        assertEquals(1008, layout.size)
        assertArrayEquals(byteArrayOf(0xE8.toByte(), 0x03, 0, 0, 0, 0, 0, 0), layout.copyOf(8))
        assertArrayEquals(file, layout.copyOfRange(8, layout.size))
    }

    @Test
    fun largeFilesGiveTheirSizeThenBothEnds() {
        val size = 5_000_000_000L
        assertEquals(65_544, BookIdBytes.length(size))
        assertEquals(
            listOf(Triple(0L, 8, 32_768), Triple(size - 32_768, 32_776, 32_768)),
            BookIdBytes.ranges(size)
        )
        assertArrayEquals(byteArrayOf(0x00, 0xF2.toByte(), 0x05, 0x2A, 0x01, 0, 0, 0), BookIdBytes.start(size).copyOf(8))
    }

    @Test
    fun theWholeFileGoesUpTo64KibAndBothEndsFromThere() {
        assertEquals(8, BookIdBytes.length(0))
        assertEquals(emptyList<Triple<Long, Int, Int>>(), BookIdBytes.ranges(0))
        assertEquals(8 + 65_535, BookIdBytes.length(65_535))
        assertEquals(listOf(Triple(0L, 8, 65_535)), BookIdBytes.ranges(65_535))
        // At exactly 64 KiB both ends are the whole file, in order.
        assertEquals(listOf(Triple(0L, 8, 32_768), Triple(32_768L, 32_776, 32_768)), BookIdBytes.ranges(65_536))
        assertEquals(65_544, BookIdBytes.length(100_000))
    }

    @Test
    fun theSizeTellsFilesOfTheSameEndsApart() {
        fun idOf(size: Int): BookId {
            val file = ByteArray(size) { (it % 7).toByte() }
            val layout = BookIdBytes.of(size.toLong()) { position, into, offset, length -> System.arraycopy(file, position.toInt(), into, offset, length) }
            return BookIdBytes.idOf(layout, Xxh3Port)
        }
        assertNotEquals(idOf(200_000), idOf(200_007))
    }

    @Test
    fun emptyFileHasAFixedId() {
        assertEquals(hex(Xxh3Port.hash(ByteArray(8), 8)), BookIdBytes.idOf(BookIdBytes.start(0), Xxh3Port).hex)
    }
}
