package com.infinity.comicreader.debug

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The battery current, battery temperature and thermal state every [everyMs] through a
 * session ([ProcStats], without memory): its mean current (negative while draining; on a
 * charger, what the charger leaves short), and the warmest it got.
 */
internal class PowerSampler(private val context: Context, private val everyMs: Long = EVERY_MS) {
    private val samples = ArrayList<ProcStats>()
    private var job: Job? = null
    private var from = 0L
    private var to = 0L

    fun start() {
        from = System.nanoTime()
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val s = ProcStats.now(context, memory = false)
                synchronized(samples) { samples += s }
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
        val currents = all.mapNotNull { it.currentMa }
        val temps = all.mapNotNull { it.batteryDeciC }
        val headrooms = all.map { it.headroom }.filter { !it.isNaN() }
        return JSONObject()
            .put("samples", all.size)
            .put("minutes", Stats.round1((to - from) / 6e10))
            .put("meanCurrentMa", if (currents.isEmpty()) JSONObject.NULL else currents.average().toLong())
            .put("batteryStartC", temps.firstOrNull()?.let { it / 10.0 } ?: JSONObject.NULL)
            .put("batteryMaxC", temps.maxOrNull()?.let { it / 10.0 } ?: JSONObject.NULL)
            .put("maxThermal", all.maxOfOrNull { it.thermal } ?: JSONObject.NULL)
            .put("maxHeadroom", headrooms.maxOrNull()?.let { Stats.round1(it * 100.0) / 100 } ?: JSONObject.NULL)
            .put("plugged", all.any { it.plugged })
    }

    private companion object {
        const val EVERY_MS = 2_000L
    }
}
