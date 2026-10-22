package com.infinity.comicreader.net

import android.app.Application
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.ArchiveSource
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.Fixtures.entry
import com.infinity.comicreader.reader.PageSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** Books on a share: zips and covers read in place, other books fetched for the reader only. */
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
    fun theKindComesFromTheBytesOnTheShare() {
        Fixtures.zip(File(share, "zip.cbr"), *pages)
        assertEquals(ArchiveKind.ZIP, ArchiveKind.detect(remote("zip.cbr")))
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
            assertTrue("$name: read $read bytes", read < 2L * 1024 * 1024)
        }
        assertEquals(0, copies())
    }

    @Test
    fun theReaderFetchesARarFirstAndReusesTheCopy() {
        File(share, "b.cbr").writeBytes(Fixtures.rar4Bytes(*pages))
        var progress = 0L to 0L
        PageSources.open(remote("b.cbr"), "UTF-8", cacheDir, fetch = PageSources.Fetch { done, total -> progress = done to total }).use {
            assertEquals(3, it.pageCount)
        }
        assertEquals(1, copies())
        val size = File(share, "b.cbr").length()
        assertEquals(size to size, progress)
        val read = fake.bytesRead
        // Covers and the reader alike use the copy from then on.
        PageSources.openCover(remote("b.cbr"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        PageSources.open(remote("b.cbr"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { assertEquals(3, it.pageCount) }
        assertEquals(read, fake.bytesRead)
    }

    @Test
    fun coversAndPeeksOfARarReadInPlace() {
        File(share, "b.cbr").writeBytes(Fixtures.rar4Bytes(*pages))
        PageSources.openCover(remote("b.cbr"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        PageSources.open(remote("b.cbr"), "UTF-8", cacheDir).use { assertEquals(3, it.pageCount) }
        assertEquals(0, copies())
    }

    @Test
    fun aLooseImageIsFetched() {
        File(share, "page.png").writeBytes(ByteArray(100))
        PageSources.open(remote("page.png"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        assertEquals(1, copies())
    }
}
