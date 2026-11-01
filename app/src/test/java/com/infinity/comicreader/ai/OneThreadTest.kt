package com.infinity.comicreader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
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
    fun exceptionsReachTheCaller() {
        val one = OneThread("test-throw")
        assertThrows(IllegalStateException::class.java) { one.run { error("no") } }
        assertEquals(1, one.run { 1 })
    }
}
