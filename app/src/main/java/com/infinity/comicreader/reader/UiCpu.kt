package com.infinity.comicreader.reader

import java.io.File

/**
 * CPU time of this process's UI threads (the main thread, RenderThread, hwuiTask*): the app
 * drawing itself, which calibration leaves out of what it measures. Read from /proc, in
 * nanoseconds where the kernel keeps them (schedstat), else in clock ticks (stat).
 */
internal class UiCpu(private val tasks: File, private val mainTid: Int) {
    private var tids: List<Int> = emptyList()

    /** Finds the UI threads again; once is enough, as they live as long as the process. */
    fun scan() {
        tids = tasks.listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.filter { tid ->
            tid == mainTid || isUi(runCatching { File(tasks, "$tid/comm").readText() }.getOrDefault(""))
        }
    }

    /** Their CPU time so far in ms; threads gone since [scan] count no more. */
    fun ms(): Double = tids.sumOf { tid ->
        cpuNs(
            runCatching { File(tasks, "$tid/schedstat").readText() }.getOrNull(),
            runCatching { File(tasks, "$tid/stat").readText() }.getOrNull()
        ) ?: 0L
    } / 1e6

    companion object {
        /** Linux clock ticks per second, as Android's kernels count them. */
        private const val TICK_NS = 10_000_000L

        /** True for the names of threads that draw: RenderThread and its helpers. */
        fun isUi(comm: String): Boolean {
            val name = comm.trim()
            return name == "RenderThread" || name.startsWith("hwuiTask")
        }

        /** A thread's CPU time in ns: [schedstat]'s first field, else [stat]'s utime + stime ticks; null when neither reads. */
        fun cpuNs(schedstat: String?, stat: String?): Long? {
            schedstat?.trim()?.split(' ')?.firstOrNull()?.toLongOrNull()?.let { return it }
            // Fields after the parenthesized name, which may hold spaces: state is field 3, utime 14, stime 15.
            val rest = stat?.substringAfterLast(')')?.trim()?.split(' ') ?: return null
            val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
            return (utime + stime) * TICK_NS
        }

        /** This process's. */
        fun self(): UiCpu = UiCpu(File("/proc/self/task"), android.os.Process.myPid())
    }
}
