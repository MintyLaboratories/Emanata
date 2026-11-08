package com.infinity.comicreader.reader

import java.io.File

/**
 * The highest clock each CPU frequency domain may run at now (`scaling_max_freq` of each
 * `cpufreq/policyN`): the system lowers it under heat or power limits, often before any
 * thermal status shows. Calibration reads it around its samples ([Calibration.capped]).
 */
internal class ClockCaps(private val root: File) {
    private val files: List<File> = root.list().orEmpty()
        .mapNotNull { name -> name.takeIf { it.startsWith("policy") }?.substring(6)?.toIntOrNull()?.let { it to name } }
        .sortedBy { it.first }
        .map { File(root, "${it.second}/scaling_max_freq") }

    /** Each domain's cap in kHz, by policy number order; null when there are none or one cannot be read. */
    fun read(): IntArray? {
        if (files.isEmpty()) return null
        val out = IntArray(files.size)
        for (i in files.indices) out[i] = runCatching { files[i].readText().trim().toInt() }.getOrNull() ?: return null
        return out
    }

    companion object {
        fun device(): ClockCaps = ClockCaps(File("/sys/devices/system/cpu/cpufreq"))
    }
}
