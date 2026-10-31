package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CpuCoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun threeClustersSplitAtHalfTheFastest() {
        // Four little cores, three middle ones, one prime: the middle ones count as large.
        val c = CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024))
        assertTrue(c.known)
        assertEquals(4, c.bigCount)
        assertEquals(4, c.littleCount)
        assertEquals(0b1111_0000L, c.big)
        assertEquals(0b0000_1111L, c.little)
        assertEquals(0b1000_0000L, c.prime)
    }

    @Test
    fun twoFastClustersAreAllLarge() {
        val c = CpuCores(intArrayOf(800, 800, 800, 800, 800, 800, 1024, 1024))
        assertEquals(8, c.bigCount)
        assertEquals(0, c.littleCount)
        // Nothing to pin to: every core is large.
        assertEquals(0L, c.big)
        assertEquals(0L, c.little)
        // The two fastest still stand apart.
        assertEquals(0b1100_0000L, c.prime)
    }

    @Test
    fun equalCoresHaveNoPrime() {
        assertEquals(0L, CpuCores(intArrayOf(1024, 1024, 1024, 1024)).prime)
        assertEquals(0L, CpuCores(IntArray(0)).prime)
    }

    @Test
    fun unknownSpeedsPinNothing() {
        val c = CpuCores(IntArray(0))
        assertFalse(c.known)
        assertEquals(0L, c.big)
        assertEquals(0L, c.little)
        assertEquals(4, NativeResampler.defaultThreads(c, 8))
        assertEquals(2, NativeResampler.defaultThreads(c, 2))
    }

    @Test
    fun oneWorkerPerLargeCore() {
        assertEquals(4, NativeResampler.defaultThreads(CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024)), 8))
        assertEquals(8, NativeResampler.defaultThreads(CpuCores(intArrayOf(800, 800, 800, 800, 800, 800, 1024, 1024)), 8))
    }

    private fun tree(name: String, vararg cores: Pair<String?, String?>): File {
        val root = tmp.newFolder(name)
        for ((n, c) in cores.withIndex()) {
            val dir = File(root, "cpu$n").apply { mkdirs() }
            c.first?.let { File(dir, "cpu_capacity").writeText(it) }
            c.second?.let { File(File(dir, "cpufreq").apply { mkdirs() }, "cpuinfo_max_freq").writeText(it) }
        }
        // Not cores.
        File(root, "cpufreq").mkdirs()
        File(root, "cpuidle").mkdirs()
        File(root, "possible").writeText("0-${cores.size - 1}")
        return root
    }

    @Test
    fun readsCapacities() {
        val c = CpuCores.read(tree("a", "261\n" to "1785600", "261\n" to "1785600", "805\n" to "2496000", "1024\n" to "2995200"))
        assertEquals(0b1100L, c.big)
        assertEquals(0b0011L, c.little)
    }

    @Test
    fun withoutEveryCapacityReadsClockSpeeds() {
        // Core 3 has no capacity: clock speeds for all, where 1.79 of 3.0 GHz is little and 2.5 large.
        val c = CpuCores.read(tree("b", "261" to "1785600", "261" to "1785600", "805" to "2496000", null to "2995200"))
        assertEquals(0b1100L, c.big)
        assertEquals(0b0011L, c.little)
    }

    @Test
    fun anUnreadableTreeKnowsNothing() {
        assertFalse(CpuCores.read(File(tmp.root, "missing")).known)
    }
}
