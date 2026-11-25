package minty.emanata.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** On a plain JVM, whose android.os.Trace only throws, sections are left out and the work still runs. */
class TracesTest {
    @Test
    fun aSectionRunsItsBlockWithoutAPlatform() {
        assertFalse(Traces.usable)
        assertFalse(Traces.recording())
        assertEquals(4, Traces.section("work") { 2 + 2 })
        assertEquals("done", Traces.section({ error("never built") }) { "done" })
    }

    @Test
    fun timedWorkRunsWithoutAPlatform() {
        assertEquals(7, Timing.span("work") { 7 })
    }

    @Test
    fun anAsyncSectionEndsQuietly() {
        val queued = Traces.queued { error("never built") }
        queued.end()
        queued.end()
    }
}
