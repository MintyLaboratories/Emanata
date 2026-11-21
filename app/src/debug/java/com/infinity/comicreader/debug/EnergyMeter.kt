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
 * refreshes only when the battery service posts (`voltageSource`). Every second it reads
 * the thermal status, the charger state (the battery's own status other than discharging,
 * or the plug) and whether the screen is on: a charger or a screen off seen at any check
 * refuses the estimate. A measurement holds a partial wake lock, so the sampler keeps
 * running whatever the screen does.
 * Valid only with the cable out (adb over Wi-Fi): under `dumpsys battery unplug` a charger
 * still supplies power. Keep the screen on for the whole run: the app in front on a screen
 * that keeps it on (the debug calibration screen), or a screen timeout longer than the run.
 */
internal class EnergyMeter(private val context: Context, private val everyMs: Long = EVERY_MS) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
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
    fun thermal(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else -1

    /**
     * Samples while [work] runs, given its deadline [ms] from now, until it returns; [heat],
     * when given, sees the thermal status every second.
     */
    fun sample(ms: Long, heat: ResampleRuns.Heat? = null, work: (until: Long) -> Unit): EnergyRuns.Draw {
        val samples = ArrayList<EnergyRuns.Sample>()
        var charger = onCharger()
        var screenOff = !power.isInteractive
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
                        if (!power.isInteractive) screenOff = true
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
        if (!power.isInteractive) screenOff = true
        return synchronized(samples) { EnergyRuns.Draw(samples.toList(), charger, error, screenOff) }
    }

    /**
     * What a busy window made: [pages], the last of them ending at [endAt]
     * ([SystemClock.elapsedRealtime]) with the process at [cpuAt] ms of CPU time. Made when
     * the last counted page ends, so work after it (a page stopped partway) is left out.
     */
    class Made(val pages: Int, val endAt: Long = SystemClock.elapsedRealtime(), val cpuAt: Long = Process.getElapsedCpuTime())

    /**
     * [idleS] s of rest sampled as the baseline, then [work] for [seconds] s, returning what
     * it made; the busy window ends with its last counted page. Then [afterS] s of rest
     * sampled when given, as the idle after, not subtracted. Under a partial wake lock.
     */
    fun measure(idleS: Int, seconds: Int, heat: ResampleRuns.Heat, afterS: Int = 0, work: (until: Long) -> Made): EnergyRuns.Run {
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
        lock.acquire((idleS + seconds + afterS) * 1000L + WAKE_SLACK_MS)
        try {
            val idle = sample(idleS * 1000L, heat) { until -> SystemClock.sleep(until - SystemClock.elapsedRealtime()) }
            var made = Made(0)
            var from = 0L
            val cpu0 = Process.getElapsedCpuTime()
            val busy = sample(seconds * 1000L, heat) { until ->
                from = until - seconds * 1000L
                made = work(until)
            }
            val endMs = (made.endAt - from).coerceAtLeast(0)
            val after = if (afterS > 0) sample(afterS * 1000L, heat) { until -> SystemClock.sleep(until - SystemClock.elapsedRealtime()) } else null
            return EnergyRuns.Run(idle, busy.until(endMs), endMs / 1000.0, made.pages, made.cpuAt - cpu0, after)
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    /**
     * [run] as one result's fields: what it made, its draw, its voltages, the gauge's
     * refresh, the energy per page, and the idle after when measured ([afterS] s).
     */
    fun json(run: EnergyRuns.Run, idleS: Int, afterS: Int = 0): JSONObject = JSONObject()
        .put("seconds", run.seconds).put("pages", run.pages).put("pagesPerS", run.pagesPerS).put("cpuMsPerPage", run.cpuMsPerPage)
        .put("idleS", idleS).put("idleMa", num(run.idle.meanMa)).put("busyMa", num(run.busy.meanMa))
        .put("idleW", num(run.idle.meanMw / 1000)).put("busyW", num(run.busy.meanMw / 1000))
        .put("aboveIdleW", run.aboveIdleW ?: JSONObject.NULL)
        .apply {
            run.after?.let { a ->
                put("afterIdleS", afterS).put("afterIdleMa", num(a.meanMa)).put("afterIdleW", num(a.meanMw / 1000))
            }
        }
        .put("volts", JSONObject().put("source", voltageSource).put("min", num(run.busy.minVolts)).put("max", num(run.busy.maxVolts))
            .put("distinct", run.busy.distinctVolts))
        .put("samples", JSONObject().put("idle", run.idle.samples.size).put("busy", run.busy.samples.size))
        .put("idleDistinctPer10s", JSONArray(run.idle.distinctPer()))
        .put("busyDistinct", run.busy.distinct)
        .put("mJPerPage", run.mJPerPage ?: JSONObject.NULL)
        .put("refused", run.refused ?: JSONObject.NULL)

    /**
     * One setup's energy per page across its [rounds]: the valid ones measured, and their
     * spread ([EnergyRuns.Rounds]); those measured but not valid listed apart (`invalidMj`).
     */
    fun summary(key: String, rounds: EnergyRuns.Rounds): JSONObject {
        val s = rounds.spread
        return JSONObject().put("energy", key).put("rounds", rounds.count).put("measured", rounds.measured).put("valid", rounds.valid.size)
            .put("mJPerPage", JSONArray(rounds.valid.map { Stats.round1(it) }))
            .put("invalidMj", JSONArray(rounds.invalid.map { Stats.round1(it) }))
            .put("meanMj", s?.let { Stats.round1(it.mean) } ?: JSONObject.NULL)
            .put("minMj", s?.let { Stats.round1(it.min) } ?: JSONObject.NULL)
            .put("maxMj", s?.let { Stats.round1(it.max) } ?: JSONObject.NULL)
            .put("spreadPct", s?.let { Stats.round1(it.pct) } ?: JSONObject.NULL)
    }

    /** [v], or null where JSON has no number for it. */
    private fun num(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    private companion object {
        const val EVERY_MS = 100L

        const val WAKE_TAG = "emanata:energy"

        /** Beyond a measurement's planned length, for a page still running at its deadline. */
        const val WAKE_SLACK_MS = 10 * 60_000L

        /** Thermal and charger checks every this many samples: once a second. */
        const val CHECK_EVERY = 10

        val VOLTS_NODE = File("/sys/class/power_supply/battery/voltage_now")
    }
}
