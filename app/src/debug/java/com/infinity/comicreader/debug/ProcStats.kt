package com.infinity.comicreader.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import org.json.JSONObject

/**
 * The app process's memory (PSS, of it graphics, native and Java heap), CPU time, and
 * the device's thermal and battery state at one moment, for benchmarks. Reading memory walks
 * the process's mappings (tens of ms), so it is taken between measurements, never during one.
 * A low battery may slow the device down: results compare only at like levels.
 */
internal class ProcStats private constructor(
    val cpuMs: Long,
    val pssKb: Long,
    val graphicsKb: Long,
    val nativeKb: Long,
    val javaKb: Long,
    val thermal: Int,
    val headroom: Float,
    /** Battery level in percent, -1 when unknown. */
    val batteryPct: Int,
    /** Current into the battery in mA, negative while it drains; null when unknown. */
    val currentMa: Int?,
    /** True while on external power. */
    val plugged: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pssMb", pssKb / 1024)
        .put("graphicsMb", graphicsKb / 1024)
        .put("nativeMb", nativeKb / 1024)
        .put("javaMb", javaKb / 1024)
        .put("thermal", thermal)
        .put("headroom", if (headroom.isNaN()) JSONObject.NULL else Stats.round1(headroom.toDouble() * 100) / 100)
        .put("batteryPct", batteryPct)
        .put("currentMa", currentMa ?: JSONObject.NULL)
        .put("plugged", plugged)

    companion object {
        /** Now; with [memory] false, CPU and thermal state only. */
        fun now(context: Context, memory: Boolean = true): ProcStats {
            val power = context.getSystemService(PowerManager::class.java)
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else -1
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(0) else Float.NaN
            val cpu = Process.getElapsedCpuTime()
            val battery = context.getSystemService(BatteryManager::class.java)
            val pct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
            // Microamperes; a device that does not tell gives the smallest Int.
            val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.let { it / 1000 }
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (!memory) return ProcStats(cpu, 0, 0, 0, 0, thermal, headroom, pct, current, plugged)
            val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            fun stat(name: String) = info.getMemoryStat(name)?.toLongOrNull() ?: 0L
            return ProcStats(
                cpu, info.totalPss.toLong(), stat("summary.graphics"), stat("summary.native-heap"), stat("summary.java-heap"),
                thermal, headroom, pct, current, plugged
            )
        }
    }
}
