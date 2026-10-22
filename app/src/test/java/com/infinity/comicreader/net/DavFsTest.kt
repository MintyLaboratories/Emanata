package com.infinity.comicreader.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** WebDAV shares against a server on this machine: listing, stats, ranged and rangeless reads, failures. */
class DavFsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var server: FakeDavServer
    private val opened = ArrayList<DavFs>()

    @Before
    fun setUp() {
        dir = tmp.newFolder("share")
        server = FakeDavServer(dir)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        server.close()
    }

    private fun fs(share: Share = server.share()) = DavFs(share).also { opened += it }

    private fun expectFailure(reason: RemoteException.Reason, lasting: Boolean = false, block: () -> Unit) {
        try {
            block()
            fail("no failure")
        } catch (e: RemoteException) {
            assertEquals(reason, e.reason)
            assertEquals(lasting, e.lasting)
        }
    }

    @Test
    fun listsAFolderWithDecodedNamesSizesAndTimes() {
        File(dir, "Sub Folder").mkdirs()
        File(dir, "Sub Folder/inner.cbz").writeBytes(ByteArray(5))
        File(dir, "a b #1.cbz").writeBytes(ByteArray(1234))
        File(dir, "café 100%.cbr").writeBytes(ByteArray(10))
        File(dir, "a b #1.cbz").setLastModified(1_700_000_000_000L)
        val found = fs().list("").sortedBy { it.name }
        assertEquals(listOf("Sub Folder", "a b #1.cbz", "café 100%.cbr"), found.map { it.name })
        assertEquals(listOf(true, false, false), found.map { it.isDirectory })
        assertEquals(1234L, found[1].size)
        assertEquals(1_700_000_000_000L, found[1].modified)
        val propfind = server.requests.single()
        assertEquals("PROPFIND", propfind.method)
        assertEquals("/dav/", propfind.path)
        assertEquals("1", propfind.getHeader("Depth"))
        // Names below the top are escaped on the way out.
        assertEquals(listOf("inner.cbz"), fs().list("Sub Folder").map { it.name })
        assertEquals("/dav/Sub%20Folder/", server.paths("PROPFIND").last())
    }

    @Test
    fun statsAFileOrFolderAndNothingWhereNothingIs() {
        File(dir, "Series").mkdirs()
        File(dir, "Series/001.cbz").writeBytes(ByteArray(77))
        val fs = fs()
        assertEquals(RemoteStat("001.cbz", false, 77, File(dir, "Series/001.cbz").lastModified() / 1000 * 1000, 0), fs.stat("Series/001.cbz"))
        assertTrue(fs.stat("Series")!!.isDirectory)
        assertNull(fs.stat("Series/002.cbz"))
        assertEquals("0", server.requests.last().getHeader("Depth"))
    }

    @Test
    fun signsInWithBasicOnlyWhenGivenCredentials() {
        server.user = "user"
        server.password = "pw"
        File(dir, "x.cbz").writeBytes(ByteArray(1))
        assertEquals(listOf("x.cbz"), fs(server.share(user = "user", password = "pw")).list("").map { it.name })
        expectFailure(RemoteException.Reason.DENIED, lasting = true) { fs(server.share(user = "user", password = "wrong")).list("") }
        server.password = ""
        fs(server.share(user = "", password = "")).list("")
        assertNull(server.requests.last().getHeader("Authorization"))
    }

    @Test
    fun aMissingTopIsAMissingShareAndAGoneHostIsUnreachable() {
        val missing = server.share().copy(path = DavUrl(false, "localhost", server.url().port, "elsewhere"))
        expectFailure(RemoteException.Reason.NO_SHARE) { fs(missing).list("") }
        expectFailure(RemoteException.Reason.MISSING) { fs().list("no/such") }
        val gone = MockWebServer().apply { start() }
        val port = gone.port
        gone.shutdown()
        expectFailure(RemoteException.Reason.UNREACHABLE) { fs(server.share().copy(path = DavUrl(false, "localhost", port, ""))).list("") }
    }

    @Test
    fun serverTroubleIsUnreachableAndNoWebDavIsNoShare() {
        val other = MockWebServer()
        other.enqueue(MockResponse().setResponseCode(503))
        other.enqueue(MockResponse().setResponseCode(405))
        other.start()
        try {
            val share = server.share().copy(path = DavUrl(false, "localhost", other.port, ""))
            expectFailure(RemoteException.Reason.UNREACHABLE) { fs(share).list("") }
            expectFailure(RemoteException.Reason.NO_SHARE) { fs(share).list("") }
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun spaceComesFromTheQuota() {
        assertEquals(3_000_000_000L to 8_000_000_000L, fs().space())
        server.quota = null
        assertNull(fs().space())
    }

    @Test
    fun rangedReadsFetchWhatIsAskedAndMoreAheadWhenReadingOn() {
        val bytes = Random(3).nextBytes(3 shl 20)
        File(dir, "big.cbz").writeBytes(bytes)
        val fs = fs()
        fs.open("big.cbz").use { input ->
            assertEquals(bytes.size.toLong(), input.length)
            assertEquals("bytes=0-65535", server.requests.last().getHeader("Range"))
            // From the end, as a zip is read: just what is asked.
            val tail = ByteArray(1000)
            assertEquals(1000, input.read(bytes.size - 1000L, tail, 0, 1000))
            assertArrayEquals(bytes.copyOfRange(bytes.size - 1000, bytes.size), tail)
            assertEquals("bytes=${bytes.size - 1000}-${bytes.size - 1}", server.requests.last().getHeader("Range"))
            // The head came with opening.
            val head = ByteArray(100)
            val before = server.requests.size
            assertEquals(100, input.read(10, head, 0, 100))
            assertEquals(before, server.requests.size)
            assertArrayEquals(bytes.copyOfRange(10, 110), head)
            // Reading on from there fetches ahead, further each time.
            val all = ByteArray(bytes.size)
            var at = 0
            while (at < all.size) {
                val n = input.read(at.toLong(), all, at, minOf(64 * 1024, all.size - at))
                assertTrue(n > 0)
                at += n
            }
            assertArrayEquals(bytes, all)
            assertTrue("${server.requests.size} requests", server.requests.size - before < 12)
        }
        assertTrue(fs.seekable("big.cbz"))
    }

    @Test
    fun withoutRangesAFileStreamsFromItsStartAndIsNotSeekable() {
        server.ranges = false
        val bytes = Random(5).nextBytes(300_000)
        File(dir, "big.cbr").writeBytes(bytes)
        val fs = fs()
        fs.open("big.cbr").use { input ->
            assertEquals(bytes.size.toLong(), input.length)
            val part = ByteArray(1000)
            assertEquals(1000, input.read(100_000, part, 0, 1000))
            assertArrayEquals(bytes.copyOfRange(100_000, 101_000), part)
            // Behind the stream: from the start again.
            assertEquals(1000, input.read(50_000, part, 0, 1000))
            assertArrayEquals(bytes.copyOfRange(50_000, 51_000), part)
            assertEquals(2, server.paths("GET").size)
        }
        assertFalse(fs.seekable("big.cbr"))
        assertEquals(2, server.paths("GET").size)
    }

    @Test
    fun aSmallFileComesWholeAndTellsNothingOfRanges() {
        server.ranges = false
        File(dir, "small.png").writeBytes(ByteArray(500) { it.toByte() })
        val fs = fs()
        fs.open("small.png").use { input ->
            val all = ByteArray(500)
            assertEquals(500, input.read(0, all, 0, 500))
            assertEquals(-1, input.read(500, all, 0, 1))
            assertEquals(1, server.paths("GET").size)
        }
        assertTrue(fs.seekable("small.png"))
    }

    @Test
    fun anEmptyFileOpens() {
        File(dir, "empty.cbz").writeBytes(ByteArray(0))
        fs().open("empty.cbz").use { input ->
            assertEquals(0L, input.length)
            assertEquals(-1, input.read(0, ByteArray(1), 0, 1))
        }
    }

    @Test
    fun closingTheShareStopsItsFiles() {
        File(dir, "a.cbz").writeBytes(ByteArray(200_000))
        val fs = fs()
        val input = fs.open("a.cbz")
        fs.close()
        expectFailure(RemoteException.Reason.UNKNOWN_SHARE) { input.read(100_000, ByteArray(10), 0, 10) }
        expectFailure(RemoteException.Reason.UNKNOWN_SHARE) { fs.list("") }
    }

    @Test
    fun contentRangesParse() {
        assertEquals(Triple(0L, 99L, 1000L), DavFs.parseContentRange("bytes 0-99/1000"))
        assertEquals(Triple(5L, 9L, -1L), DavFs.parseContentRange("bytes 5-9/*"))
        assertEquals(Triple(-1L, -1L, 0L), DavFs.parseContentRange("bytes */0"))
        assertNull(DavFs.parseContentRange("bytes 9-5/10"))
        assertNull(DavFs.parseContentRange("bytes 0-10/10"))
        assertNull(DavFs.parseContentRange("items 0-1/2"))
        assertNull(DavFs.parseContentRange(null))
    }
}
