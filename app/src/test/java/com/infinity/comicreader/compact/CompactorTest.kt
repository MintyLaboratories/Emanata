package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.ArchiveReader
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A book rebuilt: a flat `.cbz` of stored entries in natural order, pages made anew under
 * their own names, other files as they were, OS litter left out; checked before it takes
 * its place, beside the book or in its place.
 */
class CompactorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** "Pages" of [size] bytes of [fill]: a `.png` one is "compacted" to a tenth as `.jxl`. */
    private fun page(fill: Int, size: Int = 2000) = ByteArray(size) { fill.toByte() }

    private val halve: (ByteArray) -> PageWork.Made? = { bytes -> if (bytes[0] == PNG_FILL) PageWork.Made(bytes.copyOf(bytes.size / 10), "jxl") else null }

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>, deflated: Boolean = false): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, data) in entries) {
                val e = ZipEntry(path)
                if (!deflated) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(e)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sevenZip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File(tmp.root, name)
        SevenZOutputFile(file).use { out ->
            // Stored, as a book of JPEGs nearly is.
            out.setContentCompression(SevenZMethod.COPY)
            for ((path, data) in entries) {
                val e = out.createArchiveEntry(File(tmp.root, "x"), path)
                e.size = data.size.toLong()
                out.putArchiveEntry(e)
                out.write(data)
                out.closeArchiveEntry()
            }
        }
        return file
    }

    private val scratch get() = tmp.newFolder()

    private val alive = object : Compactor.Progress {
        override val alive = true

        override fun report(done: Long, total: Long) = Unit
    }

    private fun run(book: File, inPlace: Boolean = false, skip: Set<Int> = emptySet(), smallerOnly: Boolean = true, workers: Int = 2): Compactor.Result {
        val request = Compactor.Request(book, halve, inPlace, "compacted", skip, smallerOnly)
        return Compactor(scratch, workers).run(request, { ArchiveReader.open(book, "UTF-8")!! }, alive)
    }

    private fun entries(file: File): List<Pair<String, ByteArray>> = ZipFile(file).use { z ->
        z.entries().toList().map { it.name to z.getInputStream(it).readBytes() }
    }

    private val book = listOf(
        "Book/10.png" to page(PNG),
        "Book/9.png" to page(PNG),
        "Book/2.jpg" to page(JPG),
        "Book/ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
        "__MACOSX/Book/._2.jpg" to page(1, 10),
        "Book/Thumbs.db" to page(2, 10)
    )

    @Test
    fun aBookBecomesAFlatStoredCbzBesideItInNaturalOrder() {
        val source = zip("Book.cbz", book)
        val sourceBytes = source.readBytes()
        val result = run(source) as Compactor.Result.Rebuilt
        assertEquals("Book (compacted).cbz", result.file.name)
        assertNull(result.removed)
        // The book itself is untouched.
        assertArrayEquals(sourceBytes, source.readBytes())
        val out = entries(result.file)
        assertEquals(listOf("2.jpg", "9.jxl", "10.jxl", "ComicInfo.xml"), out.map { it.first })
        assertArrayEquals(page(JPG), out[0].second)
        assertEquals(200, out[1].second.size)
        assertArrayEquals("<ComicInfo/>".toByteArray(), out[3].second)
        ZipFile(result.file).use { z -> assertTrue(z.entries().toList().all { it.method == ZipEntry.STORED }) }
        assertEquals(source.length(), result.before)
        assertEquals(result.file.length(), result.after)
        // Pages 2, 9, 10 stay in place.
        assertArrayEquals(intArrayOf(0, 1, 2), result.moves)
        assertEquals(3, result.pageCount)
        assertEquals(source.lastModified() / 2000, result.file.lastModified() / 2000)
        // Nothing left behind.
        assertEquals(listOf("Book (compacted).cbz", "Book.cbz"), tmp.root.list()!!.filter { it.startsWith("Book") }.sorted())
    }

    @Test
    fun inPlaceANonZipBookBecomesACbzAndItsFileGoes() {
        val source = sevenZip("Book.cb7", book)
        source.setLastModified(1_600_000_000_000)
        val result = run(source, inPlace = true) as Compactor.Result.Rebuilt
        assertEquals("Book.cbz", result.file.name)
        // It keeps the book's time.
        assertEquals(1_600_000_000_000 / 2000, result.file.lastModified() / 2000)
        assertEquals(source, result.removed)
        assertFalse(source.exists())
        assertEquals(listOf("2.jpg", "9.jxl", "10.jxl", "ComicInfo.xml"), entries(result.file).map { it.first })
    }

    @Test
    fun inPlaceAZipBookIsReplacedUnderItsName() {
        val source = zip("Book.cbz", book)
        val result = run(source, inPlace = true) as Compactor.Result.Rebuilt
        assertEquals(source, result.file)
        assertNull(result.removed)
        assertEquals(listOf("2.jpg", "9.jxl", "10.jxl", "ComicInfo.xml"), entries(source).map { it.first })
        assertTrue(tmp.root.list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun aBookWithNothingToMakeAnewIsLeftAlone() {
        val source = zip("Plain.cbz", listOf("1.jpg" to page(JPG), "2.jpg" to page(JPG)))
        val before = source.readBytes()
        assertEquals(Compactor.Why.NOTHING, (run(source, inPlace = true) as Compactor.Result.Unchanged).why)
        assertArrayEquals(before, source.readBytes())
        assertEquals(listOf("Plain.cbz"), tmp.root.list()!!.filter { it.contains("Plain") })
    }

    @Test
    fun aNewFileNoSmallerIsDroppedWhenItMustBeSmaller() {
        // A large text deflated in the book is stored in the new file.
        val source = zip("Wordy.cbz", listOf("1.png" to page(PNG, 100), "notes.txt" to ByteArray(200_000) { 'a'.code.toByte() }), deflated = true)
        assertEquals(Compactor.Why.NOT_SMALLER, (run(source, inPlace = true) as Compactor.Result.Unchanged).why)
        assertTrue(source.exists())
        assertTrue(run(source, smallerOnly = false) is Compactor.Result.Rebuilt)
    }

    @Test
    fun repeatedNamesTakeTheirFolderAndPagesFollowTheirNames() {
        val source = zip("Two.cbz", listOf("b/01.png" to page(PNG), "a/01.png" to page(PNG), "a/02.jpg" to page(JPG)))
        val result = run(source) as Compactor.Result.Rebuilt
        assertEquals(listOf("a_01.jxl", "a_02.jpg", "b_01.jxl"), entries(result.file).map { it.first })
        assertArrayEquals(intArrayOf(0, 1, 2), result.moves)
    }

    @Test
    fun skippedPagesAreLeftOutAndTheRestMoveUp() {
        val source = zip("Skip.cbz", listOf("1.jpg" to page(JPG), "2.jpg" to page(JPG), "3.png" to page(PNG)))
        val result = run(source, skip = setOf(1)) as Compactor.Result.Rebuilt
        assertEquals(listOf("1.jpg", "3.jxl"), entries(result.file).map { it.first })
        assertArrayEquals(intArrayOf(0, -1, 1), result.moves)
        assertEquals(2, result.pageCount)
        // Leaving a page out is a change of its own.
        val plain = zip("Skip2.cbz", listOf("1.jpg" to page(JPG), "2.jpg" to page(JPG)))
        assertTrue(run(plain, skip = setOf(0), smallerOnly = false) is Compactor.Result.Rebuilt)
    }

    @Test
    fun pagesThatFailToConvertAreKeptAndNamed() {
        val source = zip("Mixed.cbz", listOf("1.png" to page(PNG), "2.png" to page(PNG, 3000), "3.png" to page(PNG, 4000)))
        val failing: (ByteArray) -> PageWork.Made? = { if (it.size == 3000) throw PageWork.Unconverted("no") else if (it.size == 4000) throw OutOfMemoryError() else halve(it) }
        val request = Compactor.Request(source, failing, inPlace = false, suffix = "compacted")
        val result = Compactor(scratch, 2).run(request, { ArchiveReader.open(source, "UTF-8")!! }, alive) as Compactor.Result.Rebuilt
        assertEquals(listOf("2.png", "3.png"), result.failed)
        val out = entries(result.file)
        assertEquals(listOf("1.jxl", "2.png", "3.png"), out.map { it.first })
        assertArrayEquals(page(PNG, 3000), out[1].second)
        // Nothing made anew: left alone, the pages named all the same.
        val none: (ByteArray) -> PageWork.Made? = { throw PageWork.Unconverted("no") }
        val unchanged = Compactor(scratch, 2).run(Compactor.Request(source, none, inPlace = false, suffix = "compacted"), { ArchiveReader.open(source, "UTF-8")!! }, alive)
        assertEquals(listOf("1.png", "2.png", "3.png"), (unchanged as Compactor.Result.Unchanged).failed)
    }

    @Test
    fun aBookHoldingArchivesIsLeftAlone() {
        val inner = zip("inner.cbz", listOf("1.png" to page(PNG))).readBytes()
        val source = zip("Outer.cbz", listOf("Issue 1.cbz" to inner, "cover.png" to page(PNG)))
        assertEquals(Compactor.Why.NESTED, (run(source) as Compactor.Result.Unchanged).why)
    }

    @Test
    fun cancelingLeavesTheBookAndNothingElse() {
        val source = zip("Long.cbz", (1..20).map { "$it.png" to page(PNG) })
        val before = source.readBytes()
        val reports = AtomicInteger()
        val progress = object : Compactor.Progress {
            override val alive: Boolean get() = reports.get() < 3

            override fun report(done: Long, total: Long) {
                reports.incrementAndGet()
            }
        }
        val dir = scratch
        val request = Compactor.Request(source, halve, inPlace = true, suffix = "compacted")
        assertThrows(CancellationException::class.java) {
            Compactor(dir, 1).run(request, { ArchiveReader.open(source, "UTF-8")!! }, progress)
        }
        assertArrayEquals(before, source.readBytes())
        assertEquals(listOf("Long.cbz"), tmp.root.list()!!.filter { it.contains("Long") })
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun noMoreThanTheLimitConvertAtOnce() {
        val inside = AtomicInteger()
        var most = 0
        val slow: (ByteArray) -> PageWork.Made? = {
            val now = inside.incrementAndGet()
            synchronized(this) { most = maxOf(most, now) }
            Thread.sleep(20)
            inside.decrementAndGet()
            halve(it)
        }
        val source = zip("Many.cbz", (1..12).map { "$it.png" to page(PNG) })
        val request = Compactor.Request(source, slow, inPlace = false, suffix = "compacted")
        val result = Compactor(scratch, 4, limit = { 2 }).run(request, { ArchiveReader.open(source, "UTF-8")!! }, alive)
        assertTrue(result is Compactor.Result.Rebuilt)
        assertTrue("at most 2 at once, saw $most", most in 1..2)
    }

    @Test
    fun noMoreConvertAtOnceThanTheMemoryHolds() {
        val inside = AtomicInteger()
        var most = 0
        val slow: (ByteArray) -> PageWork.Made? = {
            val now = inside.incrementAndGet()
            synchronized(this) { most = maxOf(most, now) }
            Thread.sleep(20)
            inside.decrementAndGet()
            halve(it)
        }
        val source = zip("Big.cbz", (1..8).map { "$it.png" to page(PNG) })
        val request = Compactor.Request(source, slow, inPlace = false, suffix = "compacted", cost = { 6000L })
        val result = Compactor(scratch, 4, memory = 10_000).run(request, { ArchiveReader.open(source, "UTF-8")!! }, alive)
        assertTrue(result is Compactor.Result.Rebuilt)
        assertEquals(1, most)
        // A page whose need is unknown, or beyond the memory, converts alone.
        val alone = Compactor.Request(source, halve, inPlace = false, suffix = "compacted", cost = { Long.MAX_VALUE })
        assertTrue(Compactor(scratch, 4, memory = 10_000).run(alone, { ArchiveReader.open(source, "UTF-8")!! }, alive) is Compactor.Result.Rebuilt)
    }

    private companion object {
        const val PNG = 0x50
        const val JPG = 0x4A
        val PNG_FILL = PNG.toByte()
    }
}
