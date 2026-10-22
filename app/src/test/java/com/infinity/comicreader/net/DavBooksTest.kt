package com.infinity.comicreader.net

import android.app.Application
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.BookFactsReader
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.Fixtures.entry
import com.infinity.comicreader.reader.PageSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Books on a WebDAV share: zips read in place over ranges; everything fetched whole from a server without them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DavBooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var share: File
    private lateinit var cacheDir: File
    private lateinit var server: FakeDavServer

    private val big = Random(9).nextBytes(2 shl 20)
    private val pages = arrayOf(entry("01.jpg"), entry("02.jpg", big), entry("03.jpg"))

    @Before
    fun setUp() {
        share = tmp.newFolder("share")
        cacheDir = tmp.newFolder("cache")
        server = FakeDavServer(share)
        Remotes.serve(listOf(server.share(id = "d1"))) { DavFs(it) }
        Fixtures.zip(File(share, "a.cbz"), *pages)
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        server.close()
    }

    private fun remote(name: String) = Remotes.file("/net/d1/$name") as RemoteFile

    private fun copies() = RemoteCache.dirIn(cacheDir).listFiles()?.size ?: 0

    @Test
    fun aFolderListsAndAZipOpensInPlace() {
        assertEquals(listOf("a.cbz"), Remotes.list("/net/d1").map { it.name })
        PageSources.open(remote("a.cbz"), "UTF-8", cacheDir, fetch = PageSources.Fetch()).use { src ->
            assertEquals(3, src.pageCount)
        }
        PageSources.openCover(remote("a.cbz"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        assertEquals(0, copies())
        assertTrue("sent ${server.bytesSent} bytes", server.bytesSent < big.size / 2)
    }

    @Test
    fun oneFilesServerErrorLeavesTheShareReachable() {
        Fixtures.zip(File(share, "b.cbz"), entry("01.jpg"))
        server.errors["b.cbz"] = 500
        try {
            Remotes.open("/net/d1/b.cbz")
            fail("opened a file the server failed")
        } catch (e: IOException) {
            assertFalse("$e", e is RemoteException)
        }
        assertFalse(Remotes.unreachable("d1"))
        Remotes.open("/net/d1/a.cbz").close()
        // A gateway's trouble is the whole share's.
        server.errors["b.cbz"] = 503
        try {
            Remotes.open("/net/d1/b.cbz")
            fail("opened a file the server failed")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.UNREACHABLE, e.reason)
        }
        assertTrue(Remotes.unreachable("d1"))
        // So is a server error at the share's top.
        Remotes.retry("d1")
        server.errors[""] = 500
        try {
            Remotes.list("/net/d1")
            fail("listed a failing top")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.UNREACHABLE, e.reason)
        }
    }

    @Test
    fun withoutRangesBooksAreFetchedWholeAndCoversWaitForThat() {
        server.ranges = false
        try {
            PageSources.openCover(remote("a.cbz"), "UTF-8", cacheDir)
            fail("a cover read from a server without ranges")
        } catch (e: IOException) {
            // Expected: covers never fetch a whole book.
        }
        try {
            ArchiveReader.open(remote("a.cbz"), "UTF-8")
            fail("read in place without ranges")
        } catch (e: IOException) {
            // Expected.
        }
        assertEquals(0, copies())
        PageSources.open(remote("a.cbz"), "UTF-8", cacheDir).use { assertEquals(3, it.pageCount) }
        assertEquals(1, copies())
        val sent = server.bytesSent
        // From then on the copy serves covers and facts alike.
        PageSources.openCover(remote("a.cbz"), "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        assertEquals(3, BookFactsReader.read(remote("a.cbz"), "UTF-8", cacheDir).pages)
        assertEquals(sent, server.bytesSent)
    }
}
