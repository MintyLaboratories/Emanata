package com.infinity.comicreader.net

import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** SMB connections against a local socket that never answers: nothing here reaches another host. */
class SmbFsTest {

    private fun share(port: Int, user: String = "user") =
        Share("s1", "Share A", SharePath("127.0.0.1", port, "books", ""), user, "secret")

    /** A local server taking connections and answering nothing; [accepted] hears each. */
    private class Silent : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepted = LinkedBlockingQueue<Socket>()
        val connections = AtomicInteger()

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        connections.incrementAndGet()
                        accepted.put(socket)
                    }
                }
            }
        }

        val port: Int get() = server.localPort

        override fun close() {
            accepted.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    @Test
    fun everyRequestOutlastsADiskSpinningUp() {
        val config = SmbFs.config(Remotes.DEFAULT_ANSWER_MS)
        // Negotiating, signing in, listing, reading and writing each wait for their answer.
        for (wait in listOf(config.transactTimeout, config.readTimeout, config.writeTimeout)) {
            assertTrue("$wait ms", wait > Remotes.SPIN_UP_MS)
        }
        // No socket timeout: a quiet connection is not taken for a dead one.
        assertEquals(0, config.soTimeout)
    }

    @Test
    fun everyRequestWaitsAsLongAsTheSettingSays() {
        val config = SmbFs.config(60_000)
        for (wait in listOf(config.transactTimeout, config.readTimeout, config.writeTimeout)) assertEquals(60_000L, wait)
    }

    @Test
    fun theConnectTimeoutFollowsTheSetting() {
        // The socket factory keeps its connect timeout to itself.
        val factory = SmbFs.config(45_000).socketFactory
        val field = factory.javaClass.getDeclaredField("connectTimeout").apply { isAccessible = true }
        assertEquals(45_000, field.getInt(factory))
    }

    @Test
    fun aChangedTimeoutRemakesTheConnectionOnlyWhenNothingElseIsUnderWay() {
        // A call alone, the caller counting itself: made anew with the new value.
        assertTrue(SmbFs.remake(connected = true, madeMs = 30_000, wantedMs = 60_000, underWay = 1))
        // A write's next piece, inside the write: its open file keeps the old connection.
        assertFalse(SmbFs.remake(connected = true, madeMs = 30_000, wantedMs = 60_000, underWay = 2))
        // The same value, or no connection: nothing to remake.
        assertFalse(SmbFs.remake(connected = true, madeMs = 60_000, wantedMs = 60_000, underWay = 1))
        assertFalse(SmbFs.remake(connected = false, madeMs = 30_000, wantedMs = 60_000, underWay = 1))
    }

    @Test
    fun aBlankUserSignsInAnonymouslyThenAsAGuest() {
        val ways = SmbFs.authentications(share(1, user = ""))
        assertEquals(listOf(true, false), ways.map { it.isAnonymous })
        assertEquals(listOf(false, true), ways.map { it.isGuest })
        val named = SmbFs.authentications(share(1, user = "WORKGROUP\\user")).single()
        assertEquals("user", named.username)
        assertEquals("WORKGROUP", named.domain)
        assertEquals("secret", String(named.password))
    }

    @Test(timeout = Waits.LIVENESS_MS)
    fun callersWaitingOnAFailedConnectFailWithoutConnectingAgain() {
        Silent().use { silent ->
            val fs = SmbFs(share(silent.port))
            // Each connection is dropped at once, as by a host going away.
            thread(isDaemon = true) { runCatching { while (true) silent.accepted.take().close() } }
            val failures = AtomicReference<Throwable>()
            val callers = List(4) {
                thread(isDaemon = true) {
                    try {
                        fs.list("")
                    } catch (e: RemoteException) {
                        if (e.reason != RemoteException.Reason.UNREACHABLE) failures.set(e)
                    } catch (t: Throwable) {
                        failures.set(t)
                    }
                }
            }
            awaitUntil("the four callers to fail") { callers.none { it.isAlive } }
            assertEquals(null, failures.get())
            assertEquals(1, silent.connections.get())
            fs.retry()
            assertThrows(RemoteException::class.java) { fs.list("") }
            assertEquals(2, silent.connections.get())
            fs.close()
        }
    }

    @Test(timeout = Waits.LIVENESS_MS)
    fun closingDoesNotWaitForAConnectUnderWay() {
        Silent().use { silent ->
            val fs = SmbFs(share(silent.port))
            val failure = AtomicReference<Throwable>()
            val listing = thread(isDaemon = true) {
                try {
                    fs.list("")
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            val socket = silent.accepted.poll(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("Gave up waiting for the listing's connection")
            val started = System.nanoTime()
            fs.close()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("close took $tookMs ms", tookMs < 1_000)
            socket.close()
            awaitUntil("the listing to fail") { !listing.isAlive }
            assertTrue("failed with ${failure.get()}", failure.get() is RemoteException)
        }
    }

    @Test
    fun anUnusedConnectionIsEchoedThenClosedAfterTheIdleTime() {
        val used = 1_000_000L
        fun at(after: Long, open: Boolean = false, echoed: Long = used) =
            SmbFs.idleAction(used + after, used, echoed, open, connected = true)
        assertEquals(SmbFs.IdleAction.NONE, at(SmbFs.KEEPALIVE_MS / 2))
        assertEquals(SmbFs.IdleAction.ECHO, at(SmbFs.KEEPALIVE_MS))
        // Echoed just now: not again until another interval passes.
        assertEquals(SmbFs.IdleAction.NONE, at(SmbFs.KEEPALIVE_MS + 1_000, echoed = used + SmbFs.KEEPALIVE_MS))
        assertEquals(SmbFs.IdleAction.CLOSE, at(SmbFs.IDLE_MS))
        // A file open keeps it, echoed, for longer, but not for ever.
        assertEquals(SmbFs.IdleAction.ECHO, at(SmbFs.IDLE_MS, open = true))
        assertEquals(SmbFs.IdleAction.CLOSE, at(SmbFs.OPEN_IDLE_MS, open = true))
        assertTrue(SmbFs.OPEN_IDLE_MS > SmbFs.IDLE_MS)
        assertEquals(SmbFs.IdleAction.NONE, SmbFs.idleAction(used + SmbFs.IDLE_MS, used, used, open = false, connected = false))
        assertTrue(SmbFs.IDLE_MS >= 10 * 60_000L)
    }

    @Test
    fun deletingAFolderRemovesLinksInsideWithoutWalkingIntoThem() {
        fun dir(path: String) = SmbFs.Entry(path, isDirectory = true, isLink = false)
        fun file(path: String) = SmbFs.Entry(path, isDirectory = false, isLink = false)
        val junction = SmbFs.Entry("books\\Series\\Elsewhere", isDirectory = true, isLink = true)
        val tree = mapOf(
            "books\\Series" to listOf(file("books\\Series\\001.cbz"), dir("books\\Series\\Extras"), junction),
            "books\\Series\\Extras" to listOf(file("books\\Series\\Extras\\cover.jpg")),
            // What the link points to: never listed, never removed.
            "books\\Series\\Elsewhere" to listOf(file("books\\Series\\Elsewhere\\other.cbz"))
        )
        val listed = ArrayList<String>()
        val removed = ArrayList<String>()
        SmbFs.deleteTree(dir("books\\Series"), { listed += it; tree.getValue(it) }, { removed += it.path })
        assertEquals(listOf("books\\Series", "books\\Series\\Extras"), listed)
        assertEquals(
            listOf(
                "books\\Series\\001.cbz", "books\\Series\\Extras\\cover.jpg", "books\\Series\\Extras",
                "books\\Series\\Elsewhere", "books\\Series"
            ),
            removed
        )
        // A link deleted by itself goes alone.
        removed.clear()
        SmbFs.deleteTree(junction, { throw AssertionError("listed $it") }, { removed += it.path })
        assertEquals(listOf("books\\Series\\Elsewhere"), removed)
    }

    @Test
    fun aChangeIsMadeAgainAfterADropOnlyWhenItDidNotLand() {
        val dropped = IOException("dropped")
        val failed = { e: Exception -> (e as? IOException ?: IOException(e)) to (e === dropped) }
        var connects = 0
        var made = 0
        fun run(landed: Boolean, failFirst: Exception?) {
            connects = 0
            made = 0
            SmbFs.changeOnce({ ++connects }, failed, { landed }) { _ ->
                made++
                if (made == 1 && failFirst != null) throw failFirst
            }
        }
        run(landed = false, failFirst = null)
        assertEquals(1 to 1, connects to made)
        // Made before the connection dropped: not made again, so no failure for a second rename or delete.
        run(landed = true, failFirst = dropped)
        assertEquals(2 to 1, connects to made)
        run(landed = false, failFirst = dropped)
        assertEquals(2 to 2, connects to made)
        // Any other failure is final.
        val refused = IOException("refused")
        assertEquals(refused, assertThrows(IOException::class.java) { run(landed = false, failFirst = refused) })
        assertEquals(1 to 1, connects to made)
    }

    @Test
    fun aReadInterruptedWhileWaitingIsNotADroppedConnection() {
        val wrapped = com.hierynomus.protocol.transport.TransportException(java.util.concurrent.ExecutionException(InterruptedException()))
        assertTrue(SmbFs.interrupted(wrapped))
        // A socket timeout is an InterruptedIOException too, but the link failed.
        assertFalse(SmbFs.interrupted(com.hierynomus.protocol.transport.TransportException(java.net.SocketTimeoutException())))
        assertFalse(SmbFs.interrupted(IOException("Connection reset", java.net.SocketException())))
    }

    @Test
    fun pipelinedReadsAskForNoMoreThanTheServerTakes() {
        assertEquals(SmbFs.PIECE_BYTES, SmbFs.pieceFor(8 shl 20, multiCredit = true))
        assertEquals(256 * 1024, SmbFs.pieceFor(256 * 1024, multiCredit = true))
        // SMB 2.0.2, or a server granting one credit per request: 64 KB a read.
        assertEquals(64 * 1024, SmbFs.pieceFor(8 shl 20, multiCredit = false))
        assertEquals(32 * 1024, SmbFs.pieceFor(32 * 1024, multiCredit = false))
        assertEquals(64 * 1024, SmbFs.pieceFor(0, multiCredit = true))
    }
}
