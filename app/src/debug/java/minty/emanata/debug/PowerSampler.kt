package minty.emanata.debug

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The battery current and thermal state every [everyMs] through a session ([ProcStats],
 * without memory), and the app's memory every [memoryEvery] samples: its mean current
 * (negative while draining; on a charger, what the charger leaves short), the most thermal
 * headroom used, and the memory's peak and course. No temperature: the battery's comes from
 * the battery service, which `dumpsys battery unplug` freezes (bench.py reads the thermal
 * sensors from the host instead).
 */
internal class PowerSampler(private val context: Context, private val everyMs: Long = EVERY_MS, private val memoryEvery: Int = MEMORY_EVERY) {
    private val samples = ArrayList<ProcStats>()

    /** Seconds into the session, PSS and graphics in MB, every [memoryEvery] samples. */
    private val memory = ArrayList<LongArray>()
    private var job: Job? = null
    private var from = 0L
    private var to = 0L

    fun start() {
        from = System.nanoTime()
        job = CoroutineScope(Dispatchers.Default).launch {
            var n = 0
            while (isActive) {
                val s = ProcStats.now(context, memory = n++ % memoryEvery == 0)
                synchronized(samples) {
                    samples += s
                    if (s.pssKb > 0) memory += longArrayOf((System.nanoTime() - from) / 1_000_000_000, s.pssKb / 1024, s.graphicsKb / 1024)
                }
                delay(everyMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        to = System.nanoTime()
    }

    fun toJson(): JSONObject {
        val all = synchronized(samples) { samples.toList() }
        val mem = synchronized(samples) { memory.toList() }
        val currents = all.mapNotNull { it.currentMa }
        val headrooms = all.map { it.headroom }.filter { !it.isNaN() }
        return JSONObject()
            .put("samples", all.size)
            .put("minutes", Stats.round1((to - from) / 6e10))
            .put("meanCurrentMa", if (currents.isEmpty()) JSONObject.NULL else currents.average().toLong())
            .put("peakPssMb", mem.maxOfOrNull { it[1] } ?: JSONObject.NULL)
            .put("peakGraphicsMb", mem.maxOfOrNull { it[2] } ?: JSONObject.NULL)
            .put("memory", JSONArray(mem.map { JSONArray(it.toList()) }))
            .put("maxThermal", all.maxOfOrNull { it.thermal } ?: JSONObject.NULL)
            .put("maxHeadroom", headrooms.maxOrNull()?.let { Stats.round1(it * 100.0) / 100 } ?: JSONObject.NULL)
            .put("plugged", all.any { it.plugged })
    }

    private companion object {
        const val EVERY_MS = 2_000L

        /** Memory every 10 s: a reading walks the process's mappings (tens of ms). */
        const val MEMORY_EVERY = 5
    }
}
