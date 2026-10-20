package com.infinity.comicreader.reader

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GuardedSourceTest {

    /** Page sizes stand in for reads: they need no Android bitmap. */
    private class FakeSource : PageSource {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closes = AtomicInteger()
        @Volatile var closedDuringRead = false
        @Volatile var reads = 0

        override val pageCount = 3
        override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap =
            throw UnsupportedOperationException()

        override fun pageSize(index: Int): IntArray {
            reads++
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            if (closes.get() > 0) closedDuringRead = true
            return intArrayOf(10, 20)
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun idleCloseClosesAtOnce() {
        val inner = FakeSource()
        GuardedSource(inner).close()
        assertEquals(1, inner.closes.get())
    }

    @Test
    fun closeWaitsForReadInFlight() {
        val inner = FakeSource()
        val guarded = GuardedSource(inner)
        var size: IntArray? = null
        val reader = Thread { size = guarded.pageSize(0) }
        reader.start()
        assertTrue(inner.started.await(5, TimeUnit.SECONDS))

        guarded.close()
        assertEquals(0, inner.closes.get())

        inner.proceed.countDown()
        reader.join(5_000)
        assertFalse(inner.closedDuringRead)
        assertEquals(20, size?.get(1))
        assertEquals(1, inner.closes.get())
    }

    @Test
    fun readsAfterCloseFail() {
        val inner = FakeSource()
        val guarded = GuardedSource(inner)
        guarded.close()
        assertNull(guarded.pageSize(0))
        assertEquals(0, inner.reads)
    }

    @Test
    fun closesOnce() {
        val inner = FakeSource()
        val guarded = GuardedSource(inner)
        guarded.close()
        guarded.close()
        assertEquals(1, inner.closes.get())
    }
}
