package com.infinity.comicreader.ai

import com.infinity.comicreader.testing.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class OneThreadTest {

    @Test
    fun everyCallerRunsOnTheSameThread() {
        val one = OneThread("test-one")
        val seen = java.util.Collections.synchronizedSet(HashSet<Thread>())
        val callers = (0 until 4).map { thread { repeat(5) { one.run { seen += Thread.currentThread() } } } }
        callers.forEach { it.join() }
        assertEquals(1, seen.size)
        val worker = seen.single()
        assertEquals("test-one", worker.name)
        assertNotEquals(Thread.currentThread(), worker)
        // Still the same thread once its callers have ended.
        assertSame(worker, one.run { Thread.currentThread() })
    }

    @Test
    fun aCallFromItsOwnThreadRunsAtOnce() {
        val one = OneThread("test-nested")
        assertEquals(42, one.run { one.run { 42 } })
    }

    @Test
    fun anInterruptedCallerWaitsForItsBlockThenKeepsTheInterrupt() {
        val one = OneThread("test-interrupt")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = AtomicBoolean(false)
        var result = 0
        var interrupted = false
        val caller = thread {
            result = one.run {
                started.countDown()
                release.await()
                ended.set(true)
                7
            }
            interrupted = Thread.currentThread().isInterrupted
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            // Taken: the flag cleared and the caller waiting again, or gone.
            awaitUntil("the caller to take the interrupt") {
                caller.state == Thread.State.TERMINATED || (!caller.isInterrupted && caller.state == Thread.State.WAITING)
            }
            assertTrue("the caller left while its block ran", caller.isAlive)
        } finally {
            release.countDown()
        }
        awaitUntil("the caller to end") { !caller.isAlive }
        assertTrue(ended.get())
        assertEquals(7, result)
        assertTrue(interrupted)
    }

    @Test
    fun exceptionsReachTheCaller() {
        val one = OneThread("test-throw")
        assertThrows(IllegalStateException::class.java) { one.run { error("no") } }
        assertEquals(1, one.run { 1 })
    }
}
