package minty.emanata.debug

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a thread runs on at one moment, for benchmarks: its cpuset and allowed CPUs, the GPU's
 * clock and busy figure (Adreno's kgsl), and each CPU cluster's clock, all read from procfs and
 * sysfs under [root] ("/" on a device). A field the system does not show or refuses is null.
 */
internal object DeviceClocks {
    private const val KGSL = "sys/class/kgsl/kgsl-3d0"
    private const val POLICIES = "sys/devices/system/cpu/cpufreq"

    class Snapshot(
        /** The thread's name, as the kernel keeps it. */
        val thread: String?,
        val cpuset: String?,
        /** The CPUs the thread may run on, as the kernel lists them ("0-3,7"). */
        val cpus: String?,
        val gpuMhz: Int?,
        val gpuBusyPct: Int?,
        /** Clock per cpufreq policy ("policy0"), in policy order; null where unreadable. Empty when none shows. */
        val cpuMhz: Map<String, Int?>
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("thread", thread ?: JSONObject.NULL)
            .put("cpuset", cpuset ?: JSONObject.NULL)
            .put("cpus", cpus ?: JSONObject.NULL)
            .put("gpuMhz", gpuMhz ?: JSONObject.NULL)
            .put("gpuBusy", gpuBusyPct ?: JSONObject.NULL)
            .put("cpuMhz", if (cpuMhz.isEmpty()) JSONObject.NULL else JSONObject().apply { cpuMhz.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
    }

    /** Thread [tid] of this process and the clocks now. */
    fun read(root: File, tid: Int): Snapshot = Snapshot(
        thread = text(root, "proc/self/task/$tid/comm"),
        cpuset = cpuset(root, tid),
        cpus = cpus(root, tid),
        gpuMhz = gpuMhz(root),
        gpuBusyPct = gpuBusy(root),
        cpuMhz = cpuMhz(root)
    )

    /** The id of this process's thread named [name]: [hint] when it still is, else the first found; null when none is. */
    fun threadNamed(root: File, name: String, hint: Int? = null): Int? {
        if (hint != null && text(root, "proc/self/task/$hint/comm") == name) return hint
        val tasks = File(root, "proc/self/task").listFiles() ?: return null
        return tasks.mapNotNull { it.name.toIntOrNull() }.sorted().firstOrNull { text(root, "proc/self/task/$it/comm") == name }
    }

    fun cpuset(root: File, tid: Int): String? = text(root, "proc/self/task/$tid/cpuset")

    fun cpus(root: File, tid: Int): String? =
        text(root, "proc/self/task/$tid/status")?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** The GPU clock in MHz: devfreq's `cur_freq`, else `gpuclk` (both in Hz). */
    fun gpuMhz(root: File): Int? = (number(root, "$KGSL/devfreq/cur_freq") ?: number(root, "$KGSL/gpuclk"))?.let { (it / 1_000_000).toInt() }

    /** How busy the GPU was over the driver's last window, in percent ("37 %"). */
    fun gpuBusy(root: File): Int? = number(root, "$KGSL/gpu_busy_percentage")?.toInt()?.takeIf { it in 0..100 }

    /** Each cpufreq policy's `scaling_cur_freq` (kHz) in MHz. */
    fun cpuMhz(root: File): Map<String, Int?> {
        val policies = File(root, POLICIES).listFiles { f -> f.name.startsWith("policy") && f.name.removePrefix("policy").toIntOrNull() != null }
            ?: return emptyMap()
        return policies.sortedBy { it.name.removePrefix("policy").toInt() }
            .associate { p -> p.name to number(root, "$POLICIES/${p.name}/scaling_cur_freq")?.let { (it / 1000).toInt() } }
    }

    /**
     * Snapshots taken through a run, summed up: their count, the cpusets seen, and the GPU
     * clock, GPU busy figure and each CPU clock as median, least and most of the samples read
     * (null when none was).
     */
    fun summary(samples: List<Snapshot>): JSONObject {
        val policies = samples.flatMap { it.cpuMhz.keys }.distinct()
        return JSONObject()
            .put("n", samples.size)
            .put("cpusets", JSONArray(samples.mapNotNull { it.cpuset }.distinct()))
            .put("gpuMhz", spread(samples.map { it.gpuMhz }))
            .put("gpuBusy", spread(samples.map { it.gpuBusyPct }))
            .put("cpuMhz", if (policies.isEmpty()) JSONObject.NULL else JSONObject().apply { policies.forEach { p -> put(p, spread(samples.map { it.cpuMhz[p] })) } })
    }

    private fun spread(values: List<Int?>): Any {
        val known = values.filterNotNull().sorted()
        if (known.isEmpty()) return JSONObject.NULL
        return JSONObject().put("median", Stats.percentile(known.map { it.toLong() }, 50)).put("min", known.first()).put("max", known.last())
    }

    /** The file's text, trimmed; null when missing, unreadable or empty. */
    private fun text(root: File, path: String): String? =
        runCatching { File(root, path).bufferedReader().use { it.readText() } }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** The leading whole number of the file's text; null when there is none. */
    private fun number(root: File, path: String): Long? = text(root, path)?.let { Regex("^\\d+").find(it)?.value?.toLongOrNull() }
}
