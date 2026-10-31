package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CalibrationTest {
    private val tablet = CpuCores(intArrayOf(261, 261, 261, 261, 805, 805, 805, 1024))
    private val allBig = CpuCores(intArrayOf(800, 800, 800, 800, 800, 800, 1024, 1024))

    private fun pixels(n: Int, pinned: Boolean) = Candidate(Candidate.Kind.PIXELS, n, pinned)
    private fun jxl(n: Int, pinned: Boolean) = Candidate(Candidate.Kind.JXL, n, pinned)
    private val gpu = Candidate(Candidate.Kind.PIXELS, 1, pinned = false, gpu = true)

    @Test
    fun candidatesPinWhatFitsOnTheLargeCores() {
        val c = Calibration.candidates(tablet, 8, gpu = true, jxl = true)
        assertEquals(
            listOf(
                pixels(1, true), pixels(2, true), pixels(4, true), pixels(8, false), gpu,
                jxl(2, true), jxl(4, true), jxl(8, false)
            ),
            c
        )
        assertEquals(listOf("pixels/1/big", "pixels/gpu", "jxl/8/any"), listOf(c[0].key, c[4].key, c[7].key))
    }

    @Test
    fun allLargeCoresPinNothingAndDropDuplicates() {
        val c = Calibration.candidates(allBig, 8, gpu = false, jxl = false)
        assertEquals(listOf(pixels(1, false), pixels(2, false), pixels(8, false)), c)
        // One CPU: one setup.
        assertEquals(listOf(pixels(1, false)), Calibration.candidates(CpuCores(IntArray(0)), 1, gpu = false, jxl = false))
    }

    @Test
    fun theLeastCpuWinsAmongTheFast() {
        // 8 threads a little faster than 4 for far more CPU time; 2 too slow.
        val t = listOf(Timing(jxl(8, false), 100.0, 1000.0), Timing(jxl(4, true), 106.0, 600.0), Timing(jxl(2, true), 160.0, 500.0))
        assertEquals(jxl(4, true), Calibration.pick(t)!!.candidate)
        // Calibration's own page on the tablet: 8 any 74.6 ms / 368 CPU, 4 big 86.4 / 253.
        assertEquals(jxl(4, true), Calibration.pick(listOf(Timing(jxl(8, false), 74.6, 368.0), Timing(jxl(4, true), 86.4, 253.0)))!!.candidate)
        // Beyond the tolerance, speed wins.
        val slow = listOf(Timing(jxl(8, false), 100.0, 1000.0), Timing(jxl(4, true), 125.0, 600.0))
        assertEquals(jxl(8, false), Calibration.pick(slow)!!.candidate)
        assertNull(Calibration.pick(emptyList()))
    }

    @Test
    fun theGpuWinsOnlyWhenAsFast() {
        val before = Tuning.defaults(tablet, 8)
        // Tablet resampling, measured: native 4 big 34 ms / 101 CPU, GPU 95 / 38.
        val tablet = Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0), Timing(gpu, 95.0, 38.0)), before)
        assertEquals(PixelEngine.NATIVE, tablet.engine)
        assertEquals(4, tablet.pixelThreads)
        val other = Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0), Timing(gpu, 35.0, 20.0)), before)
        assertEquals(PixelEngine.GPU, other.engine)
        // JPEG XL untimed: kept as before.
        assertEquals(before.jxlThreads, other.jxlThreads)
        assertEquals(before.jxlPinned, other.jxlPinned)
    }

    @Test
    fun tuningKindsAreChosenApart() {
        val before = Tuning(PixelEngine.GPU, 1, false, 1, false)
        val t = Calibration.tuning(
            listOf(Timing(pixels(2, true), 50.0, 90.0), Timing(pixels(8, false), 40.0, 200.0), Timing(jxl(4, true), 170.0, 600.0)),
            before
        )
        assertEquals(Tuning(PixelEngine.NATIVE, 8, false, 4, true), t)
    }

    @Test
    fun defaultsAreOnePerLargeCorePinned() {
        assertEquals(Tuning(PixelEngine.NATIVE, 4, true, 4, true), Tuning.defaults(tablet, 8))
        assertEquals(Tuning(PixelEngine.NATIVE, 8, true, 8, true), Tuning.defaults(allBig, 8))
    }

    @Test
    fun tuningRoundTripsAndRejectsJunk() {
        val t = Tuning(PixelEngine.GPU, 6, false, 3, true)
        assertEquals(t, Tuning.of(org.json.JSONObject(t.toJson().toString())))
        assertNull(Tuning.of(null))
        assertNull(Tuning.of(org.json.JSONObject("{\"engine\":\"cuda\",\"pixelThreads\":1,\"pixelPinned\":true,\"jxlThreads\":1,\"jxlPinned\":true}")))
        assertNull(Tuning.of(org.json.JSONObject("{\"engine\":\"native\"}")))
        assertEquals(16, Tuning.of(org.json.JSONObject(t.copy(pixelThreads = 99).toJson().toString()))!!.pixelThreads)
    }

    @Test
    fun fingerprintChangesWithAppOrSystem() {
        val a = Calibration.fingerprint("12@100", "brand/device:16/BUILD1")
        assertTrue(a != Calibration.fingerprint("12@200", "brand/device:16/BUILD1"))
        assertTrue(a != Calibration.fingerprint("12@100", "brand/device:16/BUILD2"))
        assertEquals(a, Calibration.fingerprint("12@100", "brand/device:16/BUILD1"))
    }

    @Test
    fun medianOfOddAndEven() {
        assertEquals(2.0, Calibration.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, Calibration.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertTrue(Calibration.median(emptyList()).isNaN())
    }

    /** Times each candidate from [walls], runs in turn; [fair] and [still] script the device's state. */
    private class FakeProbe(
        val walls: Map<Candidate, List<Double>>,
        val fair: () -> Boolean = { true },
        val still: () -> Boolean = { true }
    ) : CalibrationProbe {
        val order = ArrayList<String>()
        private val runs = HashMap<Candidate, Int>()

        override fun time(candidate: Candidate): Pair<Double, Double>? {
            val list = walls[candidate] ?: return null
            val i = runs.merge(candidate, 1, Int::plus)!! - 1
            order += candidate.key
            val w = list[i % list.size]
            return w to w * 2
        }

        override fun awaitFair(): Boolean = fair()
        override fun stillFair(): Boolean = still()
    }

    @Test
    fun runInterleavesLeavesOutWarmupAndTakesMedians() {
        val a = pixels(1, true)
        val b = pixels(4, true)
        // First run of each is the warm-up: 1000 ms, left out.
        val probe = FakeProbe(mapOf(a to listOf(1000.0, 10.0, 30.0, 20.0), b to listOf(1000.0, 5.0, 6.0, 7.0)))
        val t = CalibrationRun(probe, listOf(a, b), rounds = 3, warmup = 1).run()!!
        assertEquals(listOf(a.key, b.key, b.key, a.key, a.key, b.key, b.key, a.key), probe.order)
        assertEquals(Timing(a, 20.0, 40.0), t[0])
        assertEquals(Timing(b, 6.0, 12.0), t[1])
    }

    @Test
    fun aSetupThatCannotRunIsLeftOut() {
        val a = pixels(1, true)
        val probe = FakeProbe(mapOf(a to listOf(10.0)))
        val t = CalibrationRun(probe, listOf(a, gpu), rounds = 2, warmup = 0).run()!!
        assertEquals(listOf(a), t.map { it.candidate })
        // The one that runs ran in both rounds.
        assertEquals(listOf(a.key, a.key), probe.order)
    }

    @Test
    fun disturbedRunsAreMadeAgainUpToALimit() {
        val a = pixels(1, true)
        var n = 0
        // Every other run is disturbed.
        val probe = FakeProbe(mapOf(a to listOf(10.0)), still = { n++ % 2 == 1 })
        val t = CalibrationRun(probe, listOf(a), rounds = 2, warmup = 0, retries = 5).run()
        assertNotNull(t)
        assertEquals(4, probe.order.size)
        val never = FakeProbe(mapOf(a to listOf(10.0)), still = { false })
        assertNull(CalibrationRun(never, listOf(a), rounds = 2, warmup = 0, retries = 3).run())
        assertEquals(4, never.order.size)
    }

    @Test
    fun anUnfairDeviceOrAStopEndsTheRun() {
        val a = pixels(1, true)
        assertNull(CalibrationRun(FakeProbe(mapOf(a to listOf(10.0)), fair = { false }), listOf(a)).run())
        var asked = 0
        assertNull(CalibrationRun(FakeProbe(mapOf(a to listOf(10.0))), listOf(a)).run { asked++ > 2 })
        assertFalse(asked < 3)
    }
}
