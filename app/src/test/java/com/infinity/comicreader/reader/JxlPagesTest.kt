package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.compact.LibJxl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException

/** [JxlPages] on a stand-in for native code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JxlPagesTest {

    /** Native code as the JVM can have it: returns [result], every call recorded, painting the bitmap red when it decodes. */
    private class FakeKernel(var result: Int = LibJxl.PAGE_OPAQUE, val fail: Throwable? = null) : JxlPageKernel {
        class Call(val out: Bitmap, val threads: Int, val cores: Long, val stop: LibJxl.Stop?)

        val calls = ArrayList<Call>()

        override fun page(jxl: ByteArray, out: Bitmap, threads: Int, cores: Long, stop: LibJxl.Stop?): Int {
            calls += Call(out, threads, cores, stop)
            if (stop?.stop() == true) return LibJxl.PAGE_CANCELED
            fail?.let { throw it }
            if (result > 0) out.eraseColor(0xFFFF0000.toInt())
            return result
        }
    }

    /** The tablet's cores: four little (cpu0-3), four large (cpu4-7). */
    private val tablet = CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024))

    private var kept = listOf<Any?>()

    @Before
    fun keep() {
        kept = listOf(JxlPages.enabled, JxlPages.threads, JxlPages.laterThreads, JxlPages.pinned, JxlPages.laterOnLittle)
        JxlPages.cores = tablet
        JxlPages.enabled = true
        JxlPages.threads = 4
        JxlPages.laterThreads = 4
        JxlPages.pinned = true
        JxlPages.laterOnLittle = false
    }

    @After
    fun restore() {
        JxlPages.enabled = kept[0] as Boolean
        JxlPages.threads = kept[1] as Int
        JxlPages.laterThreads = kept[2] as Int
        JxlPages.pinned = kept[3] as Boolean
        JxlPages.laterOnLittle = kept[4] as Boolean
        JxlPages.cores = null
        JxlPages.kernel = null
    }

    private val bytes = ByteArray(16)

    @Test
    fun decodesIntoABitmapOfTheShownSizeOnTheLargeCores() {
        val k = FakeKernel().also { JxlPages.kernel = it }
        val page = JxlPages.decode(bytes, 30, 20, urgent = true)!!
        assertEquals(30, page.width)
        assertEquals(20, page.height)
        assertEquals(Bitmap.Config.ARGB_8888, page.config)
        assertFalse(page.hasAlpha())
        assertEquals(0xFFFF0000.toInt(), page.getPixel(3, 4))
        val call = k.calls.single()
        assertSame(page, call.out)
        assertEquals(4, call.threads)
        assertEquals(0xF0L, call.cores)
    }

    @Test
    fun alphaPagesKeepTheirAlpha() {
        JxlPages.kernel = FakeKernel(LibJxl.PAGE_ALPHA)
        assertTrue(JxlPages.decode(bytes, 4, 4, urgent = true)!!.hasAlpha())
    }

    @Test
    fun laterWorkRunsOnItsOwnBudget() {
        val k = FakeKernel().also { JxlPages.kernel = it }
        JxlPages.laterThreads = 2
        JxlPages.decode(bytes, 4, 4, urgent = false)
        assertEquals(2, k.calls.last().threads)
        assertEquals(0xF0L, k.calls.last().cores)
        JxlPages.laterOnLittle = true
        JxlPages.decode(bytes, 4, 4, urgent = false)
        assertEquals(0x0FL, k.calls.last().cores)
        // Urgent work stays on the large cores.
        JxlPages.decode(bytes, 4, 4, urgent = true)
        assertEquals(0xF0L, k.calls.last().cores)
        assertEquals(4, k.calls.last().threads)
    }

    @Test
    fun unpinnedLeavesPlacementToTheSystem() {
        val k = FakeKernel().also { JxlPages.kernel = it }
        JxlPages.pinned = false
        JxlPages.decode(bytes, 4, 4, urgent = true)
        assertEquals(0L, k.calls.single().cores)
    }

    @Test
    fun threadCountsStayInRange() {
        JxlPages.threads = 0
        assertEquals(1, JxlPages.placement(urgent = true).first)
        JxlPages.threads = 99
        assertEquals(16, JxlPages.placement(urgent = true).first)
    }

    @Test
    fun urgencyComesFromTheThreadByDefault() {
        val k = FakeKernel().also { JxlPages.kernel = it }
        JxlPages.laterThreads = 1
        Urgency.later { JxlPages.decode(bytes, 4, 4) }
        assertEquals(1, k.calls.last().threads)
        JxlPages.decode(bytes, 4, 4)
        assertEquals(4, k.calls.last().threads)
    }

    @Test
    fun imagesItDoesNotTakeOrCannotDecodeGoElsewhere() {
        for (result in listOf(LibJxl.PAGE_UNSUPPORTED, LibJxl.PAGE_FAILED)) {
            val k = FakeKernel(result).also { JxlPages.kernel = it }
            assertNull(JxlPages.decode(bytes, 4, 4, urgent = true))
            assertTrue(k.calls.single().out.isRecycled)
        }
    }

    @Test
    fun aThrowingDecodeFreesItsBitmap() {
        val k = FakeKernel(fail = IllegalStateException("boom")).also { JxlPages.kernel = it }
        assertThrows(IllegalStateException::class.java) { JxlPages.decode(bytes, 4, 4, urgent = true) }
        assertTrue(k.calls.single().out.isRecycled)
    }

    @Test
    fun offOrWithoutNativeCodeNothingIsDecoded() {
        val k = FakeKernel().also { JxlPages.kernel = it }
        JxlPages.enabled = false
        assertNull(JxlPages.decode(bytes, 4, 4, urgent = true))
        assertTrue(k.calls.isEmpty())
        JxlPages.enabled = true
        // The JVM cannot load libjxl: no kernel.
        JxlPages.kernel = null
        assertNull(JxlPages.decode(bytes, 4, 4, urgent = true))
    }

    @Test
    fun aCanceledDecodeThrowsAndFreesItsBitmap() {
        val k = FakeKernel().also { JxlPages.kernel = it }
        assertThrows(CancellationException::class.java) {
            Cancellation.during({ true }) { JxlPages.decode(bytes, 4, 4, urgent = true) }
        }
        assertTrue(k.calls.single().out.isRecycled)
        // Not canceled: decodes.
        assertFalse(Cancellation.during({ false }) { JxlPages.decode(bytes, 4, 4, urgent = true) }!!.isRecycled)
    }
}
