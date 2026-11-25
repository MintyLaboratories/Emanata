package minty.emanata.debug

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [DeviceClocks] on fake procfs and sysfs trees: normal, missing and unreadable files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceClocksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File get() = tmp.root

    private fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    /** A folder where a file is expected: opening it to read fails, as a file the system refuses. */
    private fun unreadable(path: String) = File(root, path).mkdirs()

    private fun thread(tid: Int, name: String, cpuset: String = "/top-app", cpus: String = "0-7") {
        write("proc/self/task/$tid/comm", "$name\n")
        write("proc/self/task/$tid/cpuset", "$cpuset\n")
        write("proc/self/task/$tid/status", "Name:\t$name\nCpus_allowed:\tff\nCpus_allowed_list:\t$cpus\nMems_allowed:\t1\n")
    }

    private fun device() {
        write("sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "818000000\n")
        write("sys/class/kgsl/kgsl-3d0/gpuclk", "1\n")
        write("sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "37 %\n")
        write("sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq", "1785600\n")
        write("sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq", "2419200\n")
        write("sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq", "2995200\n")
    }

    @Test
    fun readsEveryFieldWhereShown() {
        thread(41, "ai-ncnn", "/foreground", "4-7")
        device()
        val s = DeviceClocks.read(root, 41)
        assertEquals("ai-ncnn", s.thread)
        assertEquals("/foreground", s.cpuset)
        assertEquals("4-7", s.cpus)
        assertEquals(818, s.gpuMhz)
        assertEquals(37, s.gpuBusyPct)
        assertEquals(mapOf("policy0" to 1785, "policy4" to 2419, "policy7" to 2995), s.cpuMhz)
    }

    @Test
    fun missingFilesAreNull() {
        val s = DeviceClocks.read(root, 41)
        assertNull(s.thread)
        assertNull(s.cpuset)
        assertNull(s.cpus)
        assertNull(s.gpuMhz)
        assertNull(s.gpuBusyPct)
        assertTrue(s.cpuMhz.isEmpty())
    }

    @Test
    fun unreadableFilesAreNull() {
        unreadable("proc/self/task/41/cpuset")
        unreadable("proc/self/task/41/status")
        unreadable("sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
        unreadable("sys/class/kgsl/kgsl-3d0/gpuclk")
        unreadable("sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        unreadable("sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq")
        write("sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq", "2419200\n")
        val s = DeviceClocks.read(root, 41)
        assertNull(s.cpuset)
        assertNull(s.cpus)
        assertNull(s.gpuMhz)
        assertNull(s.gpuBusyPct)
        // One cluster refused, another shown: the refused one is null, not left out.
        assertEquals(mapOf("policy0" to null, "policy4" to 2419), s.cpuMhz)
    }

    @Test
    fun unreadableCpufreqFolderGivesNoClusters() {
        write("sys/devices/system/cpu/cpufreq", "not a folder")
        assertTrue(DeviceClocks.cpuMhz(root).isEmpty())
    }

    @Test
    fun textThatIsNoNumberIsNull() {
        write("sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "unknown\n")
        write("sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "\n")
        write("sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq", "<unknown>\n")
        assertNull(DeviceClocks.gpuMhz(root))
        assertNull(DeviceClocks.gpuBusy(root))
        assertEquals(mapOf("policy0" to null), DeviceClocks.cpuMhz(root))
    }

    @Test
    fun gpuClockFallsBackToGpuclk() {
        write("sys/class/kgsl/kgsl-3d0/gpuclk", "585000000\n")
        assertEquals(585, DeviceClocks.gpuMhz(root))
    }

    @Test
    fun busyPastAHundredIsNull() {
        write("sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "250 %\n")
        assertNull(DeviceClocks.gpuBusy(root))
    }

    @Test
    fun clustersComeInPolicyOrder() {
        write("sys/devices/system/cpu/cpufreq/policy10/scaling_cur_freq", "1000\n")
        write("sys/devices/system/cpu/cpufreq/policy2/scaling_cur_freq", "2000\n")
        write("sys/devices/system/cpu/cpufreq/policyX/scaling_cur_freq", "3000\n")
        assertEquals(listOf("policy2", "policy10"), DeviceClocks.cpuMhz(root).keys.toList())
    }

    @Test
    fun threadsAreFoundByName() {
        thread(7, "main")
        thread(12, "ai-ncnn")
        thread(30, "ai-check")
        assertEquals(12, DeviceClocks.threadNamed(root, "ai-ncnn"))
        assertEquals(12, DeviceClocks.threadNamed(root, "ai-ncnn", hint = 12))
        // A hint that names another thread now is looked past.
        assertEquals(12, DeviceClocks.threadNamed(root, "ai-ncnn", hint = 30))
        assertNull(DeviceClocks.threadNamed(root, "gone"))
        assertNull(DeviceClocks.threadNamed(File(root, "nowhere"), "ai-ncnn"))
    }

    @Test
    fun snapshotJsonHoldsNullsForTheUnread() {
        val j = DeviceClocks.Snapshot("ai-ncnn", "/top-app", null, null, 12, mapOf("policy0" to 1785, "policy4" to null)).toJson()
        assertEquals("ai-ncnn", j.getString("thread"))
        assertEquals("/top-app", j.getString("cpuset"))
        assertTrue(j.isNull("cpus"))
        assertTrue(j.isNull("gpuMhz"))
        assertEquals(12, j.getInt("gpuBusy"))
        val cpu = j.getJSONObject("cpuMhz")
        assertEquals(1785, cpu.getInt("policy0"))
        assertTrue(cpu.has("policy4") && cpu.isNull("policy4"))
        assertEquals(listOf("thread", "cpuset", "cpus", "gpuMhz", "gpuBusy", "cpuMhz"), j.keys().asSequence().toList())
        // Round trip through the line's text, as ai.jsonl holds it.
        val back = JSONObject(j.toString())
        assertTrue(back.has("gpuMhz") && back.isNull("gpuMhz"))
    }

    @Test
    fun snapshotJsonWithoutClustersIsNull() {
        val j = DeviceClocks.Snapshot(null, null, null, null, null, emptyMap()).toJson()
        for (k in listOf("thread", "cpuset", "cpus", "gpuMhz", "gpuBusy", "cpuMhz")) assertTrue(k, j.has(k) && j.isNull(k))
    }

    @Test
    fun summaryGivesMedianLeastAndMostOfWhatWasRead() {
        fun s(cpuset: String?, gpu: Int?, busy: Int?, big: Int?) = DeviceClocks.Snapshot("ai-ncnn", cpuset, "0-7", gpu, busy, mapOf("policy7" to big))
        val j = DeviceClocks.summary(listOf(s("/top-app", 818, 90, 2995), s("/top-app", 285, null, null), s("/foreground", 680, 40, 1200)))
        assertEquals(3, j.getInt("n"))
        assertEquals(listOf("/top-app", "/foreground"), (0 until j.getJSONArray("cpusets").length()).map { j.getJSONArray("cpusets").getString(it) })
        val gpu = j.getJSONObject("gpuMhz")
        assertEquals(680, gpu.getInt("median"))
        assertEquals(285, gpu.getInt("min"))
        assertEquals(818, gpu.getInt("max"))
        // Two of three read: nearest rank takes the lower middle.
        assertEquals(40, j.getJSONObject("gpuBusy").getInt("median"))
        val big = j.getJSONObject("cpuMhz").getJSONObject("policy7")
        assertEquals(1200, big.getInt("median"))
        assertEquals(2995, big.getInt("max"))
    }

    @Test
    fun summaryOfNothingReadIsNull() {
        val j = DeviceClocks.summary(listOf(DeviceClocks.Snapshot(null, null, null, null, null, emptyMap())))
        assertEquals(1, j.getInt("n"))
        assertEquals(0, j.getJSONArray("cpusets").length())
        assertTrue(j.isNull("gpuMhz"))
        assertTrue(j.isNull("gpuBusy"))
        assertTrue(j.isNull("cpuMhz"))
        assertEquals(0, DeviceClocks.summary(emptyList()).getInt("n"))
    }
}
