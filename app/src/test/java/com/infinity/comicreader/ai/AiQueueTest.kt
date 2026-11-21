package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.infinity.comicreader.util.AppDispatchers
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

        fun run(key: String, isCanceled: () -> Boolean): AiOutcome {
            started += key
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

        fun queue(backoffMs: Long = 50, maxBackoffMs: Long = 200, runsModels: (String) -> Boolean = { true }) =
            AiQueue<String>(scope, executor.asCoroutineDispatcher(), ::run, { k, _ -> delivered += k }, backoffMs, maxBackoffMs, runsModels, { busy += it })
    }

    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun pagesAreWorkedOnOneAtATimeInTheOrderWanted() {
        val w = Work()
        val q = w.queue()
        q.want(listOf("a", "b", "c"))
        idleUntil { w.delivered.size == 3 }
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
        idleUntil { w.started.size == 2 && q.current == null }
        q.want(listOf("b", "a", "c"))
        idleUntil { w.delivered.contains("c") }
        assertEquals(listOf("a", "b", "c"), w.started)
        q.forget("a")
        q.want(listOf("a"))
        idleUntil { w.started.size == 4 }
        assertEquals("a", w.started.last())
    }

    @Test
    fun aPageNoLongerWantedStopsAndTheNextStarts() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil { w.started.contains("a") }
        q.want(listOf("c"))
        idleUntil { w.delivered.contains("c") }
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
        idleUntil { q.waiting }
        assertEquals(listOf("a"), w.started)
        // The back-off doubles: 50 ms, then 100 ms.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        idleUntil { w.started.size == 2 && q.waiting }
        assertEquals(listOf("a", "a"), w.started)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertTrue("still waiting out the doubled back-off", q.waiting)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        idleUntil { w.delivered == listOf("a", "b") }
        assertEquals(listOf("a", "b"), w.delivered)
    }

    @Test
    fun wakingEndsABackOffAtOnce() {
        val w = Work()
        var later = true
        w.outcomes["a"] = { if (later) AiOutcome.Later else AiOutcome.Done(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("a"))
        idleUntil { q.waiting }
        later = false
        q.wake()
        idleUntil { w.delivered.contains("a") }
        assertEquals(listOf("a"), w.delivered)
    }

    @Test
    fun resetWorksOnEveryPageAgain() {
        val w = Work()
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil { w.delivered.size == 1 }
        q.reset()
        idleUntil { w.delivered.size == 2 }
        assertEquals(listOf("a", "a"), w.started)
    }

    @Test
    fun failingWorkSkipsThePage() {
        val w = Work()
        w.outcomes["a"] = { throw IllegalStateException("boom") }
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil { w.delivered.contains("b") }
        assertEquals(listOf("b"), w.delivered)
    }

    @Test
    fun aReleasedQueueTakesNoMoreWork() {
        val w = Work()
        val q = w.queue()
        q.release()
        q.want(listOf("a"))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
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
        idleUntil { w.started.contains("a") }
        q.pause()
        idleUntil { w.canceled.contains("a") }
        q.want(listOf("a", "b"))
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("nothing starts while paused", listOf("a"), w.started)
        w.held.remove("a")
        q.resume()
        idleUntil { w.delivered.size == 2 }
        assertEquals(listOf("a", "b"), w.delivered)
    }

    @Test
    fun resumingTriesAgainOnlyThePagesSkippedForAModelThatWouldNotOpen() {
        val w = Work()
        w.outcomes["a"] = { AiOutcome.Skipped(Fallback.UNOPENED) }
        w.outcomes["b"] = { AiOutcome.Skipped(Fallback.TIMEOUT) }
        val q = w.queue()
        q.want(listOf("a", "b", "c"))
        idleUntil { w.delivered.contains("c") }
        q.pause()
        w.outcomes.remove("a")
        w.outcomes.remove("b")
        q.resume()
        idleUntil { w.delivered.contains("a") }
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a", "b", "c", "a"), w.started)
        // Once done it stays done across another pause.
        q.pause()
        q.resume()
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, w.started.size)
    }

    @Test
    fun aPageFinishingAfterItWasDroppedIsLeftForTheGc() {
        val w = Work()
        val gate = CountDownLatch(1)
        val made = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        // Ignores being canceled, as a model run between two checks does.
        w.outcomes["a"] = {
            gate.await(5, TimeUnit.SECONDS)
            AiOutcome.Done(made)
        }
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil { w.started.contains("a") }
        q.want(listOf("c"))
        gate.countDown()
        idleUntil { w.delivered.contains("c") }
        assertFalse(w.delivered.contains("a"))
        assertFalse("it may still be on its way to disk", made.isRecycled)
    }

    @Test
    fun anotherPageWantedFirstWhilePutOffIsTriedAtOnce() {
        val w = Work()
        w.outcomes["a"] = { AiOutcome.Later }
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("a"))
        idleUntil { q.waiting }
        q.want(listOf("a"))
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the same page waits out its back-off", listOf("a"), w.started)
        q.want(listOf("b", "a"))
        idleUntil { w.delivered.contains("b") }
        assertEquals(listOf("b"), w.delivered)
    }

    @Test
    fun releasingStopsThePageRunningAndDeliversNothing() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a", "b"))
        idleUntil { w.started.contains("a") }
        q.release()
        idleUntil { w.canceled.contains("a") }
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
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
        idleUntil { w.started.contains("a") }
        assertTrue(q.working)
        assertEquals(listOf(true), w.busy)
        w.held["a"]!!.countDown()
        idleUntil { w.delivered.size == 3 && !q.working }
        assertEquals("not once per page", listOf(true, false), w.busy)
    }

    @Test
    fun aPageReadBackIsNotBusy() {
        val w = Work()
        val q = w.queue(runsModels = { it != "kept" })
        q.want(listOf("kept"))
        idleUntil { w.delivered.contains("kept") }
        assertTrue(w.busy.isEmpty())
        q.want(listOf("kept", "made"))
        idleUntil { w.delivered.contains("made") && !q.working }
        assertEquals(listOf(true, false), w.busy)
    }

    @Test
    fun aPagePutOffOrSkippedLeavesTheQueueIdle() {
        val w = Work()
        w.outcomes["hot"] = { AiOutcome.Later }
        val q = w.queue(backoffMs = 60_000)
        q.want(listOf("hot"))
        idleUntil { q.waiting }
        assertFalse(q.working)
        assertEquals(listOf(true, false), w.busy)
        w.outcomes["big"] = { AiOutcome.Skipped(Fallback.TIMEOUT) }
        q.want(listOf("big"))
        idleUntil { w.started.contains("big") && !q.working }
        assertEquals(listOf(true, false, true, false), w.busy)
    }

    @Test
    fun pausingAndReleasingTellIdle() {
        val w = Work()
        w.held["a"] = CountDownLatch(1)
        val q = w.queue()
        q.want(listOf("a"))
        idleUntil { w.started.contains("a") }
        q.pause()
        assertFalse(q.working)
        assertEquals(listOf(true, false), w.busy)
        q.resume()
        idleUntil { w.started.size == 2 }
        assertEquals(listOf(true, false, true), w.busy)
        q.release()
        assertEquals(listOf(true, false, true, false), w.busy)
    }
}
