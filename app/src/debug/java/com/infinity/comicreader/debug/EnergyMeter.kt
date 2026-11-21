package com.infinity.comicreader.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Energy per page on battery alone, for the check services: an idle baseline, then work,
 * sampling the battery current and voltage together every [everyMs] ([EnergyRuns]). Current
 * is BatteryManager's `CURRENT_NOW`; voltage is the battery's sysfs node where the app may
 * read it, else the battery broadcast, read once a second with the charger state, which
 * refreshes only when the battery service posts (`voltageSource`). Every second it reads the thermal status and the charger state (the
 * battery's own status other than discharging, or the plug): a charger seen at any check
 * refuses the estimate.
 * Valid only with the cable out (adb over Wi-Fi): under `dumpsys battery unplug` a charger
 * still supplies power.
 */
internal class EnergyMeter(private val context: Context, private val everyMs: Long = EVERY_MS) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val node: File? = VOLTS_NODE.takeIf { EnergyRuns.voltsFromNode(read(it)) != null }

    /** Where voltages come from: "sysfs" or "broadcast". */
    val voltageSource: String get() = if (node != null) "sysfs" else "broadcast"

    private fun read(f: File): String? = runCatching { f.readText() }.getOrNull()

    private fun sticky(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** The battery broadcast's voltage in V (0 when unknown) as [onCharger] last read it: once a second while sampling. */
    @Volatile
    private var broadcastVolts = 0.0

    private fun volts(): Double = node?.let { EnergyRuns.voltsFromNode(read(it)) } ?: broadcastVolts

    /** True while the battery reports a charger ([EnergyRuns.onCharger]); keeps the broadcast's voltage for [volts]. */
    fun onCharger(): Boolean {
        val b = sticky()
        broadcastVolts = (b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
        return EnergyRuns.onCharger(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS), b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
    }

    /** The thermal status now; -1 before Android 10. */
    fun thermal(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(PowerManager::class.java).currentThermalStatus else -1

    /**
     * Samples while [work] runs, given its deadline [ms] from now, until it returns; [heat],
     * when given, sees the thermal status every second.
     */
    fun sample(ms: Long, heat: ResampleRuns.Heat? = null, work: (until: Long) -> Unit): EnergyRuns.Draw {
        val samples = ArrayList<EnergyRuns.Sample>()
        var charger = onCharger()
        val t0 = SystemClock.elapsedRealtime()
        val until = t0 + ms
        val done = AtomicBoolean(false)
        var error: String? = null
        val sampler = thread(name = "energy") {
            var n = 0
            // A failure ends the readings and refuses the estimate, without taking the process down.
            try {
                while (!done.get()) {
                    if (n++ % CHECK_EVERY == 0) {
                        heat?.see(thermal())
                        if (onCharger()) charger = true
                    }
                    // Microamperes, as on most devices.
                    val ua = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    if (ua != Int.MIN_VALUE) {
                        val v = volts()
                        synchronized(samples) { samples += EnergyRuns.Sample(SystemClock.elapsedRealtime() - t0, ua / 1000.0, v) }
                    }
                    Thread.sleep(everyMs)
                }
            } catch (t: Throwable) {
                error = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        try {
            work(until)
        } finally {
            done.set(true)
            // The sampler's samples, statuses and charger are seen from here on.
            sampler.join()
        }
        if (onCharger()) charger = true
        return synchronized(samples) { EnergyRuns.Draw(samples.toList(), charger, error) }
    }

    /** [idleS] s of rest sampled as the baseline, then [work] for [seconds] s, returning the pages it made. */
    fun measure(idleS: Int, seconds: Int, heat: ResampleRuns.Heat, work: (until: Long) -> Int): EnergyRuns.Run {
        val idle = sample(idleS * 1000L, heat) { until -> SystemClock.sleep(until - SystemClock.elapsedRealtime()) }
        var pages = 0
        val t0 = SystemClock.elapsedRealtime()
        val cpu0 = Process.getElapsedCpuTime()
        val busy = sample(seconds * 1000L, heat) { until -> pages = work(until) }
        val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
        return EnergyRuns.Run(idle, busy, wall, pages, Process.getElapsedCpuTime() - cpu0)
    }

    /** [run] as one result's fields: what it made, its draw, its voltages, the gauge's refresh and the energy per page. */
    fun json(run: EnergyRuns.Run, idleS: Int): JSONObject = JSONObject()
        .put("seconds", run.seconds).put("pages", run.pages).put("pagesPerS", run.pagesPerS).put("cpuMsPerPage", run.cpuMsPerPage)
        .put("idleS", idleS).put("idleMa", num(run.idle.meanMa)).put("busyMa", num(run.busy.meanMa))
        .put("idleW", num(run.idle.meanMw / 1000)).put("busyW", num(run.busy.meanMw / 1000))
        .put("aboveIdleW", run.aboveIdleW ?: JSONObject.NULL)
        .put("volts", JSONObject().put("source", voltageSource).put("min", num(run.busy.minVolts)).put("max", num(run.busy.maxVolts))
            .put("distinct", run.busy.distinctVolts))
        .put("samples", JSONObject().put("idle", run.idle.samples.size).put("busy", run.busy.samples.size))
        .put("idleDistinctPer10s", JSONArray(run.idle.distinctPer()))
        .put("busyDistinct", run.busy.distinct)
        .put("mJPerPage", run.mJPerPage ?: JSONObject.NULL)
        .put("refused", run.refused ?: JSONObject.NULL)

    /** One setup's energy per page across its rounds: those measured, and their spread ([EnergyRuns.spread]). */
    fun summary(key: String, runs: List<EnergyRuns.Run>): JSONObject {
        val mj = runs.mapNotNull { it.mJPerPage }
        val s = EnergyRuns.spread(mj)
        return JSONObject().put("energy", key).put("rounds", runs.size).put("measured", mj.size)
            .put("mJPerPage", JSONArray(mj.map { Stats.round1(it) }))
            .put("meanMj", s?.let { Stats.round1(it.mean) } ?: JSONObject.NULL)
            .put("minMj", s?.let { Stats.round1(it.min) } ?: JSONObject.NULL)
            .put("maxMj", s?.let { Stats.round1(it.max) } ?: JSONObject.NULL)
            .put("spreadPct", s?.let { Stats.round1(it.pct) } ?: JSONObject.NULL)
    }

    /** [v], or null where JSON has no number for it. */
    private fun num(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    private companion object {
        const val EVERY_MS = 100L

        /** Thermal and charger checks every this many samples: once a second. */
        const val CHECK_EVERY = 10

        val VOLTS_NODE = File("/sys/class/power_supply/battery/voltage_now")
    }
}
