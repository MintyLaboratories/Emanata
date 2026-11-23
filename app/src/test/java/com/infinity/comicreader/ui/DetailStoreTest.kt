package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.reader.PageDetail
import com.infinity.comicreader.reader.Region
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailStoreTest {

    private class FakeDetail(val name: String, override val byteCount: Long = 0L) : PageDetail {
        @Volatile var released = false
        override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap = throw UnsupportedOperationException()
        override fun release() {
            released = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = DetailStore<String>(scope, Dispatchers.IO)
    private val loads = AtomicInteger()
    private val made = HashMap<String, FakeDetail>()

    private fun loader(key: String): () -> PageDetail = {
        loads.incrementAndGet()
        FakeDetail(key).also { synchronized(made) { made[key] = it } }
    }

    private fun detail(key: String) = synchronized(made) { made.getValue(key) }

    /** Gates loads and renders wait at until the test opens them; all opened after it. */
    private val gates = ArrayList<CountDownLatch>()

    private fun gate() = CountDownLatch(1).also { gates += it }

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        scope.cancel()
    }

    @Test
    fun pageLoadsOnceForEveryRender() {
        val results = ArrayList<String>()
        store.render("a", listOf("a"), loader("a"), { (it as FakeDetail).name + 1 }) { results += it }
        store.render("a", listOf("a"), loader("a"), { (it as FakeDetail).name + 2 }) { results += it }
        idleUntil("both renders delivered") { results.size == 2 }
        assertEquals(listOf("a1", "a2"), results.sorted())
        assertEquals(1, loads.get())
    }

    @Test
    fun anotherItemDropsAndFreesThePages() {
        var done = 0
        store.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idleUntil("a rendered") { done == 1 }
        store.render("b", listOf("b"), loader("b"), { it }) { done++ }
        idleUntil("b rendered") { done == 2 }
        assertTrue(detail("a").released)
        assertFalse(detail("b").released)
        assertEquals(setOf("b"), store.keys)
    }

    @Test
    fun spreadHalvesAreKeptTogether() {
        var done = 0
        store.render("left", listOf("left", "right"), loader("left"), { it }) { done++ }
        store.prepare("right", listOf("left", "right"), loader("right"))
        store.render("right", listOf("left", "right"), loader("right"), { it }) { done++ }
        idleUntil("both halves rendered") { done == 2 }
        assertEquals(setOf("left", "right"), store.keys)
        assertEquals(2, loads.get())
        assertFalse(detail("left").released)
        assertFalse(detail("right").released)
    }

    @Test
    fun pageDroppedMidRenderIsFreedOnceTheRenderEnds() {
        val started = CountDownLatch(1)
        val proceed = gate()
        var done = 0
        store.render("a", listOf("a"), loader("a"), {
            started.countDown()
            proceed.await()
            it
        }) { done++ }
        idleUntil("the render started") { started.count == 0L }
        store.clear()
        assertFalse(detail("a").released)
        proceed.countDown()
        idleUntil("the page freed") { detail("a").released }
        assertTrue(detail("a").released)
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun canceledRenderDeliversNothing() {
        val started = CountDownLatch(1)
        val proceed = gate()
        var done = 0
        val job = store.render("a", listOf("a"), loader("a"), {
            started.countDown()
            proceed.await()
            it
        }) { done++ }
        idleUntil("the render started") { started.count == 0L }
        job.cancel()
        proceed.countDown()
        idleUntil("the canceled render ended") { job.isCompleted }
        assertEquals(0, done)
        // The page stays for the next render.
        store.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idleUntil("a rendered") { done == 1 }
        assertEquals(1, loads.get())
    }

    @Test
    fun failedLoadGivesNothingAndIsTriedAgain() {
        var done = 0
        val job = store.render("a", listOf("a"), { throw OutOfMemoryError("synthetic") }, { it }) { done++ }
        idleUntil("the failed render ended") { job.isCompleted }
        assertEquals(0, done)
        idleUntil("the failed page forgotten") { store.keys.isEmpty() }
        assertTrue(store.keys.isEmpty())

        store.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idleUntil("a rendered") { done == 1 }
        assertEquals(1, done)
        assertEquals(1, loads.get())
    }

    @Test
    fun aLoadFailingBeforeTheStoreHoldsItIsStillForgotten() {
        // Loads run in the caller's thread: the load fails, and its completion is heard, before the store's call returns.
        val now = DetailStore<String>(scope, Dispatchers.Unconfined)
        var failed = 0
        now.render("a", listOf("a"), { throw OutOfMemoryError("synthetic") }, { it }, failed = { failed++ }) { }
        idleUntil("a's load failed") { failed == 1 }
        assertTrue(now.keys.isEmpty())
    }

    @Test
    fun failedLoadOrRenderCallsFailedInstead() {
        var failed = 0
        var done = 0
        store.render("a", listOf("a"), { throw IllegalStateException("synthetic") }, { it }, failed = { failed++ }) { done++ }
        idleUntil("a's load failed") { failed == 1 }
        store.render("b", listOf("b"), loader("b"), { throw OutOfMemoryError("synthetic") }, failed = { failed++ }) { done++ }
        idleUntil("b's render failed") { failed == 2 }
        assertEquals(2, failed)
        assertEquals(0, done)
    }

    @Test
    fun pageDroppedBeforeItsRenderStartsCallsFailed() {
        val proceed = gate()
        var failed = 0
        var done = 0
        store.render("a", listOf("a"), {
            proceed.await()
            FakeDetail("a")
        }, { it }, failed = { failed++ }) { done++ }
        store.clear()
        proceed.countDown()
        idleUntil("the dropped render failed") { failed == 1 }
        assertEquals(1, failed)
        assertEquals(0, done)
    }

    @Test
    fun pageDroppedBeforeItsRenderStartsCallsDroppedWhenGiven() {
        val proceed = gate()
        var failed = 0
        var dropped = 0
        store.render("a", listOf("a"), {
            proceed.await()
            FakeDetail("a")
        }, { it }, failed = { failed++ }, dropped = { dropped++ }) { }
        // Another page's render keeping only its own.
        store.render("b", listOf("b"), loader("b"), { it }) { }
        proceed.countDown()
        idleUntil("the render told it was dropped") { dropped == 1 }
        assertEquals(1, dropped)
        assertEquals(0, failed)
        // A failed load still fails.
        store.render("c", listOf("c"), { throw IllegalStateException("synthetic") }, { it }, failed = { failed++ }, dropped = { dropped++ }) { }
        idleUntil("c's load failed") { failed == 1 }
        assertEquals(1, dropped)
    }

    @Test
    fun canceledRenderCallsNeitherCallback() {
        val proceed = gate()
        var calls = 0
        val job = store.render("a", listOf("a"), {
            proceed.await()
            FakeDetail("a")
        }, { it }, failed = { calls++ }) { calls++ }
        job.cancel()
        store.clear()
        proceed.countDown()
        // The callbacks run within the render's job: none can come once it ended.
        idleUntil("the canceled render ended") { job.isCompleted }
        assertEquals(0, calls)
    }

    /** How near each page is, as [DetailStore.rank] reads it; pages not named are too far. */
    private val ranks = HashMap<String, Int>()

    private fun madeStore(budget: Long) = DetailStore<String>(scope, Dispatchers.IO).also { s ->
        s.budget = budget
        s.rank = { ranks[it] }
    }

    @Test
    fun aMadeDetailServesRendersWithoutALoad() {
        ranks["a"] = 0
        val s = madeStore(1_000)
        s.offer("a", FakeDetail("model"))
        assertTrue(s.made("a"))
        assertTrue(s.loaded("a"))
        var got: String? = null
        s.render("a", listOf("a"), loader("a"), { (it as FakeDetail).name }) { got = it }
        idleUntil("a rendered") { got != null }
        assertEquals("model", got)
        assertEquals(0, loads.get())
    }

    @Test
    fun aMadeDetailTakesTheLoadedOnesPlaceAndFreesIt() {
        ranks["a"] = 0
        val s = madeStore(1_000)
        var done = 0
        s.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idleUntil("a rendered") { done == 1 }
        val model = FakeDetail("model")
        s.offer("a", model)
        assertTrue(detail("a").released)
        assertFalse(model.released)
        assertTrue(s.made("a"))
    }

    @Test
    fun madeDetailsFillTheBudgetNearestFirstAndNoFurther() {
        val s = madeStore(100)
        val made = (0 until 5).map { FakeDetail("p$it", 40) }
        made.forEachIndexed { i, d ->
            ranks["p$i"] = i
            s.offer("p$i", d)
            assertTrue("within the budget after page $i", s.heldBytes <= 100)
        }
        assertEquals(setOf("p0", "p1"), s.keys)
        assertEquals(80L, s.heldBytes)
        assertEquals(listOf(false, false, true, true, true), made.map { it.released })
        // A nearer page coming in sends the farthest out.
        ranks["q"] = 0
        ranks["p0"] = 1
        ranks["p1"] = 2
        val q = FakeDetail("q", 40)
        s.offer("q", q)
        assertEquals(setOf("q", "p0"), s.keys)
        assertTrue(made[1].released)
        assertFalse(q.released)
    }

    @Test
    fun madeDetailsCountBesideTheLoadedOnesOfTheItemZoomed() {
        val s = madeStore(100)
        ranks["next"] = 1
        s.offer("next", FakeDetail("next", 40))
        var done = 0
        s.render("shown", listOf("shown"), { FakeDetail("shown", 80) }, { it }) { done++ }
        idleUntil("shown rendered") { done == 1 }
        // The zoomed page's own detail stays; the next page's goes to keep within the budget.
        idleUntil("within the budget") { s.keys == setOf("shown") }
        assertTrue(s.heldBytes <= 100)
    }

    @Test
    fun madeDetailsOfPagesNoLongerNearAreLetGoOf() {
        val s = madeStore(1_000)
        ranks["a"] = 0
        ranks["b"] = 1
        val a = FakeDetail("a")
        val b = FakeDetail("b")
        s.offer("a", a)
        s.offer("b", b)
        // The reader turned on: a is behind, out of reach.
        ranks.remove("a")
        ranks["b"] = 0
        s.trim()
        assertTrue(a.released)
        assertFalse(b.released)
        assertEquals(setOf("b"), s.keys)
    }

    @Test
    fun aMadeDetailOfTheItemZoomedStaysThoughFar() {
        val s = madeStore(1_000)
        val a = FakeDetail("a")
        ranks["a"] = 0
        s.offer("a", a)
        var done = 0
        s.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idleUntil("a rendered") { done == 1 }
        ranks.remove("a")
        s.trim()
        assertFalse(a.released)
        assertEquals(0, loads.get())
        // Its view gone, it is a far page like any other.
        s.drop(listOf("a"))
        assertTrue(a.released)
    }

    @Test
    fun anotherItemOrADetachedViewKeepsMadeDetailsWhileChangedOnesAndClearFreeThem() {
        val s = madeStore(1_000)
        ranks["next"] = 1
        val next = FakeDetail("next")
        s.offer("next", next)
        var done = 0
        s.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idleUntil("a rendered") { done == 1 }
        assertEquals(setOf("a", "next"), s.keys)
        s.drop(listOf("a", "next"))
        assertTrue(detail("a").released)
        assertFalse(next.released)
        s.dropMade()
        assertTrue(next.released)
        assertTrue(s.keys.isEmpty())
        val again = FakeDetail("again")
        s.offer("next", again)
        s.clear()
        assertTrue(again.released)
    }

    @Test
    fun budgetIsASixteenthOfRamWithinBounds() {
        val gib = 1024L * 1024 * 1024
        assertEquals(DetailStore.MAX_BUDGET, DetailStore.budget(8 * gib))
        assertEquals(gib / 16, DetailStore.budget(gib))
        assertEquals(32L * 1024 * 1024, DetailStore.budget(gib / 64))
    }
}
