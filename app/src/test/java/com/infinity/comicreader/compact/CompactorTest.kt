package com.infinity.comicreader.compact

import com.infinity.comicreader.net.ChangeRefused
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
    fun foldersArePrefixedToKeepTheReadingOrderAndDotFoldersGo() {
        val source = zip(
            "Parts.cbz",
            listOf(
                "Part B/01.png" to page(PNG),
                "Part A/02.png" to page(PNG),
                "Part A/.thumbs/02.png" to page(PNG)
            )
        )
        val result = run(source) as Compactor.Result.Rebuilt
        assertEquals(listOf("Part A_02.jxl", "Part B_01.jxl"), entries(result.file).map { it.first })
        assertArrayEquals(intArrayOf(0, 1), result.moves)
        assertEquals(2, result.pageCount)
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
    fun aFileReadDamagedStopsTheRebuild() {
        // Noise: deflating stores it as it is, so it can be found in the archive.
        val random = java.util.Random(3)
        val damaged = ByteArray(3000).also { random.nextBytes(it) }
        val marker = String(damaged, 1000, 100, Charsets.ISO_8859_1)
        for (deflated in listOf(false, true)) {
            val source = zip(if (deflated) "Deflated.cbz" else "Stored.cbz", listOf("1.png" to page(PNG), "2.jpg" to damaged, "notes.txt" to "abc".repeat(500).toByteArray()), deflated)
            // A byte of the page's data changed where the archive holds it, its CRC left as it was.
            val bytes = source.readBytes()
            val at = String(bytes, Charsets.ISO_8859_1).indexOf(marker) + 50
            assertTrue(at > 50)
            bytes[at] = (bytes[at] + 1).toByte()
            source.writeBytes(bytes)
            assertThrows(java.io.IOException::class.java) { run(source, inPlace = true) }
            assertArrayEquals(bytes, source.readBytes())
        }
        // Text read whole and checked too.
        val text = zip("Text.cbz", listOf("1.png" to page(PNG), "notes.txt" to "x".repeat(500).toByteArray()))
        val bytes = text.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("x".repeat(100)) + 50
        bytes[at] = 'y'.code.toByte()
        text.writeBytes(bytes)
        assertThrows(java.io.IOException::class.java) { run(text, inPlace = true) }
    }

    @Test
    fun withoutRoomForThePiecesAndTheNewFileNothingIsWritten() {
        val source = zip("Full.cbz", (1..4).map { "$it.png" to page(PNG) })
        val before = source.readBytes()
        val request = Compactor.Request(source, halve, inPlace = true, suffix = "compacted", smallerOnly = true)
        // The pieces and the new file need the files' size each, on one volume.
        val need = 2 * 4 * 2000L + Compactor.SPARE_BYTES
        assertThrows(ChangeRefused::class.java) {
            Compactor(scratch, 2, free = { need - 1 }).run(request, { ArchiveReader.open(source, "UTF-8")!! }, alive)
        }
        // Room at first, then none: the run stops as it fills up, leaving nothing behind.
        val dir = scratch
        var calls = 0
        val shrinking: (File) -> Long = { if (calls++ == 0) need else Compactor.SPARE_BYTES }
        assertThrows(ChangeRefused::class.java) {
            Compactor(dir, 2, free = shrinking).run(request, { ArchiveReader.open(source, "UTF-8")!! }, alive)
        }
        assertArrayEquals(before, source.readBytes())
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(tmp.root.list()!!.none { it.endsWith(".part") })
        assertTrue(Compactor(scratch, 2, free = { need }).run(request, { ArchiveReader.open(source, "UTF-8")!! }, alive) is Compactor.Result.Rebuilt)
    }

    @Test
    fun newFilesLeftByRunsThatEndedEarlyAreSweptOnceStale() {
        val source = zip("Swept.cbz", listOf("1.png" to page(PNG)))
        val stale = File(tmp.root, ".Other.cbr.123456789.part").apply { writeBytes(ByteArray(10)) }
        stale.setLastModified(System.currentTimeMillis() - Compactor.STALE_PART_MS - 60_000)
        val fresh = File(tmp.root, ".Other.cbr.987654321.part").apply { writeBytes(ByteArray(10)) }
        val mine = File(tmp.root, ".notes.part").apply { writeBytes(ByteArray(10)) }
        mine.setLastModified(stale.lastModified())
        run(source)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(mine.exists())
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
    fun cancelingWaitsForThePagesUnderWay() {
        val inside = AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val slow: (ByteArray) -> PageWork.Made? = {
            inside.incrementAndGet()
            started.countDown()
            // As libjxl does, it goes on whatever happens.
            val end = System.nanoTime() + 300_000_000
            while (System.nanoTime() < end) Unit
            inside.decrementAndGet()
            halve(it)
        }
        val source = zip("Slow.cbz", (1..6).map { "$it.png" to page(PNG) })
        val progress = object : Compactor.Progress {
            override val alive: Boolean get() = started.count > 0

            override fun report(done: Long, total: Long) = Unit
        }
        val request = Compactor.Request(source, slow, inPlace = true, suffix = "compacted")
        assertThrows(CancellationException::class.java) {
            Compactor(scratch, 2).run(request, { ArchiveReader.open(source, "UTF-8")!! }, progress)
        }
        assertEquals(0, inside.get())
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

    /** A folder of loose [images] in the temporary folder. */
    private fun folder(name: String, images: List<Pair<String, ByteArray>>): File {
        val dir = File(tmp.root, name).apply { mkdirs() }
        for ((n, data) in images) File(dir, n).writeBytes(data)
        return dir
    }

    private val loose = listOf("10.png" to page(PNG), "2.jpg" to page(JPG), "1.jpg" to page(JPG, 1500))

    private fun pack(dir: File, convert: (ByteArray) -> PageWork.Made?, target: File, progress: Compactor.Progress = alive, work: File = scratch): Compactor.Result {
        val images = PackFolders.of(dir).images
        val request = Compactor.Request(dir, convert, inPlace = false, suffix = "", target = target, always = true, smallerOnly = false)
        return Compactor(work, 2).run(request, { FolderReader(images) }, progress)
    }

    @Test
    fun aFolderPacksIntoAStoredCbzOfItsImagesAsTheyAre() {
        val dir = folder("Loose", loose)
        File(dir, "1.jpg").setLastModified(1_700_000_000_000)
        File(dir, "2.jpg").setLastModified(1_700_000_100_000)
        File(dir, "10.png").setLastModified(1_600_000_000_000)
        val work = scratch
        val result = pack(dir, { null }, File(tmp.root, "Loose.cbz"), work = work) as Compactor.Result.Rebuilt
        assertEquals(File(tmp.root, "Loose.cbz"), result.file)
        val out = entries(result.file)
        assertEquals(listOf("1.jpg", "2.jpg", "10.png"), out.map { it.first })
        for ((name, data) in out) assertArrayEquals(File(dir, name).readBytes(), data)
        ZipFile(result.file).use { z -> assertTrue(z.entries().toList().all { it.method == ZipEntry.STORED }) }
        assertEquals(loose.sumOf { it.second.size.toLong() }, result.before)
        assertEquals(result.file.length(), result.after)
        assertArrayEquals(intArrayOf(0, 1, 2), result.moves)
        assertEquals(3, result.pageCount)
        // Dated as its newest image; the folder itself is left for the caller.
        assertEquals(1_700_000_100_000 / 2000, result.file.lastModified() / 2000)
        assertEquals(setOf("1.jpg", "2.jpg", "10.png"), dir.list()!!.toSet())
        // Images kept as they are go in from where they are: nothing was written aside.
        assertTrue(work.list()!!.isEmpty())
    }

    @Test
    fun aFolderPacksCompactedWithItsPagesMadeAnew() {
        val dir = folder("Loose", loose)
        val result = pack(dir, halve, File(tmp.root, "Loose.cbz")) as Compactor.Result.Rebuilt
        val out = entries(result.file)
        assertEquals(listOf("1.jpg", "2.jpg", "10.jxl"), out.map { it.first })
        assertEquals(200, out[2].second.size)
        assertArrayEquals(page(JPG, 1500), out[0].second)
        assertTrue(result.before > result.after)
    }

    @Test
    fun aGivenTargetIsReplaced() {
        val dir = folder("Loose", loose)
        val target = File(tmp.root, "Loose.cbz").apply { writeText("old") }
        val result = pack(dir, { null }, target) as Compactor.Result.Rebuilt
        assertEquals(target, result.file)
        assertEquals(3, entries(target).size)
        assertEquals(listOf("Loose", "Loose.cbz"), tmp.root.list()!!.filter { it.startsWith("Loose") }.sorted())
    }

    @Test
    fun cancelingPackingLeavesTheFolderAndNoFile() {
        val dir = folder("Long", (1..20).map { "$it.png" to page(PNG) })
        val reports = AtomicInteger()
        val progress = object : Compactor.Progress {
            override val alive: Boolean get() = reports.get() < 3

            override fun report(done: Long, total: Long) {
                reports.incrementAndGet()
            }
        }
        assertThrows(CancellationException::class.java) { pack(dir, halve, File(tmp.root, "Long.cbz"), progress) }
        assertEquals(20, dir.list()!!.size)
        assertEquals(listOf("Long"), tmp.root.list()!!.filter { it.contains("Long") })
    }

    @Test
    fun anImageChangedWhilePackedFailsTheCheck() {
        val dir = folder("Loose", loose)
        val images = PackFolders.of(dir).images
        // The image changes after it was read, before it is packed.
        val changing: (ByteArray) -> PageWork.Made? = { bytes ->
            if (bytes[0] == JPG.toByte() && bytes.size == 1500) File(dir, "1.jpg").writeBytes(page(JPG + 1, 1500))
            null
        }
        val request = Compactor.Request(dir, changing, inPlace = false, suffix = "", target = File(tmp.root, "Loose.cbz"), always = true, smallerOnly = false)
        assertThrows(java.io.IOException::class.java) { Compactor(scratch, 1).run(request, { FolderReader(images) }, alive) }
        assertFalse(File(tmp.root, "Loose.cbz").exists())
    }

    private companion object {
        const val PNG = 0x50
        const val JPG = 0x4A
        val PNG_FILL = PNG.toByte()
    }
}
