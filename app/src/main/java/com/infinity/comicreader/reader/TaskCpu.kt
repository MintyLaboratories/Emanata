package com.infinity.comicreader.reader

import java.io.File

/**
 * CPU time of each of this process's threads, read from /proc ([UiCpu.cpuNs]): calibration
 * checks with it that no thread but its own used the CPU while it timed a setup. Names are
 * read once per thread.
 */
internal class TaskCpu(private val tasks: File) {
    private val names = HashMap<Int, String>()

    /** True once a thread's time came in clock ticks (no schedstat): times are then only good to [UiCpu.TICK_NS]. */
    var coarse = false
        private set

    /** Each live thread's CPU time so far in ns, by thread id. */
    fun snapshot(): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        for (name in tasks.list().orEmpty()) {
            val tid = name.toIntOrNull() ?: continue
            val schedstat = runCatching { File(tasks, "$tid/schedstat").readText() }.getOrNull()
            val stat = if (schedstat == null) runCatching { File(tasks, "$tid/stat").readText() }.getOrNull() else null
            if (schedstat == null && stat != null) coarse = true
            out[tid] = UiCpu.cpuNs(schedstat, stat) ?: continue
        }
        return out
    }

    /** The name of thread [tid] (its `comm`), "" when unknown. */
    fun name(tid: Int): String = names.getOrPut(tid) { runCatching { File(tasks, "$tid/comm").readText().trim() }.getOrDefault("") }

    companion object {
        fun self(): TaskCpu = TaskCpu(File("/proc/self/task"))
    }
}
