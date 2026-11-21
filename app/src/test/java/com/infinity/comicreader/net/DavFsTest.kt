package com.infinity.comicreader.net

import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.awaitUntil
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    fun aLongReadAskedForWholeIsOneRangedRequest() {
        val bytes = Random(5).nextBytes(12 shl 20)
        File(dir, "big.cbz").writeBytes(bytes)
        fs().open("big.cbz").use { input ->
            val before = server.requests.size
            val run = ByteArray(8 shl 20)
            assertEquals(run.size, input.readFully(100_000, run, 0, run.size))
            assertArrayEquals(bytes.copyOfRange(100_000, 100_000 + run.size), run)
            assertEquals(before + 1, server.requests.size)
            assertEquals("bytes=100000-${100_000 + run.size - 1}", server.requests.last().getHeader("Range"))
        }
    }

    @Test
    fun aFileChangedBetweenReadsFailsInsteadOfMixing() {
        val file = File(dir, "big.cbz")
        file.writeBytes(Random(4).nextBytes(1 shl 20))
        file.setLastModified(1_700_000_000_000L)
        fs().open("big.cbz").use { input ->
            val part = ByteArray(1000)
            assertEquals(1000, input.read(500_000, part, 0, 1000))
            assertEquals("\"${1 shl 20}-1700000000000\"", server.requests.last().getHeader("If-Range"))
            file.writeBytes(Random(6).nextBytes(1 shl 20))
            file.setLastModified(1_700_000_100_000L)
            try {
                input.read(900_000, part, 0, 1000)
                fail("read a changed file")
            } catch (e: IOException) {
                assertFalse("$e", e is RemoteException)
                assertTrue("$e", e.message!!.startsWith("Changed"))
            }
        }
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
    fun closingDuringASlowReadCancelsItWithoutWaiting() {
        File(dir, "slow.cbz").writeBytes(Random(7).nextBytes(2 shl 20))
        for (closeShare in listOf(true, false)) {
            server.slow = false
            val fs = fs()
            val input = fs.open("slow.cbz")
            server.slow = true
            val gets = server.paths("GET").size
            val failure = AtomicReference<Throwable>()
            val finished = AtomicBoolean()
            // A megabyte at the slow pace takes about a minute.
            val reader = thread {
                runCatching { input.read(1L shl 20, ByteArray(1 shl 20), 0, 1 shl 20) }.onSuccess { finished.set(true) }.onFailure { failure.set(it) }
            }
            // Under way: the GET sent and its body coming in.
            awaitUntil("the read to take its body in") { server.paths("GET").size > gets && reader.stackTrace.any { it.methodName == "readBody" } }
            assertTrue(reader.isAlive)
            if (closeShare) fs.close() else input.close()
            assertFalse("close waited for the read", finished.get())
            awaitUntil("the canceled read to end") { !reader.isAlive }
            assertFalse("read still going", reader.isAlive)
            val e = failure.get()
            assertTrue("$e", e is IOException)
            if (closeShare) assertEquals(RemoteException.Reason.UNKNOWN_SHARE, (e as RemoteException).reason)
            else assertFalse("$e", e is RemoteException)
        }
    }

    /** A server on this machine answering with [answer]; shut down after the test. */
    private fun mock(answer: (RecordedRequest) -> MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answer(request)
        }
        mocks += this
    }

    private val mocks = ArrayList<MockWebServer>()

    @After
    fun shutMocks() {
        mocks.forEach { it.shutdown() }
    }

    private fun davShare(secure: Boolean, host: String, port: Int, dir: String = "dav") =
        Share("m1", "Share M", DavUrl(secure, host, port, dir), "user", "pw")

    private val emptyListing = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"></D:multistatus>""")

    @Test
    fun theSignInStaysOnItsHostThroughRedirects() {
        val other = mock { emptyListing }
        other.start(InetAddress.getByName("127.0.0.1"), 0)
        val first = mock { MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${other.port}/dav/") }
        first.start()
        assertEquals(emptyList<RemoteStat>(), fs(davShare(false, "localhost", first.port)).list(""))
        assertEquals(Credentials.basic("user", "pw"), first.takeRequest().getHeader("Authorization"))
        val moved = other.takeRequest()
        assertEquals("PROPFIND", moved.method)
        assertNull(moved.getHeader("Authorization"))
    }

    @Test
    fun aFolderMovedToItsSlashKeepsPropfindDepthAndSignIn() {
        val dav = mock { request ->
            when (request.path) {
                "/dav/Series" -> MockResponse().setResponseCode(301).setHeader("Location", "/dav/Series/")
                "/dav/Series/" -> MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(
                    """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/Series/</D:href>""" +
                        """<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>""" +
                        """<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        dav.start()
        assertTrue(fs(davShare(false, "localhost", dav.port)).stat("Series")!!.isDirectory)
        dav.takeRequest()
        val moved = dav.takeRequest()
        assertEquals("/dav/Series/", moved.path)
        assertEquals("PROPFIND", moved.method)
        assertEquals("0", moved.getHeader("Depth"))
        assertEquals(Credentials.basic("user", "pw"), moved.getHeader("Authorization"))
        assertTrue(moved.body.readUtf8().contains("propfind"))
    }

    @Test
    fun redirectsBetweenHttpsAndHttpAreRefused() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = DavFs.sharedClient.newBuilder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        val plainTarget = mock { emptyListing }
        plainTarget.start()
        val secureTarget = mock { emptyListing }
        secureTarget.useHttps(serverCerts.sslSocketFactory(), false)
        secureTarget.start()
        val secureFrom = mock { MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${plainTarget.port}/dav/") }
        secureFrom.useHttps(serverCerts.sslSocketFactory(), false)
        secureFrom.start()
        val plainFrom = mock { MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${secureTarget.port}/dav/") }
        plainFrom.start()
        // The certificate is trusted: straight to the HTTPS server works.
        assertEquals(emptyList<RemoteStat>(), DavFs(davShare(true, "localhost", secureTarget.port), client).also { opened += it }.list(""))
        val reached = secureTarget.requestCount
        for ((share, target) in listOf(davShare(true, "localhost", secureFrom.port) to plainTarget, davShare(false, "localhost", plainFrom.port) to secureTarget)) {
            val before = target.requestCount
            try {
                DavFs(share, client).also { opened += it }.list("")
                fail("followed a redirect between HTTPS and HTTP")
            } catch (e: IOException) {
                assertTrue("$e", e.message!!.contains("302"))
            }
            assertEquals(before, target.requestCount)
        }
        assertEquals(1, reached)
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

    @Test(timeout = Waits.LIVENESS_MS)
    fun aDiskSpinningUpIsWaitedFor() {
        val bytes = Random(11).nextBytes(100_000)
        File(dir, "book.cbz").writeBytes(bytes)
        // The default timeout outlasts a spin-up with room to spare, and no cap on the whole call cuts one short.
        assertTrue(Remotes.DEFAULT_ANSWER_MS >= Remotes.SPIN_UP_MS * 6 / 5)
        val fs = DavFs(server.share(), answerMs = { Remotes.DEFAULT_ANSWER_MS }).also { opened += it }
        assertEquals(0, fs.client.callTimeoutMillis)
        for (t in listOf(fs.client.connectTimeoutMillis, fs.client.readTimeoutMillis, fs.client.writeTimeoutMillis)) {
            assertTrue("$t ms", t >= Remotes.SPIN_UP_MS * 6 / 5)
        }
        // The first answer after the disk slept comes only once it spun up.
        val spinUp = CountDownLatch(1)
        server.spinUp = spinUp
        val listing = FutureTask { fs.list("") }.also { Thread(it).start() }
        awaitUntil("the listing to be held while the disk spins up") { server.heldAtSpinUp.get() == 1 }
        assertFalse(listing.isDone)
        spinUp.countDown()
        awaitUntil("the listing") { listing.isDone }
        assertEquals(listOf("book.cbz"), listing.get().map { it.name })
        fs.open("book.cbz").use { input ->
            val got = ByteArray(bytes.size)
            var at = 0
            while (at < got.size) at += input.read(at.toLong(), got, at, got.size - at)
            assertArrayEquals(bytes, got)
        }
    }

    @Test(timeout = Waits.LIVENESS_MS)
    fun aShortTimeoutGivesUpOnASpinUp() {
        val fs = DavFs(server.share(), answerMs = { 300 }).also { opened += it }
        // Never opened: only the client's timeout ends the call; closing the server ends the hold.
        server.spinUp = CountDownLatch(1)
        val e = assertThrows(RemoteException::class.java) { fs.list("") }
        assertEquals(RemoteException.Reason.UNREACHABLE, e.reason)
    }

    @Test
    fun requestsWaitAsLongAsTheSettingSaysNow() {
        var setting = Remotes.DEFAULT_ANSWER_MS
        val fs = DavFs(server.share(), answerMs = { setting }).also { opened += it }
        assertTrue(Remotes.DEFAULT_ANSWER_MS > Remotes.SPIN_UP_MS)
        for (ms in listOf(Remotes.DEFAULT_ANSWER_MS, 60_000L)) {
            setting = ms
            val client = fs.client
            assertEquals(ms.toInt(), client.connectTimeoutMillis)
            assertEquals(ms.toInt(), client.readTimeoutMillis)
            assertEquals(ms.toInt(), client.writeTimeoutMillis)
        }
    }
}
