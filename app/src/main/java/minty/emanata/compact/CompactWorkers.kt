package minty.emanata.compact

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.util.concurrent.ThreadFactory

/**
 * How many pages convert at once: half the cores, at most four, on background-priority
 * threads; fewer as the device warms up or saves power, so a long run stays cool.
 */
object CompactWorkers {
    /** Threads at most. */
    fun max(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores / 2).coerceIn(1, MAX)

    /** Workers allowed of [max] at thermal [status] (PowerManager's THERMAL_STATUS_*) and in [powerSave]. */
    fun allowed(max: Int, status: Int, powerSave: Boolean): Int {
        val byHeat = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 1
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            status >= PowerManager.THERMAL_STATUS_LIGHT -> max - 1
            else -> max
        }
        val byPower = if (powerSave) 1 else max
        return minOf(max, byHeat, byPower).coerceAtLeast(1)
    }

    /** Workers allowed now of [max], asked of the system at most every [CHECK_MS]. */
    fun limit(context: Context, max: Int): () -> Int {
        val power = context.applicationContext.getSystemService(PowerManager::class.java)
        var checked = 0L
        var allowed = max
        return {
            synchronized(this) {
                val now = System.nanoTime() / 1_000_000
                if (now - checked >= CHECK_MS) {
                    checked = now
                    val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus ?: 0 else 0
                    allowed = allowed(max, status, power?.isPowerSaveMode == true)
                }
                allowed
            }
        }
    }

    /** Background-priority threads, so the screen stays smooth while pages convert. */
    val threads = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "compact").apply { isDaemon = true }
    }

    private const val MAX = 4
    private const val CHECK_MS = 2_000L
}
