package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskCpuTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun task(tasks: File, tid: Int, name: String, schedstat: String?, stat: String? = null) = File(tasks, "$tid").apply {
        mkdirs()
        File(this, "comm").writeText("$name\n")
        schedstat?.let { File(this, "schedstat").writeText(it) }
        stat?.let { File(this, "stat").writeText(it) }
    }

    @Test
    fun readsEveryThreadsCpuTimeAndNameOnce() {
        val tasks = tmp.newFolder("task")
        task(tasks, 100, "app.main", "1000000 0 0\n")
        task(tasks, 101, "DefaultDispatch", "2500000 0 0\n")
        File(tasks, "self").mkdirs()
        val cpu = TaskCpu(tasks)
        assertEquals(mapOf(100 to 1_000_000L, 101 to 2_500_000L), cpu.snapshot())
        assertFalse(cpu.coarse)
        assertEquals("DefaultDispatch", cpu.name(101))
        // Names are kept: a thread id reused later keeps the name first read.
        File(tasks, "101/comm").writeText("other\n")
        assertEquals("DefaultDispatch", cpu.name(101))
        assertEquals("", cpu.name(999))
    }

    @Test
    fun ticksStandInAndMarkTimesCoarse() {
        val tasks = tmp.newFolder("task")
        task(tasks, 7, "worker", null, "7 (worker) S 1 2 3 4 5 6 7 8 9 10 4 1 0 0 20 0 1 0")
        // Unreadable: left out.
        File(tasks, "8").mkdirs()
        val cpu = TaskCpu(tasks)
        assertEquals(mapOf(7 to 50_000_000L), cpu.snapshot())
        assertTrue(cpu.coarse)
    }
}
