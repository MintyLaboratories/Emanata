package com.infinity.comicreader.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test(timeout = 20_000)
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
            callers.forEach { it.join(10_000) }
            assertEquals(null, failures.get())
            assertEquals(1, silent.connections.get())
            fs.retry()
            assertThrows(RemoteException::class.java) { fs.list("") }
            assertEquals(2, silent.connections.get())
            fs.close()
        }
    }

    @Test(timeout = 20_000)
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
            val socket = silent.accepted.poll(10, TimeUnit.SECONDS)!!
            val started = System.nanoTime()
            fs.close()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("close took $tookMs ms", tookMs < 1_000)
            socket.close()
            listing.join(10_000)
            assertTrue("failed with ${failure.get()}", failure.get() is RemoteException)
        }
    }
}
