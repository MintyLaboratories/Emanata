package minty.emanata.reader

import java.io.File

/**
 * The CPU's cores split by speed, read at runtime ([read]). Large cores are those at least
 * [largeShare] as fast as the fastest, the rest little. Masks have bit n set for core n; a
 * mask is 0 where pinning to it would change nothing (one kind of core) or cannot be known.
 *
 * [speeds] holds each core's speed by number, 0 where unknown; [capacities] each core's
 * `cpu_capacity` (the fastest 1024), empty where the kernel gives none.
 */
class CpuCores(speeds: IntArray, largeShare: Double = CAPACITY_SHARE, val capacities: IntArray = IntArray(0)) {
    /** True when the cores' speeds were read; otherwise nothing is pinned and counts are guesses. */
    val known: Boolean = speeds.any { it > 0 }

    private val least = (speeds.maxOrNull() ?: 0) * largeShare

    private val bigBits: Long = maskOf(speeds) { it > 0 && it >= least }

    private val littleBits: Long = maskOf(speeds) { it > 0 && it < least }

    val bigCount: Int = java.lang.Long.bitCount(bigBits)

    val littleCount: Int = java.lang.Long.bitCount(littleBits)

    /** The large cores, to pin work someone waits on to; 0 when every core is large or speeds are unknown. */
    val big: Long = if (littleBits == 0L) 0L else bigBits

    /** The little cores, to pin work nobody waits on yet to; 0 when there are none. */
    val little: Long = littleBits

    /** The fastest cores alone, for one thread someone waits on; 0 when every core is as fast. */
    val prime: Long = (speeds.maxOrNull() ?: 0).let { top ->
        maskOf(speeds) { it > 0 && it == top }.takeIf { it != maskOf(speeds) { s -> s > 0 } } ?: 0L
    }

    companion object {
        /** Large by the scheduler's `cpu_capacity`, which counts work per clock too: at least half the fastest's. */
        const val CAPACITY_SHARE = 0.5

        /** Large by clock speed alone, where a little core's lower work per clock does not show: at least 70% of the fastest's. */
        const val CLOCK_SHARE = 0.7

        /** This device's cores, read once. */
        val device: CpuCores by lazy { read(File("/sys/devices/system/cpu")) }

        /**
         * The cores under [root] (`/sys/devices/system/cpu`), by their `cpuN` folders: by each
         * one's `cpu_capacity` where every core has one, else by `cpufreq/cpuinfo_max_freq`;
         * none known when neither is complete.
         */
        fun read(root: File): CpuCores {
            val numbers = root.list()?.mapNotNull { name ->
                name.takeIf { it.startsWith("cpu") }?.substring(3)?.toIntOrNull()?.takeIf { it in 0 until Long.SIZE_BITS }
            } ?: emptyList()
            if (numbers.isEmpty()) return CpuCores(IntArray(0))
            fun all(file: String): IntArray? {
                val speeds = IntArray(numbers.max() + 1)
                for (n in numbers) speeds[n] = number(File(root, "cpu$n/$file")) ?: return null
                return speeds
            }
            all("cpu_capacity")?.let { return CpuCores(it, CAPACITY_SHARE, it) }
            all("cpufreq/cpuinfo_max_freq")?.let { return CpuCores(it, CLOCK_SHARE) }
            return CpuCores(IntArray(0))
        }

        private fun number(f: File): Int? = runCatching { f.readText().trim().toInt() }.getOrNull()?.takeIf { it > 0 }

        private inline fun maskOf(speeds: IntArray, pick: (Int) -> Boolean): Long {
            var m = 0L
            for (n in speeds.indices) if (n < Long.SIZE_BITS && pick(speeds[n])) m = m or (1L shl n)
            return m
        }
    }
}
