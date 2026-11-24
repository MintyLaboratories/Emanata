package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.infinity.comicreader.testing.awaitUntil
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Pages go through AI one at a time in the order wanted; one no longer wanted stops; one put off waits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiQueueTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    /** Work that answers [outcomes] per key (Done by default), holding a key in [held] until its latch opens. */
    private inner class Work {
        val started: MutableList<String> = Collections.synchronizedList(ArrayList())
        val canceled: MutableList<String> = Collections.synchronizedList(ArrayList())
        val outcomes = ConcurrentHashMap<String, () -> AiOutcome>()
        val held = ConcurrentHashMap<String, CountDownLatch>()
        val delivered = ArrayList<String>()

        /** What the queue's busy callback heard, in order. */
        val busy = ArrayList<Boolean>()

        /** Keys whose work runs no model (read back, or put off before its models open). */
        val quiet: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun run(key: String, isCanceled: () -> Boolean, models: () -> Unit): AiOutcome {
            started += key
            if (key !in quiet) models()
            held[key]?.let { latch ->
                while (!latch.await(5, TimeUnit.MILLISECONDS)) {
                    if (isCanceled()) {
                        canceled += key
                        throw java.util.concurrent.CancellationException()
                    }
                }
            }
            return outcomes[key]?.invoke() ?: AiOutcome.Done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }

        fun queue(backoffMs: Long = 50, maxBackoffMs: Long = 200, worker: CoroutineDispatcher = executor.asCoroutineDispatcher()) =
            AiQueue<String>(scope, worker, ::run, { k, _ -> delivered += k }, backoffMs, maxBackoffMs, { busy += it })
    }

    /** Runs each block on the worker thread to its end before the caller goes on: work that ends before the caller suspends. */
    private val endsFirst = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.submit(block).get()
        }
    }

    /** Runs what the queue posted to the main thread, then what it handed the worker, then what that posted back: what has not happened by then will not. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        val drained = executor.submit {}
        awaitUntil("the worker to run what it was handed") { drained.isDone }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun pagesAreWorkedOnOneAtATimeInTheOrderWanted() {
        val w = Work()
        val q = w.queue()
        q.want(listOf("a", "b", "c"))
        idleUntil("a, b and c delivered") { w.delivered.size == 3 }
        assertEquals(listOf("a", "b", "c"), w.started)
        assertEquals(listOf("a", "b", "c"), w.delivered)
        assertNull(q.current)
    }

    @Test
    fun aPageDoneOrSkippedIsNotWorkedOnAgain() {
        val w = Work()
        w.outcomes["b"] = { AiOutcome.Skipped(Fallback.TIMEOUT) }
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil("a and b worked on") { w.started.size == 2 && q.current == null }
        q.want(listOf("b", "a", "c"))
        idleUntil("c delivered") { w.delivered.contains("c") }
        assertEquals(listOf("a", "b", "c"), w.started)
        q.forget("a")
        q.want(listOf("a"))
        idleUntil("a worked on again") { w.started.size == 4 }
        assertEquals("a", w.started.last())
    }

    @Test
    fun aPageNoLongerWantedStopsAndTheNextStarts() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil("a started") { w.started.contains("a") }
        q.want(listOf("c"))
        idleUntil("c delivered") { w.delivered.contains("c") }
        assertEquals(listOf("a"), w.canceled)
        assertFalse(w.delivered.contains("a"))
        assertFalse("b is not wanted any more", w.started.contains("b"))
    }

    @Test
    fun aPagePutOffHoldsTheQueueBackThenGoesOn() {
        val w = Work()
        var later = 2
        w.outcomes["a"] = { if (later-- > 0) AiOutcome.Later else AiOutcome.Done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        val q = w.queue(backoffMs = 50)
        q.want(listOf("a", "b"))
        idleUntil("a put off") { q.waiting }
        assertEquals(listOf("a"), w.started)
        // The back-off doubles: 50 ms, then 100 ms.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        idleUntil("a tried again and put off again") { w.started.size == 2 && q.waiting }
        assertEquals(listOf("a", "a"), w.started)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertTrue("still waiting out the doubled back-off", q.waiting)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        idleUntil("a and b delivered") { w.delivered == listOf("a", "b") }
        assertEquals(listOf("a", "b"), w.delivered)
    }

    @Test
    fun wakingEndsABackOffAtOnce() {
        val w = Work()
        var later = true
        w.outcomes["a"] = { if (later) AiOutcome.Later else AiOutcome.Done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("a"))
        idleUntil("a put off") { q.waiting }
        later = false
        q.wake()
        idleUntil("a delivered") { w.delivered.contains("a") }
        assertEquals(listOf("a"), w.delivered)
    }

    @Test
    fun resetWorksOnEveryPageAgain() {
        val w = Work()
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil("a delivered") { w.delivered.size == 1 }
        q.reset()
        idleUntil("a delivered again") { w.delivered.size == 2 }
        assertEquals(listOf("a", "a"), w.started)
    }

    @Test
    fun againWorksOnlyOnThePagesPickedTheOneRunningGoingOnUnlessPicked() {
        val w = Work()
        w.outcomes["b"] = { AiOutcome.Skipped(Fallback.UNNEEDED) }
        w.held["c"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a", "b", "c"))
        idleUntil("a and b worked on, c running") { w.started.size == 3 && q.current == "c" }
        q.again { it == "b" }
        // c goes on, unpicked (a page stopped gives way to b at once); b is worked on again once c ends.
        assertEquals("c", q.current)
        w.held.getValue("c").countDown()
        idleUntil("c delivered, b worked on again") { w.delivered.contains("c") && w.started.toList().count { it == "b" } == 2 }
        assertEquals(listOf("a", "b", "c", "b"), w.started)
        assertTrue(w.canceled.isEmpty())

        w.held["d"] = CountDownLatch(1)
        q.want(listOf("d"))
        // Its work, not just the queue: a page stopped before its work starts never runs it.
        idleUntil("d's work started") { w.started.contains("d") }
        q.again { it == "d" }
        idleUntil("d stopped") { w.canceled == listOf("d") }
        w.held.getValue("d").countDown()
        idleUntil("d worked on again and delivered") { w.delivered.contains("d") }
        assertEquals(2, w.started.toList().count { it == "d" })
    }

    @Test
    fun failingWorkSkipsThePage() {
        val w = Work()
        w.outcomes["a"] = { throw IllegalStateException("boom") }
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil("b delivered") { w.delivered.contains("b") }
        assertEquals(listOf("b"), w.delivered)
    }

    @Test
    fun aReleasedQueueTakesNoMoreWork() {
        val w = Work()
        val q = w.queue()
        q.release()
        q.want(listOf("a"))
        settle()
        assertTrue(w.started.isEmpty())
    }

    @Test
    fun workEndingBeforeTheCallerWaitsIsDeliveredAndTheQueueGoesOn() {
        val w = Work()
        // Work done inline, as when the worker ends a page before the main thread suspends for it.
        val q = AiQueue<String>(scope, Dispatchers.Unconfined, w::run, { k, _ -> w.delivered += k })
        q.want(listOf("a", "b"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a", "b"), w.delivered)
        assertNull(q.current)
    }

    @Test
    fun pagesWantedWhileAPageDoneInlineIsDeliveredAreTheOnesWorkedOn() {
        val w = Work()
        lateinit var q: AiQueue<String>
        // Its delivery wants other pages, as the reader's does once a page shows processed.
        q = AiQueue(scope, Dispatchers.Unconfined, w::run, { k, _ ->
            w.delivered += k
            if (k == "a") q.want(listOf("c", "d"))
        })
        q.want(listOf("a", "b"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a", "c", "d"), w.started.toList())
        assertEquals(listOf("a", "c", "d"), w.delivered)
        assertNull(q.current)
    }

    @Test
    fun pausingStopsThePageRunningAndStartsNoneUntilResumed() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil("a started") { w.started.contains("a") }
        q.pause()
        idleUntil("a canceled") { w.canceled.contains("a") }
        q.want(listOf("a", "b"))
        settle()
        assertEquals("nothing starts while paused", listOf("a"), w.started)
        w.held.remove("a")
        q.resume()
        idleUntil("a and b delivered") { w.delivered.size == 2 }
        assertEquals(listOf("a", "b"), w.delivered)
    }

    @Test
    fun resumingTriesAgainOnlyThePagesSkippedForAModelThatWouldNotOpen() {
        val w = Work()
        w.outcomes["a"] = { AiOutcome.Skipped(Fallback.UNOPENED) }
        w.outcomes["b"] = { AiOutcome.Skipped(Fallback.TIMEOUT) }
        val q = w.queue()
        q.want(listOf("a", "b", "c"))
        idleUntil("c delivered") { w.delivered.contains("c") }
        q.pause()
        w.outcomes.remove("a")
        w.outcomes.remove("b")
        q.resume()
        idleUntil("a tried again and delivered") { w.delivered.contains("a") }
        settle()
        assertEquals(listOf("a", "b", "c", "a"), w.started)
        // Once done it stays done across another pause.
        q.pause()
        q.resume()
        settle()
        assertEquals(4, w.started.size)
    }

    @Test
    fun aPageFinishingAfterItWasDroppedIsLeftForTheGc() {
        val w = Work()
        val gate = CountDownLatch(1)
        val made = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        // Ignores being canceled, as a model run between two checks does.
        w.outcomes["a"] = {
            gate.await()
            AiOutcome.Done(made)
        }
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil("a started") { w.started.contains("a") }
        q.want(listOf("c"))
        gate.countDown()
        idleUntil("c delivered") { w.delivered.contains("c") }
        assertFalse(w.delivered.contains("a"))
        assertFalse("it may still be on its way to disk", made.isRecycled)
    }

    @Test
    fun anotherPageWantedFirstWhilePutOffIsTriedAtOnce() {
        val w = Work()
        w.outcomes["a"] = { AiOutcome.Later }
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("a"))
        idleUntil("a put off") { q.waiting }
        q.want(listOf("a"))
        settle()
        assertEquals("the same page waits out its back-off", listOf("a"), w.started)
        q.want(listOf("b", "a"))
        idleUntil("b delivered") { w.delivered.contains("b") }
        assertEquals(listOf("b"), w.delivered)
    }

    @Test
    fun releasingStopsThePageRunningAndDeliversNothing() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil("a started") { w.started.contains("a") }
        q.release()
        idleUntil("a canceled") { w.canceled.contains("a") }
        settle()
        assertEquals(listOf("a"), w.canceled)
        assertTrue(w.delivered.isEmpty())
        assertEquals(listOf("a"), w.started)
    }

    @Test
    fun busyIsToldOnceWhenModelsStartAndOnceWhenTheQueueRunsDry() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        assertFalse(q.working)
        q.want(listOf("a", "b", "c"))
        idleUntil("models working on a") { q.working }
        assertEquals(listOf(true), w.busy)
        w.held["a"]!!.countDown()
        idleUntil("all three pages delivered and the queue idle") { w.delivered.size == 3 && !q.working }
        assertEquals("not once per page", listOf(true, false), w.busy)
    }

    @Test
    fun aPageReadBackIsNotBusy() {
        val w = Work()
        w.quiet += "kept"
        val q = w.queue()
        q.want(listOf("kept"))
        idleUntil("kept delivered") { w.delivered.contains("kept") }
        assertTrue(w.busy.isEmpty())
        q.want(listOf("kept", "made"))
        idleUntil("made delivered and the queue idle") { w.delivered.contains("made") && !q.working }
        assertEquals(listOf(true, false), w.busy)
    }

    @Test
    fun aPagePutOffOrSkippedLeavesTheQueueIdle() {
        val w = Work()
        w.outcomes["hot"] = { AiOutcome.Later }
        w.quiet += "hot"
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("hot"))
        idleUntil("hot put off") { q.waiting }
        assertFalse(q.working)
        assertTrue("put off before its models opened", w.busy.isEmpty())
        w.outcomes["big"] = { AiOutcome.Skipped(Fallback.TIMEOUT) }
        q.want(listOf("big"))
        idleUntil("busy told twice") { w.busy.size == 2 }
        assertEquals(listOf(true, false), w.busy)
    }

    @Test
    fun modelsOnAPageEndingBeforeTheCallerWaitsAreToldBusy() {
        val w = Work()
        w.outcomes["a"] = { AiOutcome.Skipped(Fallback.TIMEOUT) }
        val q = w.queue(worker = endsFirst)
        q.want(listOf("a", "b"))
        // Both pages ended inline; what the worker posted as their models started has not run yet.
        assertEquals(listOf("b"), w.delivered)
        assertEquals("not once per page", listOf(true, false), w.busy)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the reports arriving late change nothing", listOf(true, false), w.busy)
    }

    @Test
    fun aPagePutOffWhileItsModelsRanIsBusyAgainOnceRetried() {
        val w = Work()
        var tries = 0
        w.outcomes["a"] = { if (tries++ == 0) AiOutcome.Later else AiOutcome.Done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        val q = w.queue(backoffMs = 50)
        q.want(listOf("a"))
        idleUntil("a put off") { q.waiting }
        assertFalse(q.working)
        assertEquals(listOf(true, false), w.busy)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        idleUntil("a delivered and the queue idle") { w.delivered.contains("a") && !q.working }
        assertEquals(listOf(true, false, true, false), w.busy)
    }

    @Test
    fun wakingRunsThePagePutOffAndIsBusy() {
        val w = Work()
        var tries = 0
        w.quiet += "a"
        w.outcomes["a"] = {
            if (tries++ == 0) AiOutcome.Later else AiOutcome.Done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("a"))
        idleUntil("a put off") { q.waiting }
        assertTrue(w.busy.isEmpty())
        w.quiet -= "a"
        w.held["a"] = CountDownLatch(1)
        q.wake()
        idleUntil("models working on a") { q.working }
        assertEquals(listOf(true), w.busy)
        w.held["a"]!!.countDown()
        idleUntil("a delivered and the queue idle") { w.delivered.contains("a") && !q.working }
        assertEquals(listOf(true, false), w.busy)
    }

    @Test
    fun resettingThePageRunningKeepsItBusyWithoutABlink() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil("models working on a") { q.working }
        q.reset()
        idleUntil("a started again") { w.started.size == 2 }
        assertEquals(listOf("a"), w.canceled)
        assertTrue(q.working)
        assertEquals(listOf(true), w.busy)
        w.held["a"]!!.countDown()
        idleUntil("a delivered and the queue idle") { w.delivered.contains("a") && !q.working }
        assertEquals(listOf(true, false), w.busy)
    }

    @Test
    fun turningFromAPageModelsWorkOnToAnotherDoesNotBlink() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        w.held["b"] = CountDownLatch(1)
        w.held["c"] = CountDownLatch(1)
        w.quiet += listOf("b", "c")
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil("models working on a") { q.working }
        q.want(listOf("b", "c"))
        idleUntil("b started") { w.started.contains("b") }
        assertTrue("busy until b shows whether its models run", q.working)
        assertEquals(listOf(true), w.busy)
        // b ran no model: the page after it is not counted busy before its models start.
        w.held["b"]!!.countDown()
        idleUntil("c started and the queue idle") { w.started.contains("c") && !q.working }
        assertEquals(listOf(true, false), w.busy)
        w.held["c"]!!.countDown()
        idleUntil("c delivered") { w.delivered.contains("c") }
        assertEquals(listOf(true, false), w.busy)
    }

    @Test
    fun pausingAndReleasingTellIdle() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil("models working on a") { q.working }
        q.pause()
        assertFalse(q.working)
        assertEquals(listOf(true, false), w.busy)
        q.resume()
        idleUntil("a started again and working") { w.started.size == 2 && q.working }
        assertEquals(listOf(true, false, true), w.busy)
        q.release()
        assertEquals(listOf(true, false, true, false), w.busy)
    }
}
