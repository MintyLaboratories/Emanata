package com.infinity.comicreader.files

import com.infinity.comicreader.testing.awaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** The queue of file operations: one at a time in the order started, canceling, and how far each is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileWorkQueueTest {

    @Before
    fun setUp() = FileWork.reset()

    @After
    fun tearDown() = FileWork.reset()

    private fun status(id: Long) = FileWork.operations.value.first { it.id == id }

    /** An operation that runs until [go] lets it end, counting itself in [running] meanwhile. */
    private fun held(name: String, go: Semaphore, running: AtomicInteger, most: AtomicInteger, log: MutableList<String>): Long =
        FileWork.start(FileWork.Kind.COPY, name) {
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            log += name
            go.acquire()
            running.decrementAndGet()
            FileWork.Outcome.Done(null)
        }

    @Test
    fun operationsRunOneAtATimeInTheOrderStarted() {
        val go = Semaphore(0)
        val running = AtomicInteger()
        val most = AtomicInteger()
        val log = Collections.synchronizedList(ArrayList<String>())
        val a = held("a", go, running, most, log)
        val b = held("b", go, running, most, log)
        val c = held("c", go, running, most, log)
        awaitUntil("a to start") { log.size == 1 }
        assertTrue(status(a).running)
        assertTrue(status(b).waiting)
        assertTrue(status(c).waiting)
        assertTrue(FileWork.busy)

        go.release()
        awaitUntil("b to start") { log.size == 2 }
        assertEquals(FileWork.Outcome.Done(null), status(a).outcome)
        assertTrue(status(b).running)
        go.release()
        awaitUntil("c to start") { log.size == 3 }
        go.release()
        awaitUntil("c to end") { status(c).outcome != null }
        assertEquals(listOf("a", "b", "c"), log.toList())
        assertEquals(1, most.get())
        assertFalse(FileWork.busy)
    }

    @Test
    fun cancelingOneWaitingEndsItAtOnceAndTheRestGoOn() {
        val go = Semaphore(0)
        val log = Collections.synchronizedList(ArrayList<String>())
        val running = AtomicInteger()
        val most = AtomicInteger()
        held("a", go, running, most, log)
        val b = FileWork.start(FileWork.Kind.DELETE, "b", count = 3) { throw AssertionError("never runs") }
        val c = held("c", go, running, most, log)
        awaitUntil("a to start") { log.size == 1 }
        FileWork.cancel(b)
        assertEquals(FileWork.Outcome.Canceled(0, 3), status(b).outcome)
        go.release()
        awaitUntil("c to start") { log.size == 2 }
        go.release()
        awaitUntil("c to end") { status(c).outcome != null }
        assertEquals(listOf("a", "c"), log.toList())
    }

    @Test
    fun oneCanceledBeforeItBeganRunsItsUnrunInstead() {
        val go = Semaphore(0)
        val log = Collections.synchronizedList(ArrayList<String>())
        val started = Semaphore(0)
        val a = FileWork.start(FileWork.Kind.MOVE, "a", unrun = { log += "unrun a"; 0 }) {
            started.release()
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        val b = FileWork.start(FileWork.Kind.MOVE, "b", unrun = { log += "unrun b"; 0 }) { log += "ran b"; FileWork.Outcome.Done(null) }
        started.acquire()
        FileWork.cancel(b)
        FileWork.cancel(a)
        go.release()
        awaitUntil("a to end") { status(a).outcome != null }
        assertEquals(listOf("unrun b"), log.toList())
    }

    @Test
    fun cancelingTheOneUnderWayStopsItAndStartsTheNext() {
        val started = Semaphore(0)
        val a = FileWork.start(FileWork.Kind.COPY, "a") { p ->
            started.release()
            awaitUntil("a's cancel") { !p.alive }
            throw CancellationException()
        }
        val b = FileWork.start(FileWork.Kind.MOVE, "b") { FileWork.Outcome.Done(null) }
        started.acquire()
        FileWork.cancel(a)
        awaitUntil("b to end") { status(b).outcome != null }
        assertEquals(FileWork.Outcome.Canceled(), status(a).outcome)
        assertEquals(FileWork.Outcome.Done(null), status(b).outcome)
    }

    @Test
    fun cancelWithoutAnIdStopsOnlyTheOneUnderWay() {
        val started = Semaphore(0)
        val a = FileWork.start(FileWork.Kind.COPY, "a") { p ->
            started.release()
            awaitUntil("a's cancel") { !p.alive }
            throw CancellationException()
        }
        val b = FileWork.start(FileWork.Kind.COPY, "b") { FileWork.Outcome.Done(null) }
        started.acquire()
        FileWork.cancel()
        awaitUntil("b to end") { status(b).outcome != null }
        assertTrue(status(a).outcome is FileWork.Outcome.Canceled)
        assertEquals(FileWork.Outcome.Done(null), status(b).outcome)
    }

    @Test
    fun acknowledgingLetsGoOfFinishedOnesOnly() {
        val go = Semaphore(0)
        val a = FileWork.start(FileWork.Kind.COPY, "a") { FileWork.Outcome.Done(null) }
        val b = FileWork.start(FileWork.Kind.COPY, "b") {
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        awaitUntil("a to end and b to run") { status(a).outcome != null && status(b).running }
        FileWork.acknowledge(a)
        FileWork.acknowledge(b)
        assertEquals(listOf(b), FileWork.operations.value.map { it.id })
        go.release()
        awaitUntil("b to end") { status(b).outcome != null }
        FileWork.acknowledge(b)
        assertTrue(FileWork.operations.value.isEmpty())
    }

    @Test
    fun howFarCountsItemsDoneAndTheBytesOfTheCurrentOne() {
        val one = FileWork.Status(FileWork.Kind.COPY, "a", 0)
        assertNull(one.fraction)
        assertEquals(0.25f, one.copy(done = 25, total = 100).fraction!!, 0.001f)
        val many = one.copy(count = 4, index = 1)
        assertEquals(0.25f, many.fraction!!, 0.001f)
        assertEquals(0.375f, many.copy(done = 50, total = 100).fraction!!, 0.001f)
        assertEquals(1f, many.copy(index = 3, done = 100, total = 100).fraction!!, 0.001f)
    }

    @Test
    fun progressIsReportedOnItsOwnOperation() {
        val go = Semaphore(0)
        val reported = Semaphore(0)
        val a = FileWork.start(FileWork.Kind.COPY, "a", count = 2) { p ->
            p.item("second", 1, 2)
            p.report(10, 10)
            p.stoppable(false)
            reported.release()
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        val b = FileWork.start(FileWork.Kind.COPY, "b") { FileWork.Outcome.Done(null) }
        reported.acquire()
        val s = status(a)
        assertEquals("second", s.name)
        assertEquals(1, s.index)
        assertEquals(10L, s.done)
        assertFalse(s.stoppable)
        assertEquals(FileWork.Status(FileWork.Kind.COPY, "b", null, id = b), status(b))
        go.release()
        awaitUntil("b to end") { status(b).outcome != null }
    }

    @Test
    fun aJobMeasuresItsSpeedFromItsPlanAndAnewWhenTheAppComesOrGoes() {
        var front = true
        var now = 1_000L
        FileWork.inFront = { front }
        FileWork.clock = { now }
        val go = Semaphore(0)
        val stage = Semaphore(0)
        try {
            val id = FileWork.start(FileWork.Kind.COMPACT, "a") { p ->
                // Time before the plan does not count.
                now = 5_000L
                p.plan(listOf(60.0))
                stage.release()
                go.acquire()
                p.report(50, 100)
                stage.release()
                go.acquire()
                FileWork.Outcome.Done(null)
            }
            stage.acquire()
            assertEquals(FileWork.Since(5_000L, 0, 0.0, front = true), status(id).since)
            front = false
            now = 8_000L
            go.release()
            stage.acquire()
            assertEquals(FileWork.Since(8_000L, 0, 0.5, front = false), status(id).since)
            go.release()
            awaitUntil("the job to end") { status(id).outcome != null }
        } finally {
            FileWork.inFront = { true }
            FileWork.clock = { System.nanoTime() / 1_000_000 }
        }
    }
}
