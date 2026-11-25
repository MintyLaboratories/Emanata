package minty.emanata.reader

import minty.emanata.reader.Fixtures.entry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

class NestedArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cache get() = File(tmp.root, "cache")

    private val warnings = ArrayList<String>()

    private fun nested(
        maxBytes: Long = NestedArchives.MAX_CACHE_BYTES,
        maxEntryBytes: Long = NestedArchives.MAX_ENTRY_BYTES,
        canceled: () -> Boolean = { false },
        copy: Boolean = true
    ) = NestedArchives(cache, "UTF-8", maxBytes, maxEntryBytes, canceled, copy) { message, _ -> warnings += message }

    private fun open(file: File, nested: NestedArchives = nested(), pageLimit: Int = Int.MAX_VALUE) =
        ArchiveSource(ZipReader(file, "UTF-8"), NestedArchives.keyOf(file), nested, pageLimit)

    private fun ArchiveSource.text(index: Int) = readPage(index) { String(it.readBytes()) }

    private fun cached() = cache.listFiles()?.filter { it.isFile }.orEmpty()

    private fun sevenZipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
        Fixtures.sevenZip(File(tmp.newFolder(), "inner.cb7"), *entries).readBytes()

    /** A book holding one archive per issue, mixed formats, a zip named `.cbr` and junk alongside. */
    private fun omnibus(): File = Fixtures.zip(
        File(tmp.root, "omnibus.cbr"),
        entry("Set/Issue 10.cbr", Fixtures.zipBytes(entry("01.jpg", "issue10/01".toByteArray()))),
        entry("Set/Issue 2.cbz", Fixtures.zipBytes(entry("02.jpg", "issue2/02".toByteArray()), entry("01.jpg", "issue2/01".toByteArray()))),
        entry("Set/Issue 1.cb7", sevenZipBytes(entry("p/01.jpg", "issue1/01".toByteArray()))),
        entry("Set/000 cover.jpg"),
        entry("__MACOSX/Set/._Issue 2.cbz"),
        entry("Set/notes.txt")
    )

    @Test
    fun innerArchivePagesInNaturalOrder() {
        open(omnibus()).use { src ->
            assertEquals(
                listOf(
                    "Set/000 cover.jpg",
                    "Set/Issue 1.cb7/p/01.jpg",
                    "Set/Issue 2.cbz/01.jpg",
                    "Set/Issue 2.cbz/02.jpg",
                    "Set/Issue 10.cbr/01.jpg"
                ),
                src.pagePaths
            )
            assertEquals("Set/000 cover.jpg", src.text(0))
            assertEquals("issue1/01", src.text(1))
            assertEquals("issue2/02", src.text(3))
            assertEquals("issue10/01", src.text(4))
        }
        assertEquals(3, cached().size)
    }

    @Test
    fun tarInsideZipAndZipInsideTar() {
        val book = Fixtures.zip(
            File(tmp.root, "set.cbz"),
            entry("Issue 1.cbt", Fixtures.tarBytes(entry("01.jpg", "tar/01".toByteArray()))),
            entry("Issue 2.cbz", Fixtures.zipBytes(entry("01.jpg", "zip/01".toByteArray())))
        )
        open(book).use { src ->
            assertEquals(listOf("Issue 1.cbt/01.jpg", "Issue 2.cbz/01.jpg"), src.pagePaths)
            assertEquals("tar/01", src.text(0))
        }
        val tar = Fixtures.tar(
            File(tmp.root, "set.cbt"),
            entry("Issue 1.cbz", Fixtures.zipBytes(entry("01.jpg", "zip/01".toByteArray())))
        )
        ArchiveSource(TarReader(tar, "UTF-8"), NestedArchives.keyOf(tar), nested()).use { src ->
            assertEquals(listOf("Issue 1.cbz/01.jpg"), src.pagePaths)
            assertEquals("zip/01", src.text(0))
        }
    }

    @Test
    fun innerArchiveBesideLooseImagesSortsByName() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("b.jpg"),
            entry("a.cbz", Fixtures.zipBytes(entry("z.jpg"))),
            entry("a.jpg"),
            entry("a2.jpg")
        )
        open(f).use { assertEquals(listOf("a.cbz/z.jpg", "a.jpg", "a2.jpg", "b.jpg"), it.pagePaths) }
    }

    @Test
    fun innerArchivesSortByFileName() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Issue 1.5.cbz", Fixtures.zipBytes(entry("01.jpg"))),
            entry("Issue 1 extra.cbz", Fixtures.zipBytes(entry("01.jpg"))),
            entry("Issue 1.cbz", Fixtures.zipBytes(entry("01.jpg")))
        )
        open(f).use {
            assertEquals(listOf("Issue 1.cbz/01.jpg", "Issue 1 extra.cbz/01.jpg", "Issue 1.5.cbz/01.jpg"), it.pagePaths)
        }
    }

    @Test
    fun fewInnerArchivesStayOpen() {
        val issues = (1..6).map { n -> entry("Issue $n.cbz", Fixtures.zipBytes(entry("01.jpg", "issue$n".toByteArray()))) }
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), *issues.toTypedArray())
        ArchiveSource(ZipReader(f, "UTF-8"), NestedArchives.keyOf(f), nested(), maxOpenInner = 2).use { src ->
            assertTrue(src.openInnerCount <= 2)
            for (i in 0 until 6) assertEquals("issue${i + 1}", src.text(i))
            assertEquals(2, src.openInnerCount)
            // Closed for room, reopened from its copy.
            assertEquals("issue1", src.text(0))
            assertEquals(2, src.openInnerCount)
        }
    }

    @Test
    fun concurrentReadsSurviveEviction() {
        val issues = (1..4).map { n -> entry("Issue $n.cb7", sevenZipBytes(entry("01.jpg", "issue$n".toByteArray()))) }
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), *issues.toTypedArray())
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        ArchiveSource(ZipReader(f, "UTF-8"), NestedArchives.keyOf(f), nested(), maxOpenInner = 1).use { src ->
            List(4) { t ->
                thread {
                    try {
                        repeat(50) { i ->
                            val page = (t + i) % 4
                            assertEquals("issue${page + 1}", src.text(page))
                        }
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }.forEach { it.join() }
        }
        if (failures.isNotEmpty()) throw failures.first()
    }

    @Test(expected = IOException::class)
    fun closedSourceDoesNotReopen() {
        val src = open(omnibus())
        src.close()
        src.text(4)
    }

    @Test
    fun copiesAreReused() {
        val f = omnibus()
        open(f).use { }
        val first = cached().associate { it.name to it.readBytes() }
        open(f).use { assertEquals(5, it.pageCount) }
        val second = cached()
        assertEquals(first.keys, second.map { it.name }.toSet())
        for (c in second) assertArrayEquals(first.getValue(c.name), c.readBytes())
    }

    @Test
    fun coverExtractsOnlyItsOwnArchive() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Issue 2.cbz", Fixtures.zipBytes(entry("01.jpg", "issue2".toByteArray()))),
            entry("Issue 1.cbz", Fixtures.zipBytes(entry("01.jpg", "issue1".toByteArray())))
        )
        open(f, pageLimit = 1).use { src ->
            assertEquals(listOf("Issue 1.cbz/01.jpg"), src.pagePaths)
            assertEquals("issue1", src.text(0))
        }
        assertEquals(1, cached().size)
    }

    @Test
    fun coverSkipsEmptyInnerArchive() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Issue 1.cbz", Fixtures.zipBytes(entry("notes.txt"))),
            entry("Issue 2.cbz", Fixtures.zipBytes(entry("01.jpg")))
        )
        open(f, pageLimit = 1).use { assertEquals(listOf("Issue 2.cbz/01.jpg"), it.pagePaths) }
    }

    @Test
    fun nestingStopsAtDepthCap() {
        var inner = Fixtures.zipBytes(entry("d4.jpg"))
        inner = Fixtures.zipBytes(entry("d3.jpg"), entry("l4.cbz", inner))
        inner = Fixtures.zipBytes(entry("d2.jpg"), entry("l3.cbz", inner))
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("d1.jpg"), entry("l2.cbz", inner))
        open(f).use { src ->
            assertEquals(listOf("d1.jpg", "l2.cbz/d2.jpg", "l2.cbz/l3.cbz/d3.jpg"), src.pagePaths)
            assertEquals("d3.jpg", src.text(2))
        }
    }

    @Test
    fun withoutNestedArchivesInnerArchivesAreIgnored() {
        ArchiveSource(ZipReader(omnibus(), "UTF-8")).use { assertEquals(listOf("Set/000 cover.jpg"), it.pagePaths) }
    }

    @Test
    fun oversizedInnerArchiveIsSkipped() {
        val big = Fixtures.zipBytes(entry("01.jpg", Random(1).nextBytes(4096)))
        val small = Fixtures.zipBytes(entry("01.jpg"))
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("a.cbz", big), entry("b.cbz", small))
        open(f, nested(maxEntryBytes = 1024)).use { assertEquals(listOf("b.cbz/01.jpg"), it.pagePaths) }
    }

    @Test
    fun bookBudgetCapsCopies() {
        val a = Fixtures.zipBytes(entry("01.jpg", ByteArray(600)))
        val b = Fixtures.zipBytes(entry("01.jpg", ByteArray(600)))
        val f = Fixtures.zip(File(tmp.root, "book.cbz"), entry("a.cbz", a), entry("b.cbz", b))
        open(f, nested(maxBytes = a.size + 100L)).use { assertEquals(listOf("a.cbz/01.jpg"), it.pagePaths) }
    }

    @Test
    fun corruptInnerArchiveIsSkipped() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("a.cbr", "not an archive".toByteArray()),
            entry("b.cbz", Fixtures.zipBytes(entry("01.jpg")))
        )
        open(f).use { assertEquals(listOf("b.cbz/01.jpg"), it.pagePaths) }
        assertEquals(listOf("Could not open nested archive a.cbr"), warnings)
    }

    @Test(expected = NestedArchives.StorageException::class)
    fun unwritableCacheFailsTheOpen() {
        val f = omnibus()
        cache.writeText("a file where the cache folder belongs")
        open(f).use { }
    }

    @Test
    fun canceledOpenFailsAndLeavesNoCopies() {
        val f = omnibus()
        // Canceled after the first check, so mid-way through the first copy.
        var checks = 0
        val nested = nested(canceled = { checks++ > 0 })
        try {
            open(f, nested).use { }
            throw AssertionError("expected UnavailableException")
        } catch (expected: NestedArchives.UnavailableException) {
        }
        assertEquals(0, cached().size)
    }

    @Test
    fun canceledCopyStops() {
        var reads = 0
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return len
            }
        }
        try {
            NestedArchives.copyBounded(endless, ByteArrayOutputStream(), Long.MAX_VALUE) { reads >= 3 }
            throw AssertionError("expected UnavailableException")
        } catch (expected: NestedArchives.UnavailableException) {
        }
        assertEquals(3, reads)
    }

    @Test
    fun withoutCopyingOnlyCachedCopiesOpen() {
        val f = omnibus()
        try {
            open(f, nested(copy = false)).use { }
            throw AssertionError("expected UnavailableException")
        } catch (expected: NestedArchives.UnavailableException) {
        }
        assertEquals(0, cached().size)
        open(f).use { }
        open(f, nested(copy = false)).use { assertEquals(5, it.pageCount) }
    }

    @Test(expected = NestedArchives.StorageException::class)
    fun failedWriteIsStorageError() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }
        NestedArchives.copyBounded(ByteArrayInputStream(ByteArray(10)), broken, 10)
    }

    @Test
    fun copyStopsPastLimit() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, NestedArchives.copyBounded(ByteArrayInputStream(ByteArray(10)), out, 10))
        try {
            NestedArchives.copyBounded(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), 10)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun trimDropsLeastRecentlyUsed() {
        cache.mkdirs()
        val old = File(cache, "old.cbz").apply { writeBytes(ByteArray(100)); setLastModified(1_000_000) }
        val mid = File(cache, "mid.cbz").apply { writeBytes(ByteArray(100)); setLastModified(2_000_000) }
        val kept = File(cache, "kept.cbz").apply { writeBytes(ByteArray(100)); setLastModified(500_000) }
        val stalePart = File(cache, "x.cbz.part").apply { writeBytes(ByteArray(1)); setLastModified(1) }
        NestedArchives.retain(kept)
        try {
            NestedArchives.trim(cache, maxBytes = 150)
        } finally {
            NestedArchives.release(kept)
        }
        assertTrue(kept.exists())
        assertTrue(!old.exists() && !mid.exists())
        assertTrue(!stalePart.exists())
    }

    @Test
    fun trimSparesCopiesOfOpenBooks() {
        val f = omnibus()
        open(f).use { src ->
            NestedArchives.trim(cache, maxBytes = 0)
            assertEquals(3, cached().size)
            assertEquals("issue10/01", src.text(4))
        }
        NestedArchives.trim(cache, maxBytes = 0)
        assertEquals(0, cached().size)
    }

    @Test
    fun concurrentOpensAndTrimsKeepEveryPage() {
        val f = omnibus()
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val stop = AtomicBoolean(false)
        val trimmer = thread { while (!stop.get()) NestedArchives.trim(cache, maxBytes = 0) }
        val readers = List(4) {
            thread {
                try {
                    repeat(20) {
                        open(f).use { src ->
                            assertEquals(5, src.pageCount)
                            assertEquals("issue10/01", src.text(4))
                        }
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        readers.forEach { it.join() }
        stop.set(true)
        trimmer.join()
        if (failures.isNotEmpty()) throw failures.first()
    }
}
