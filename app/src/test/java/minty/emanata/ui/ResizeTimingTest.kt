package minty.emanata.ui

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResizeTimingTest {

    private var clock = 0L
    private val timing = ResizeTiming { clock }

    @Test
    fun timesTheFirstFrameAndTheLastExactPage() {
        val left = Any()
        val right = Any()
        clock = 1_000
        timing.begin()
        timing.standIn(left)
        timing.standIn(right)
        clock = 1_016
        assertEquals(16L, timing.drawn())
        assertNull(timing.drawn())
        clock = 1_120
        assertNull(timing.done(left))
        clock = 1_300
        assertEquals(300L, timing.done(right))
        assertFalse(timing.running)
        assertNull(timing.done(right))
    }

    @Test
    fun exactPagesBeforeTheFirstFrameEndWithIt() {
        val page = Any()
        timing.begin()
        timing.standIn(page)
        clock = 5
        assertNull(timing.done(page))
        clock = 16
        assertEquals(16L, timing.drawn())
        assertFalse(timing.running)
    }

    @Test
    fun aNewResizeStartsOver() {
        val page = Any()
        timing.begin()
        timing.standIn(page)
        clock = 50
        timing.begin()
        assertTrue(timing.running)
        // The page waited for the earlier resize, not this one.
        assertNull(timing.done(page))
        clock = 66
        assertEquals(16L, timing.drawn())
        assertFalse(timing.running)
    }

    @Test
    fun slotsNotStandingInAreIgnored() {
        assertNull(timing.done(Any()))
        assertNull(timing.drawn())
        timing.standIn(Any())
        assertFalse(timing.running)
    }
}
