package com.infinity.comicreader.net

import android.app.Application
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.ArchiveSource
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.Fixtures.entry
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.RarReader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** Books on a share: archives read in place, solid ones streamed once with their pages kept; PDFs and images fetched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteBooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var share: File
    private lateinit var cacheDir: File
    private lateinit var fake: FakeRemoteFs

    private val pages = arrayOf(entry("02.jpg"), entry("10.jpg"), entry("01.jpg"), entry("notes.txt"))

    @Before
    fun setUp() {
        share = tmp.newFolder("share")
        cacheDir = tmp.newFolder("cache")
        fake = FakeRemoteFs(share)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun remote(name: String) = Remotes.file("/net/s1/$name") as RemoteFile

    private fun copies() = RemoteCache.dirIn(cacheDir).listFiles()?.size ?: 0

    private fun ArchiveSource.text(index: Int) = readPage(index) { String(it.readBytes()) }

    private fun checkInPlace(name: String) {
        val reader = ArchiveReader.open(remote(name), "UTF-8")!!
        ArchiveSource(reader).use { src ->
            assertEquals(listOf("01.jpg", "02.jpg", "10.jpg"), src.pagePaths)
            assertEquals("10.jpg", src.text(2))
        }
        assertEquals(0, copies())
    }

    @Test
    fun everyArchiveKindReadsInPlace() {
        Fixtures.zip(File(share, "a.cbz"), *pages)
        File(share, "b.cbr").writeBytes(Fixtures.rar4Bytes(*pages))
        File(share, "c.cbr").writeBytes(Fixtures.rar5Bytes(*pages))
        Fixtures.sevenZip(File(share, "d.cb7"), *pages)
        File(share, "e.cbt").writeBytes(Fixtures.tarBytes(*pages))
        for (name in listOf("a.cbz", "b.cbr", "c.cbr", "d.cb7", "e.cbt")) checkInPlace(name)
    }

    @Test
    fun theKindComesFromTheBytesOnTheShareReadOnce() {
        Fixtures.zip(File(share, "zip.cbr"), *pages)
        assertEquals(ArchiveKind.ZIP, ArchiveKind.detect(remote("zip.cbr")))
        val read = fake.bytesRead
        assertEquals(ArchiveKind.ZIP, ArchiveKind.detect(remote("zip.cbr")))
        assertEquals(read, fake.bytesRead)
    }

    @Test
    fun aZipOpensInPlaceReadingOnlyWhatItNeeds() {
        val big = ByteArray(4 shl 20) { (it % 253).toByte() }
        Fixtures.zip(File(share, "a.cbz"), entry("01.jpg"), entry("02.jpg", big), entry("03.jpg"))
        PageSources.open(remote("a.cbz"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
            assertEquals(3, src.pageCount)
        }
        assertEquals(0, copies())
        assertTrue("read ${fake.bytesRead} bytes", fake.bytesRead < big.size / 2)
    }

    @Test
    fun aZipOfManyPagesOpensWithoutReadingEachPage() {
        val random = Random(7)
        val many = Array(40) { entry("%02d.jpg".format(it), random.nextBytes(512 * 1024)) }
        Fixtures.zip(File(share, "many.cbz"), *many)
        PageSources.open(remote("many.cbz"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
            assertEquals(40, src.pageCount)
        }
        // Only the central directory at the end: no page's local header.
        assertTrue("read ${fake.bytesRead} bytes", fake.bytesRead < 3L * 1024 * 1024)
    }

    @Test
    fun rarAndTarListingsFetchLittleAroundEachHeader() {
        val random = Random(11)
        val many = Array(20) { entry("%02d.jpg".format(it), random.nextBytes(512 * 1024)) }
        File(share, "many.cbr").writeBytes(Fixtures.rar4Bytes(*many))
        File(share, "many.cbt").writeBytes(Fixtures.tarBytes(*many))
        for (name in listOf("many.cbr", "many.cbt")) {
            val before = fake.bytesRead
            ArchiveReader.open(remote(name), "UTF-8")!!.use { assertEquals(20, it.entries.size) }
            val read = fake.bytesRead - before
            // A small fetch around each of 20 headers, never a page's worth.
            assertTrue("$name: read $read bytes", read < 20L * 2 * RemoteChannel.HEADER_BLOCK_BYTES)
        }
        assertEquals(0, copies())
    }

    private fun bigPages(count: Int, seed: Int) = Random(seed).let { r -> Array(count) { entry("%02d.jpg".format(it), r.nextBytes(256 * 1024)) } }

    @Test
    fun theReaderReadsRarAnd7zThatAreNotSolidInPlace() {
        val big = bigPages(8, 3)
        File(share, "b.cbr").writeBytes(Fixtures.rar4Bytes(*big))
        Fixtures.sevenZip(File(share, "c.cb7"), *big)
        for (name in listOf("b.cbr", "c.cb7")) {
            val before = fake.bytesRead
            PageSources.open(remote(name), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
                assertEquals(8, src.pageCount)
                src.pageSize(6)
            }
            val read = fake.bytesRead - before
            assertTrue("$name: read $read of ${File(share, name).length()} bytes", read < File(share, name).length() / 2)
        }
        assertEquals(0, copies())
        assertEquals(0, RemoteCache.pagesIn(cacheDir).list()?.size ?: 0)
    }

    @Test
    fun aRarPageReadBackFetchesItsOwnEntryAlone() {
        val big = bigPages(12, 17)
        File(share, "b.cbr").writeBytes(Fixtures.rar4Bytes(*big))
        (ArchiveReader.open(remote("b.cbr"), "UTF-8") as RarReader).use { r ->
            r.read(r.entries[11]) { it.readBytes() }
            val before = fake.bytesRead
            assertArrayEquals(big[3].second, r.read(r.entries[3]) { it.readBytes() })
            val read = fake.bytesRead - before
            // Straight to its header: its data and a little around it, no walk past the headers before it.
            assertTrue("read $read bytes", read < 2L * 256 * 1024)
            assertEquals(2, r.spliced)
            assertEquals(0, r.unspliced)
        }
        assertEquals(0, copies())
    }

    @Test
    fun aSolidRarIsStreamedOnceKeepingItsPages() {
        val big = bigPages(6, 5)
        File(share, "s.cbr").writeBytes(Fixtures.rar5Bytes(*big, solid = true))
        PageSources.open(remote("s.cbr"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
            assertEquals(6, src.pageCount)
            src.pageSize(3)
            val streamed = fake.bytesRead
            // Pages passed on the way are kept: reading them again fetches nothing.
            for (i in 0..3) src.pageSize(i)
            assertEquals(streamed, fake.bytesRead)
        }
        assertEquals(0, copies())
        val kept = RemoteCache.pagesIn(cacheDir).listFiles()!!.single()
        assertEquals(4, kept.list()!!.size)
        // Opened again, kept pages come from the cache: only the headers are fetched.
        val before = fake.bytesRead
        PageSources.open(remote("s.cbr"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
            src.pageSize(2)
        }
        assertTrue("read ${fake.bytesRead - before} bytes", fake.bytesRead - before < File(share, "s.cbr").length() / 2)
    }

    @Test
    fun factsOfASolidRarSkipAComicInfoStoredAfterThePages() {
        val big = bigPages(4, 13)
        val info = entry("ComicInfo.xml", "<ComicInfo><Year>2004</Year></ComicInfo>".toByteArray())
        File(share, "late.cbr").writeBytes(Fixtures.rar5Bytes(*big, info, solid = true))
        File(share, "early.cbr").writeBytes(Fixtures.rar5Bytes(info, *big, solid = true))
        val before = fake.bytesRead
        val late = com.infinity.comicreader.reader.BookFactsReader.read(remote("late.cbr"), "UTF-8", cacheDir)
        assertEquals(4, late.pages)
        assertEquals(0, late.year)
        assertTrue("read ${fake.bytesRead - before} bytes", fake.bytesRead - before < File(share, "late.cbr").length() / 2)
        assertEquals(2004, com.infinity.comicreader.reader.BookFactsReader.read(remote("early.cbr"), "UTF-8", cacheDir).year)
    }

    @Test
    fun keptPagesAreTrimmedWithTheCopies() {
        val big = bigPages(4, 9)
        File(share, "s.cbr").writeBytes(Fixtures.rar5Bytes(*big, solid = true))
        PageSources.open(remote("s.cbr"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
            src.pageSize(3)
        }
        assertEquals(1, RemoteCache.pagesIn(cacheDir).list()!!.size)
        RemoteCache.trim(RemoteCache.dirIn(cacheDir), maxBytes = 1024)
        assertEquals(0, RemoteCache.pagesIn(cacheDir).list()!!.size)
    }

    @Test
    fun coversAndPeeksOfARarReadInPlace() {
        File(share, "b.cbr").writeBytes(Fixtures.rar4Bytes(*pages))
        PageSources.openCover(remote("b.cbr"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        PageSources.open(remote("b.cbr"), "UTF-8", cacheDir).use { assertEquals(3, it.pageCount) }
        assertEquals(0, copies())
    }

    @Test
    fun aPdfNotFetchedHasNoCoverAndIsNotFetchedForOne() {
        File(share, "book.pdf").writeBytes("%PDF-1.4\n".toByteArray() + ByteArray(4096))
        try {
            PageSources.openCover(remote("book.pdf"), "UTF-8", cacheDir)
            fail("a cover from a PDF not fetched")
        } catch (e: IOException) {
            // Expected: covers never fetch a whole PDF.
        }
        assertEquals(0, copies())
        assertEquals(0L, fake.bytesRead)
    }

    @Test
    fun inPlaceOnlyOpensWhatReadsInPlaceAndNeverFetchesOrStreams() {
        Fixtures.zip(File(share, "a.cbz"), *pages)
        File(share, "s.cbr").writeBytes(Fixtures.rar5Bytes(*bigPages(4, 9), solid = true))
        File(share, "book.pdf").writeBytes("%PDF-1.4\n".toByteArray() + ByteArray(4096))
        PageSources.open(remote("a.cbz"), "UTF-8", cacheDir, inPlaceOnly = true).use { assertEquals(3, it.pageCount) }
        for (name in listOf("s.cbr", "book.pdf")) {
            try {
                PageSources.open(remote(name), "UTF-8", cacheDir, inPlaceOnly = true).close()
                fail("$name opened in place only")
            } catch (e: IOException) {
                // Expected: it would be streamed or fetched whole.
            }
        }
        assertEquals(0, copies())
        assertEquals(0, RemoteCache.pagesIn(cacheDir).list()?.size ?: 0)
        fake.seekable = false
        try {
            PageSources.open(remote("a.cbz"), "UTF-8", cacheDir, inPlaceOnly = true).close()
            fail("a book from a share sending files only whole")
        } catch (e: IOException) {
            // Expected.
        }
        assertEquals(0, copies())
    }

    @Test
    fun aLooseImageIsFetched() {
        File(share, "page.png").writeBytes(ByteArray(100))
        PageSources.open(remote("page.png"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        assertEquals(1, copies())
    }
}
