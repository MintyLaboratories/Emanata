package minty.emanata.reader

import minty.emanata.reader.Fixtures.entry
import minty.emanata.testing.awaitUntil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.random.Random

/** The RAR reader over libarchive's calls, as [StoredRarEngine] answers them on the JVM. */
class RarReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pages = arrayOf(entry("02.jpg"), entry("10.jpg"), entry("01.jpg"), entry("notes.txt"))

    /** Six pages of different sizes and contents. */
    private val big = Array(6) { entry("%02d.jpg".format(it), Random(it).nextBytes(10_000 + 997 * it)) }

    private fun ArchiveSource.text(index: Int) = readPage(index) { String(it.readBytes()) }

    private fun file(bytes: ByteArray, name: String = "book.cbr") = File(tmp.newFolder(), name).apply { writeBytes(bytes) }

    private fun RarReader.bytes(i: Int) = read(entries[i]) { it.readBytes() }

    private fun check(file: File) {
        val reader = ArchiveReader.open(file, "UTF-8")
        assertTrue(reader is RarReader)
        ArchiveSource(reader!!).use { src ->
            assertEquals(listOf("01.jpg", "02.jpg", "10.jpg"), src.pagePaths)
            assertEquals("10.jpg", src.text(2))
        }
    }

    @Test
    fun rar4Pages() {
        check(file(Fixtures.rar4Bytes(*pages)))
    }

    @Test
    fun rar5Pages() {
        check(file(Fixtures.rar5Bytes(*pages)))
    }

    @Test
    fun rarSubfoldersAndJunk() {
        val entries = arrayOf(entry("b/01.jpg"), entry("a/02.jpg"), entry("a/01.jpg"), entry("__MACOSX/a/._01.jpg"))
        for (bytes in listOf(Fixtures.rar4Bytes(*entries), Fixtures.rar5Bytes(*entries))) {
            ArchiveSource(ArchiveReader.open(file(bytes), "UTF-8")!!).use { src ->
                assertEquals(3, src.pageCount)
                assertEquals(listOf("a/01.jpg", "a/02.jpg", "b/01.jpg"), src.pagePaths)
            }
        }
    }

    @Test
    fun pathsUseSlashesWhateverTheArchiveStores() {
        // RAR 4 stores "\" between folders (the engine here passes it on as stored); a stray one in RAR 5 too.
        RarReader(file(Fixtures.rar4Bytes(entry("b/01.jpg"), entry("a/x\\y.jpg")))).use {
            assertEquals(listOf("b/01.jpg", "a/x/y.jpg"), it.entries.map { e -> e.path })
        }
        RarReader(file(Fixtures.rar5Bytes(entry("a\\01.jpg"), entry("a/02.jpg")))).use {
            assertEquals(listOf("a/01.jpg", "a/02.jpg"), it.entries.map { e -> e.path })
        }
    }

    @Test
    fun entriesKeepTheArchivesOrderAndSizes() {
        val entries = arrayOf(entry("02.jpg", ByteArray(5)), entry("01.jpg", ByteArray(0)), entry("03.jpg", ByteArray(70_000)))
        for (bytes in listOf(Fixtures.rar4Bytes(*entries), Fixtures.rar5Bytes(*entries))) {
            RarReader(file(bytes)).use { r ->
                assertEquals(listOf("02.jpg", "01.jpg", "03.jpg"), r.entries.map { it.path })
                assertEquals(listOf(5L, 0L, 70_000L), r.entries.map { it.size })
                assertEquals(listOf(0, 1, 2), r.entries.map { it.index })
                assertEquals(0, r.bytes(1).size)
            }
        }
    }

    @Test
    fun rarNamedCbzOpensAsRar() {
        check(file(Fixtures.rar5Bytes(*pages), "book.cbz"))
    }

    @Test
    fun rarInsideZipAndZipInsideRar() {
        val cache = File(tmp.root, "cache")
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Issue 1.cbr", Fixtures.rar4Bytes(entry("01.jpg", "issue1".toByteArray()))),
            entry("Issue 2.cbr", Fixtures.rar5Bytes(entry("01.jpg", "issue2".toByteArray())))
        )
        ArchiveSource(ZipReader(f, "UTF-8"), NestedArchives.keyOf(f), NestedArchives(cache, "UTF-8")).use { src ->
            assertEquals(listOf("Issue 1.cbr/01.jpg", "Issue 2.cbr/01.jpg"), src.pagePaths)
            assertEquals("issue2", src.text(1))
        }
        val outer = File(tmp.root, "book.cbr").apply {
            writeBytes(Fixtures.rar5Bytes(entry("Issue 1.cbz", Fixtures.zipBytes(entry("01.jpg", "inner".toByteArray())))))
        }
        val reader = ArchiveReader.open(outer, "UTF-8")!!
        ArchiveSource(reader, NestedArchives.keyOf(outer), NestedArchives(cache, "UTF-8")).use { src ->
            assertEquals(listOf("Issue 1.cbz/01.jpg"), src.pagePaths)
            assertEquals("inner", src.text(0))
        }
    }

    @Test
    fun partlyReadEntryLeavesTheNextReadWhole() {
        for (bytes in listOf(Fixtures.rar4Bytes(*pages), Fixtures.rar5Bytes(*pages))) {
            ArchiveSource(ArchiveReader.open(file(bytes), "UTF-8")!!).use { src ->
                assertEquals('0'.code, src.readPage(0) { it.read() })
                assertEquals("02.jpg", src.text(1))
                assertEquals("01.jpg", src.text(0))
            }
        }
    }

    @Test
    fun entriesOfAnArchiveNotSolidAreReadStraightFromTheirHeaders() {
        for (bytes in listOf(Fixtures.rar4Bytes(*big), Fixtures.rar5Bytes(*big))) {
            RarReader(file(bytes)).use { r ->
                assertEquals(false, r.solid)
                assertTrue(r.randomAccess)
                val order = listOf(5, 0, 3, 3, 1, 4, 2)
                for (i in order) assertArrayEquals("entry $i", big[i].second, r.bytes(i))
                // The first entry's header follows the main header anyway.
                assertEquals(order.count { it != 0 }, r.spliced)
                assertEquals(0, r.unspliced)
            }
        }
    }

    @Test
    fun oneFailedViewKeepsSplicingWhileASecondOrAMisplacedHeaderStopsIt() {
        val bytes = Fixtures.rar5Bytes(*big)
        val starts = FileChannel.open(file(bytes).toPath(), StandardOpenOption.READ).use { Rar5Headers.list(it).map { h -> h.start } }
        fun open() = Faulty(FileChannel.open(file(bytes).toPath(), StandardOpenOption.READ)).let { RarReader.of(it) to it }
        open().let { (r, channel) ->
            r.use {
                // A read error on the view: the walk from the start finds the entry; the next read splices again.
                channel.failAt = starts[3]
                assertArrayEquals(big[3].second, r.bytes(3))
                assertEquals(0 to 1, r.spliced to r.unspliced)
                assertArrayEquals(big[4].second, r.bytes(4))
                assertEquals(1 to 1, r.spliced to r.unspliced)
                // A second one: reads walk from the start from then on.
                channel.failAt = starts[2]
                assertArrayEquals(big[2].second, r.bytes(2))
                assertArrayEquals(big[5].second, r.bytes(5))
                assertEquals(1 to 2, r.spliced to r.unspliced)
            }
        }
        open().let { (r, channel) ->
            r.use {
                // The view lands on the next entry's header: no more views at once.
                channel.failAt = starts[3]
                channel.substitute = starts[4]
                assertArrayEquals(big[3].second, r.bytes(3))
                assertArrayEquals(big[1].second, r.bytes(1))
                assertEquals(0 to 1, r.spliced to r.unspliced)
            }
        }
    }

    @Test
    fun aSolidArchiveIsListedByItsHeadersAndReadForward() {
        val bytes = Fixtures.rar5Bytes(*big, solid = true)
        val f = file(bytes)
        Counting(FileChannel.open(f.toPath(), StandardOpenOption.READ)).let { channel ->
            RarReader.of(channel).use { r ->
                assertEquals(true, r.solid)
                assertFalse(r.randomAccess)
                assertEquals(big.map { it.first to it.second.size.toLong() }, r.entries.map { it.path to it.size })
                val listed = channel.read
                assertTrue("listing read $listed bytes", listed < 4096)
                for (i in big.indices) assertArrayEquals("entry $i", big[i].second, r.bytes(i))
                val forward = channel.read - listed
                assertTrue("in order read $forward of ${bytes.size} bytes", forward < bytes.size + 64 * 1024)
                // Back: unpacks from the start again.
                assertArrayEquals(big[2].second, r.bytes(2))
                assertEquals(0, r.spliced)
            }
        }
    }

    @Test
    fun anArchiveWhoseMainHeaderIsNotReadIsReadForward() {
        // An empty RAR 4 block of another type before the main header: the solid flag is unknown.
        val block = byteArrayOf(0x7A, 0, 0, 7, 0)
        val crc = CRC32().apply { update(block) }.value.toInt()
        val plain = Fixtures.rar4Bytes(*big)
        val bytes = plain.copyOf(7) + byteArrayOf(crc.toByte(), (crc ushr 8).toByte()) + block + plain.copyOfRange(7, plain.size)
        RarReader(file(bytes)).use { r ->
            assertEquals(null, r.solid)
            assertFalse(r.randomAccess)
            for (i in listOf(4, 1, 5)) assertArrayEquals("entry $i", big[i].second, r.bytes(i))
            assertEquals(0, r.spliced)
        }
    }

    @Test
    fun realRar5ArchivesAreListedAndAStoredEntryReadStraight() {
        // The CC0 RAR test files (resources/rar/README.md): a compressed JPEG, then a stored PNG.
        fun resource(name: String) = file(javaClass.getResourceAsStream("/rar/$name")!!.use { it.readBytes() })
        RarReader(resource("testfile.rar5.cbr")).use { r ->
            assertEquals(false, r.solid)
            assertEquals(listOf("testfile.jpg" to 220L, "testfile.png" to 87L), r.entries.map { it.path to it.size })
            // Stored, so the engine here reads it, its CRC checked: past a main header with a locator record.
            val png = r.bytes(1)
            assertEquals(87, png.size)
            assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))
            assertEquals(1, r.spliced)
        }
        RarReader(resource("testfile.rar5.solid.cbr")).use { r ->
            assertEquals(true, r.solid)
            assertFalse(r.randomAccess)
            assertEquals(listOf("testfile.jpg" to 220L, "testfile.png" to 87L), r.entries.map { it.path to it.size })
        }
    }

    @Test
    fun aSolidRar4IsListedWithoutUnpackingAndReadForward() {
        // Listing jumps over each entry's data, as for an archive that is not solid; reading unpacks every entry up to the one asked for.
        fun listingRead(bytes: ByteArray) = Counting(FileChannel.open(file(bytes).toPath(), StandardOpenOption.READ)).let { channel ->
            RarReader.of(channel).use { channel.read }
        }
        val plainListed = listingRead(Fixtures.rar4Bytes(*big))
        val bytes = Fixtures.rar4Bytes(*big, solid = true)
        val f = file(bytes)
        Counting(FileChannel.open(f.toPath(), StandardOpenOption.READ)).let { channel ->
            RarReader.of(channel).use { r ->
                assertEquals(true, r.solid)
                assertFalse(r.randomAccess)
                assertEquals(big.map { it.first to it.second.size.toLong() }, r.entries.map { it.path to it.size })
                val listed = channel.read
                assertTrue("listing read $listed bytes, $plainListed when not solid", listed <= plainListed)
                assertArrayEquals(big[3].second, r.bytes(3))
                for (i in big.indices) assertArrayEquals("entry $i", big[i].second, r.bytes(i))
                assertEquals(0, r.spliced)
            }
        }
    }

    @Test
    fun realRar4ArchivesListTheEntriesOfTheRar5OnesInTheirOwnOrder() {
        // The CC0 RAR test files (resources/rar/README.md): the same two compressed images in each.
        fun channel(name: String) = Counting(FileChannel.open(file(javaClass.getResourceAsStream("/rar/$name")!!.use { it.readBytes() }).toPath(), StandardOpenOption.READ))
        val rar5 = RarReader.of(channel("testfile.rar5.cbr")).use { r -> r.entries.map { it.path to it.size } }
        RarReader.of(channel("testfile.rar5.solid.cbr")).use { r -> assertEquals(rar5, r.entries.map { it.path to it.size }) }
        val plain = channel("testfile.rar3.cbr")
        RarReader.of(plain).use { r ->
            assertEquals(false, r.solid)
            assertEquals(rar5, r.entries.map { it.path to it.size })
        }
        val solid = channel("testfile.rar3.solid.cbr")
        RarReader.of(solid).use { r ->
            assertEquals(true, r.solid)
            assertFalse(r.randomAccess)
            // Solid RAR 4 groups by extension: the PNG first.
            assertEquals(rar5.reversed(), r.entries.map { it.path to it.size })
        }
        // Listing jumps over the solid entries' data as over the others'.
        assertTrue("listing read ${solid.read} bytes, ${plain.read} when not solid", solid.read <= plain.read)
    }

    @Test
    fun aRar5WhoseDictionaryIsOver64MiBDoesNotOpen() {
        // libarchive unpacks with 64 MiB at most: a solid one is turned down by its headers, the other by libarchive.
        // Only the last entry's being over still turns the whole book down: its pages from there on would be lost.
        for (solid in listOf(true, false)) {
            for (bits in listOf<(Int) -> Int>({ 10 }, { if (it == pages.size - 1) 10 else 0 })) {
                assertThrows("solid $solid", RefusedRar::class.java) { RarReader(file(Fixtures.rar5Bytes(*pages, solid = solid, dictBits = bits))) }
            }
            RarReader(file(Fixtures.rar5Bytes(*pages, solid = solid, dictBits = { 9 }))).use { assertEquals("02.jpg", String(it.bytes(0))) }
        }
    }

    @Test
    fun anEncryptedArchiveDoesNotOpen() {
        val encrypted = listOf(
            Fixtures.rar4Bytes(*pages, encrypted = true),
            Fixtures.rar5Bytes(*pages, encrypted = true),
            Fixtures.rar5Bytes(*pages, solid = true, encrypted = true),
            Fixtures.rar5Bytes(*pages, headersEncrypted = true)
        )
        for ((i, bytes) in encrypted.withIndex()) {
            val f = file(bytes)
            assertThrows("archive $i", IOException::class.java) { ArchiveReader.open(f, "UTF-8") }
        }
    }

    /** Where RAR 4 entry [i] of [entries]' block starts: past the signature, main header and earlier entries. */
    private fun rar4Block(entries: Array<Pair<String, ByteArray>>, i: Int): Int =
        7 + 13 + entries.take(i).sumOf { 32 + it.first.length + it.second.size }

    @Test
    fun aDamagedHeaderEndsTheListingKeepingTheEntriesBeforeIt() {
        val rar4 = Fixtures.rar4Bytes(*big).also { it[rar4Block(big, 3)] = (it[rar4Block(big, 3)] + 1).toByte() }
        val plain5 = Fixtures.rar5Bytes(*big)
        val third = FileChannel.open(file(plain5).toPath(), StandardOpenOption.READ).use { Rar5Headers.list(it)[3].start.toInt() }
        val rar5 = plain5.also { it[third] = (it[third] + 1).toByte() }
        val solid5 = Fixtures.rar5Bytes(*big, solid = true).also { it[third] = (it[third] + 1).toByte() }
        for (bytes in listOf(rar4, rar5, solid5)) {
            RarReader(file(bytes)).use { r ->
                assertEquals(big.take(3).map { it.first }, r.entries.map { it.path })
                assertArrayEquals(big[2].second, r.bytes(2))
            }
        }
    }

    @Test
    fun anArchiveWhoseFirstHeaderIsDamagedDoesNotOpen() {
        val rar4 = Fixtures.rar4Bytes(*big).also { it[rar4Block(big, 0)] = (it[rar4Block(big, 0)] + 1).toByte() }
        assertThrows(IOException::class.java) { RarReader(file(rar4)) }
        assertThrows(IOException::class.java) { RarReader(file("Rar!".toByteArray() + ByteArray(100))) }
    }

    @Test
    fun anEntryWhoseDataIsDamagedFailsAloneWhenRead() {
        val at = rar4Block(big, 1) + 32 + big[1].first.length + 100
        val rar4 = Fixtures.rar4Bytes(*big).also { it[at] = (it[at] + 1).toByte() }
        RarReader(file(rar4)).use { r ->
            assertEquals(big.size, r.entries.size)
            assertThrows(IOException::class.java) { r.bytes(1) }
            assertArrayEquals(big[0].second, r.bytes(0))
            assertArrayEquals(big[2].second, r.bytes(2))
        }
    }

    @Test
    fun aTruncatedArchiveListsTheEntriesUpToTheCut() {
        val cut = rar4Block(big, 3) + 32 + big[3].first.length + 500
        RarReader(file(Fixtures.rar4Bytes(*big).copyOf(cut))).use { r ->
            assertEquals(big.take(4).map { it.first }, r.entries.map { it.path })
            assertArrayEquals(big[2].second, r.bytes(2))
            assertThrows(IOException::class.java) { r.bytes(3) }
        }
        // A solid RAR 5, listed by its own headers, the same way.
        val solid = Fixtures.rar5Bytes(*big, solid = true)
        val fourth = FileChannel.open(file(solid).toPath(), StandardOpenOption.READ).use { Rar5Headers.list(it)[4].start.toInt() }
        RarReader(file(solid.copyOf(fourth - 500))).use { r ->
            assertEquals(big.take(4).map { it.first }, r.entries.map { it.path })
            assertArrayEquals(big[2].second, r.bytes(2))
            assertThrows(IOException::class.java) { r.bytes(3) }
        }
    }

    @Test
    fun aClosedReaderReadsNoMore() {
        val r = RarReader(file(Fixtures.rar5Bytes(*big)))
        assertArrayEquals(big[1].second, r.bytes(1))
        r.close()
        assertThrows(IOException::class.java) { r.bytes(2) }
        r.close()
    }

    @Test
    fun closeDoesNotWaitForAReadUnderWay() {
        for (bytes in listOf(Fixtures.rar5Bytes(*big), Fixtures.rar5Bytes(*big, solid = true))) {
            val r = RarReader(file(bytes))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val outcome = AtomicReference<Result<Int>>()
            val reader = Thread {
                outcome.set(runCatching { r.read(r.entries[2]) { entered.countDown(); release.await(); it.readBytes().size } })
            }.apply { start() }
            try {
                awaitUntil("read parked in its block") { entered.count == 0L }
                val closer = Thread { r.close() }.apply { start() }
                awaitUntil("close returns while the read is parked") { !closer.isAlive }
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
            }
            awaitUntil("read ends") { !reader.isAlive }
            // The read failed on the closed channel or ended with its entry whole.
            outcome.get().onSuccess { assertEquals(big[2].second.size, it) }
            assertFalse(r.holdsHandle)
            assertThrows(IOException::class.java) { r.bytes(0) }
        }
    }

    @Test
    fun readsFromSeveralThreadsEachGetTheirEntry() {
        for (bytes in listOf(Fixtures.rar5Bytes(*big), Fixtures.rar5Bytes(*big, solid = true))) {
            RarReader(file(bytes)).use { r ->
                val failures = Collections.synchronizedList(ArrayList<String>())
                val threads = (0 until 4).map { t ->
                    Thread {
                        for (i in big.indices.shuffled(Random(t))) {
                            val got = runCatching { r.bytes(i) }
                            if (got.getOrNull()?.contentEquals(big[i].second) != true) failures += "thread $t entry $i: $got"
                        }
                    }.apply { start() }
                }
                awaitUntil("readers done") { threads.none { it.isAlive } }
                assertEquals(emptyList<String>(), failures)
            }
        }
    }

    @Test
    fun aSplicedViewPutsThePrefixBeforeTheBytesFromItsStart() {
        val f = file("0123456789".toByteArray())
        FileChannel.open(f.toPath(), StandardOpenOption.READ).use { channel ->
            val view = Spliced(channel, "AB".toByteArray(), 6)
            assertEquals(6, view.size())
            val all = ByteBuffer.allocate(16)
            while (view.read(all) >= 0) Unit
            assertEquals("AB6789", String(all.array(), 0, all.position()))
            view.position(1)
            val two = ByteBuffer.allocate(2)
            view.read(two)
            assertEquals("B", String(two.array(), 0, two.position()))
            view.position(3)
            two.clear()
            view.read(two)
            assertEquals("78", String(two.array()))
            assertTrue(channel.isOpen)
        }
    }

    /** [inner], counting the bytes read through it. */
    private class Counting(private val inner: SeekableByteChannel) : SeekableByteChannel by inner {
        var read = 0L

        override fun read(dst: ByteBuffer): Int = inner.read(dst).also { if (it > 0) read += it }
    }

    /**
     * [inner], whose first read starting at [failAt] fails, or, with [substitute] set, returns
     * the bytes from there instead; each is used once.
     */
    private class Faulty(private val inner: SeekableByteChannel) : SeekableByteChannel by inner {
        @Volatile
        var failAt = -1L

        @Volatile
        var substitute = -1L

        override fun read(dst: ByteBuffer): Int {
            val at = inner.position()
            if (at != failAt) return inner.read(dst)
            failAt = -1
            val from = substitute.also { substitute = -1 }
            if (from < 0) throw IOException("Read failed")
            inner.position(from)
            val n = inner.read(dst)
            inner.position(at + n.coerceAtLeast(0))
            return n
        }
    }
}
