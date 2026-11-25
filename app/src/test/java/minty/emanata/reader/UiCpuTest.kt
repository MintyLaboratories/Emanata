package minty.emanata.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UiCpuTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun drawingThreadsAreUi() {
        assertTrue(UiCpu.isUi("RenderThread\n"))
        assertTrue(UiCpu.isUi("hwuiTask1"))
        assertFalse(UiCpu.isUi("jxl-decode-1"))
        assertFalse(UiCpu.isUi("DefaultDispatch"))
    }

    @Test
    fun schedstatNanosecondsComeFirst() {
        assertEquals(4_576_512L, UiCpu.cpuNs("4576512 111197 18\n", "1 (x) S 0 0 0 0 0 0 0 0 0 0 7 3"))
    }

    @Test
    fun statTicksStandInWithoutSchedstat() {
        // A name holding spaces and a parenthesis; utime 7, stime 3 ticks of 10 ms.
        val stat = "123 (a (b) c) S 1 2 3 4 5 6 7 8 9 10 7 3 0 0 20 0 1 0"
        assertEquals(100_000_000L, UiCpu.cpuNs(null, stat))
        assertNull(UiCpu.cpuNs(null, "123 (x) S 1"))
        assertNull(UiCpu.cpuNs(null, null))
    }

    @Test
    fun countsTheMainThreadAndDrawingThreadsOnly() {
        val tasks = tmp.newFolder("task")
        fun task(tid: Int, name: String, ns: Long) = File(tasks, "$tid").apply {
            mkdirs()
            File(this, "comm").writeText("$name\n")
            File(this, "schedstat").writeText("$ns 0 0\n")
        }
        task(100, "app.main", 1_000_000)
        task(101, "RenderThread", 2_000_000)
        task(102, "hwuiTask0", 3_000_000)
        task(103, "jxl-decode-1", 50_000_000)
        val ui = UiCpu(tasks, mainTid = 100)
        assertEquals(0.0, ui.ms(), 0.0)
        ui.scan()
        assertEquals(6.0, ui.ms(), 1e-9)
        // A thread gone counts no more.
        File(tasks, "102").deleteRecursively()
        assertEquals(3.0, ui.ms(), 1e-9)
    }
}
