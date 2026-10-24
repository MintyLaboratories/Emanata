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
        override fun loadPage(index: Int, target: Target): Bitmap =
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

    /** A source whose detail renders wait for [proceed]; renders throw instead of making bitmaps. */
    private class DetailSource : PageSource {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closes = AtomicInteger()
        @Volatile var renders = 0

        override val pageCount = 1
        override fun loadPage(index: Int, target: Target): Bitmap = throw UnsupportedOperationException()

        override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail = object : PageDetail {
            override val byteCount = 0L
            override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap {
                renders++
                started.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                throw IllegalStateException(if (closes.get() > 0) "closed" else "rendered")
            }

            override fun release() {}
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun closeWaitsForDetailRender() {
        val inner = DetailSource()
        val guarded = GuardedSource(inner)
        val detail = guarded.loadDetail(0, Target.NATIVE, Long.MAX_VALUE)
        var message: String? = null
        val renderer = Thread {
            message = runCatching { detail.render(Region(0.0, 0.0, 1.0, 1.0), 1, 1, false) }.exceptionOrNull()?.message
        }
        renderer.start()
        assertTrue(inner.started.await(5, TimeUnit.SECONDS))

        guarded.close()
        assertEquals(0, inner.closes.get())
        inner.proceed.countDown()
        renderer.join(5_000)
        assertEquals("rendered", message)
        assertEquals(1, inner.closes.get())

        // Later renders fail without reaching the closed source.
        assertTrue(runCatching { detail.render(Region(0.0, 0.0, 1.0, 1.0), 1, 1, false) }.exceptionOrNull() is java.io.IOException)
        assertEquals(1, inner.renders)
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
