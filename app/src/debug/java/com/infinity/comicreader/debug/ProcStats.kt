package com.infinity.comicreader.debug

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import org.json.JSONObject

/**
 * The app process's memory (PSS, of it graphics, native and Java heap), CPU time, and
 * the device's thermal state at one moment, for benchmarks. Reading memory walks the
 * process's mappings (tens of ms), so it is taken between measurements, never during one.
 */
internal class ProcStats private constructor(
    val cpuMs: Long,
    val pssKb: Long,
    val graphicsKb: Long,
    val nativeKb: Long,
    val javaKb: Long,
    val thermal: Int,
    val headroom: Float
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pssMb", pssKb / 1024)
        .put("graphicsMb", graphicsKb / 1024)
        .put("nativeMb", nativeKb / 1024)
        .put("javaMb", javaKb / 1024)
        .put("thermal", thermal)
        .put("headroom", if (headroom.isNaN()) JSONObject.NULL else Stats.round1(headroom.toDouble() * 100) / 100)

    companion object {
        /** Now; with [memory] false, CPU and thermal state only. */
        fun now(context: Context, memory: Boolean = true): ProcStats {
            val power = context.getSystemService(PowerManager::class.java)
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else -1
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(0) else Float.NaN
            val cpu = Process.getElapsedCpuTime()
            if (!memory) return ProcStats(cpu, 0, 0, 0, 0, thermal, headroom)
            val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            fun stat(name: String) = info.getMemoryStat(name)?.toLongOrNull() ?: 0L
            return ProcStats(
                cpu, info.totalPss.toLong(), stat("summary.graphics"), stat("summary.native-heap"), stat("summary.java-heap"),
                thermal, headroom
            )
        }
    }
}
