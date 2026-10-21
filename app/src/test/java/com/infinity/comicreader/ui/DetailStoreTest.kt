package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.infinity.comicreader.reader.PageDetail
import com.infinity.comicreader.reader.Region
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailStoreTest {

    private class FakeDetail(val name: String) : PageDetail {
        @Volatile var released = false
        override val byteCount = 0L
        override fun render(region: Region, width: Int, height: Int, linear: Boolean): Bitmap = throw UnsupportedOperationException()
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

    private fun idle(done: () -> Boolean = { false }) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pageLoadsOnceForEveryRender() {
        val results = ArrayList<String>()
        store.render("a", listOf("a"), loader("a"), { (it as FakeDetail).name + 1 }) { results += it }
        store.render("a", listOf("a"), loader("a"), { (it as FakeDetail).name + 2 }) { results += it }
        idle { results.size == 2 }
        assertEquals(listOf("a1", "a2"), results.sorted())
        assertEquals(1, loads.get())
    }

    @Test
    fun anotherItemDropsAndFreesThePages() {
        var done = 0
        store.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idle { done == 1 }
        store.render("b", listOf("b"), loader("b"), { it }) { done++ }
        idle { done == 2 }
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
        idle { done == 2 }
        assertEquals(setOf("left", "right"), store.keys)
        assertEquals(2, loads.get())
        assertFalse(detail("left").released)
        assertFalse(detail("right").released)
    }

    @Test
    fun pageDroppedMidRenderIsFreedOnceTheRenderEnds() {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var done = 0
        store.render("a", listOf("a"), loader("a"), {
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            it
        }) { done++ }
        idle { started.count == 0L }
        store.clear()
        assertFalse(detail("a").released)
        proceed.countDown()
        idle { detail("a").released }
        assertTrue(detail("a").released)
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun canceledRenderDeliversNothing() {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var done = 0
        val job = store.render("a", listOf("a"), loader("a"), {
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            it
        }) { done++ }
        idle { started.count == 0L }
        job.cancel()
        proceed.countDown()
        idle { job.isCompleted }
        assertEquals(0, done)
        // The page stays for the next render.
        store.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idle { done == 1 }
        assertEquals(1, loads.get())
    }

    @Test
    fun failedLoadGivesNothingAndIsTriedAgain() {
        var done = 0
        val job = store.render("a", listOf("a"), { throw OutOfMemoryError("synthetic") }, { it }) { done++ }
        idle { job.isCompleted }
        assertEquals(0, done)
        idle { store.keys.isEmpty() }
        assertTrue(store.keys.isEmpty())

        store.render("a", listOf("a"), loader("a"), { it }) { done++ }
        idle { done == 1 }
        assertEquals(1, done)
        assertEquals(1, loads.get())
    }

    @Test
    fun failedLoadOrRenderCallsFailedInstead() {
        var failed = 0
        var done = 0
        store.render("a", listOf("a"), { throw IllegalStateException("synthetic") }, { it }, failed = { failed++ }) { done++ }
        idle { failed == 1 }
        store.render("b", listOf("b"), loader("b"), { throw OutOfMemoryError("synthetic") }, failed = { failed++ }) { done++ }
        idle { failed == 2 }
        assertEquals(2, failed)
        assertEquals(0, done)
    }

    @Test
    fun pageDroppedBeforeItsRenderStartsCallsFailed() {
        val proceed = CountDownLatch(1)
        var failed = 0
        var done = 0
        store.render("a", listOf("a"), {
            proceed.await(5, TimeUnit.SECONDS)
            FakeDetail("a")
        }, { it }, failed = { failed++ }) { done++ }
        store.clear()
        proceed.countDown()
        idle { failed == 1 }
        assertEquals(1, failed)
        assertEquals(0, done)
    }

    @Test
    fun canceledRenderCallsNeitherCallback() {
        val proceed = CountDownLatch(1)
        var calls = 0
        val job = store.render("a", listOf("a"), {
            proceed.await(5, TimeUnit.SECONDS)
            FakeDetail("a")
        }, { it }, failed = { calls++ }) { calls++ }
        job.cancel()
        store.clear()
        proceed.countDown()
        idle { job.isCompleted }
        idle()
        assertEquals(0, calls)
    }

    @Test
    fun budgetIsASixteenthOfRamWithinBounds() {
        val gib = 1024L * 1024 * 1024
        assertEquals(DetailStore.MAX_BUDGET, DetailStore.budget(8 * gib))
        assertEquals(gib / 16, DetailStore.budget(gib))
        assertEquals(32L * 1024 * 1024, DetailStore.budget(gib / 64))
    }
}
