package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Fixtures.entry
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
import kotlin.random.Random

class NestedArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cache get() = File(tmp.root, "cache")

    private fun nested(maxBytes: Long = NestedArchives.MAX_CACHE_BYTES, maxEntryBytes: Long = NestedArchives.MAX_ENTRY_BYTES) =
        NestedArchives(cache, "UTF-8", maxBytes, maxEntryBytes)

    private fun open(file: File, nested: NestedArchives = nested(), firstPageOnly: Boolean = false) =
        ArchiveSource(ZipReader(file, "UTF-8"), NestedArchives.keyOf(file), nested, firstPageOnly)

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
    fun innerArchiveBesideLooseImagesSortsAsFolder() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("b.jpg"),
            entry("a.cbz", Fixtures.zipBytes(entry("z.jpg"))),
            entry("a.jpg")
        )
        open(f).use { assertEquals(listOf("a.jpg", "a.cbz/z.jpg", "b.jpg"), it.pagePaths) }
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
        open(f, firstPageOnly = true).use { src ->
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
        open(f, firstPageOnly = true).use { assertEquals(listOf("Issue 2.cbz/01.jpg"), it.pagePaths) }
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
        NestedArchives.trim(cache, maxBytes = 150, keep = setOf(kept))
        assertTrue(kept.exists())
        assertTrue(!old.exists() && !mid.exists())
        assertTrue(!stalePart.exists())
    }
}
