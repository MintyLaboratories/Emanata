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
    fun platformJpegIsTimedPinnedAndNotWhereThereAreLargeCores() {
        val c = Calibration.candidates(tablet, 8, gpu = false, jxl = false, jpeg = true)
        assertEquals(listOf("pixels/1/big", "pixels/2/big", "pixels/4/big", "pixels/8/any", "jpeg/big", "jpeg/any"), c.map { it.key })
        assertTrue(Calibration.candidates(allBig, 8, gpu = false, jxl = false, jpeg = true).none { it.kind == Candidate.Kind.JPEG })
    }

    @Test
    fun platformJpegIsLeftUnpinnedOnlyWhereThatIsClearlyFaster() {
        val big = Candidate(Candidate.Kind.JPEG, 1, pinned = true)
        val any = Candidate(Candidate.Kind.JPEG, 1, pinned = false)
        val before = Tuning.defaults(tablet, 8)
        // A multithreaded system decoder: 32 ms unpinned, 47 pinned, for twice the CPU.
        val spread = listOf(Timing(big, 47.0, 47.0, 2.0), Timing(any, 32.0, 110.0, 2.0))
        assertFalse(Calibration.tuning(spread, before).jpegPinned)
        // One thread: pinned, even where unpinned is faster warm.
        assertTrue(Calibration.jpegPinned(listOf(Timing(big, 44.0, 44.0, 1.0), Timing(any, 40.0, 40.0, 1.0)), before = false))
        assertTrue(Calibration.jpegPinned(listOf(Timing(big, 30.0, 30.0), Timing(any, 40.0, 40.0)), before = true))
        // Several threads, but not clearly faster: no change either way.
        assertTrue(Calibration.jpegPinned(listOf(Timing(big, 30.0, 30.0, 2.0), Timing(any, 29.0, 110.0, 2.0)), before = true))
        assertTrue(Calibration.jpegPinned(listOf(Timing(big, 50.0, 50.0, 10.0), Timing(any, 42.0, 150.0, 3.0)), before = true))
        assertFalse(Calibration.jpegPinned(listOf(Timing(big, 42.0, 42.0, 3.0), Timing(any, 50.0, 180.0, 10.0)), before = false))
        // Pinned clearly faster: pinned again.
        assertTrue(Calibration.jpegPinned(listOf(Timing(big, 40.0, 40.0, 1.0), Timing(any, 50.0, 180.0, 1.0)), before = false))
        // Untimed: as before.
        assertFalse(Calibration.jpegPinned(emptyList(), before = false))
        assertTrue(Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0)), before).jpegPinned)
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
        val t = listOf(Timing(jxl(8, false), 100.0, 1000.0), Timing(jxl(4, true), 110.0, 600.0), Timing(jxl(2, true), 160.0, 500.0))
        assertEquals(jxl(4, true), Calibration.pick(t)!!.candidate)
        // Calibration's own page on the tablet: 8 any 74.6 ms / 368 CPU, 4 big 86.4 / 253.
        assertEquals(jxl(4, true), Calibration.pick(listOf(Timing(jxl(8, false), 74.6, 368.0), Timing(jxl(4, true), 86.4, 253.0)))!!.candidate)
        // Beyond the tolerance, speed wins.
        val slow = listOf(Timing(jxl(8, false), 100.0, 1000.0), Timing(jxl(4, true), 125.0, 600.0))
        assertEquals(jxl(8, false), Calibration.pick(slow)!!.candidate)
        assertNull(Calibration.pick(emptyList()))
    }

    @Test
    fun theGpuWinsOnlyWhenClearlyFaster() {
        val before = Tuning.defaults(tablet, 8)
        // Tablet resampling, measured: native 4 big 34 ms / 101 CPU, GPU 95 / 38.
        val tablet = Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0), Timing(gpu, 95.0, 38.0)), before)
        assertEquals(PixelEngine.NATIVE, tablet.engine)
        assertEquals(4, tablet.pixelThreads)
        // As fast for less CPU time is not enough: its own power is not counted.
        val asFast = Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0), Timing(gpu, 33.0, 20.0)), before)
        assertEquals(PixelEngine.NATIVE, asFast.engine)
        val other = Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0), Timing(gpu, 25.0, 20.0)), before)
        assertEquals(PixelEngine.GPU, other.engine)
        // Not beyond the spread of the runs.
        val noisy = listOf(Timing(pixels(4, true), 34.0, 101.0, wallSpreadMs = 12.0), Timing(gpu, 25.0, 20.0))
        assertEquals(PixelEngine.NATIVE, Calibration.tuning(noisy, before).engine)
        // A GPU in effect stays only on the same terms.
        val onGpu = before.copy(engine = PixelEngine.GPU)
        assertEquals(PixelEngine.NATIVE, Calibration.tuning(listOf(Timing(pixels(4, true), 34.0, 101.0), Timing(gpu, 33.0, 20.0)), onGpu).engine)
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
        // No large cores to pin to: unpinned, as every setup calibration times there.
        assertEquals(Tuning(PixelEngine.NATIVE, 8, false, 8, false, false), Tuning.defaults(allBig, 8))
    }

    @Test
    fun withoutLargeCoresTheDefaultsAreAnIncumbent() {
        val before = Tuning.defaults(allBig, 8)
        // 2 threads within the tolerance of 8 for less CPU, but not by more than the noise: 8 stays.
        val t = listOf(Timing(pixels(2, false), 16.0, 70.0, 2.0, 10.0), Timing(pixels(8, false), 14.0, 77.0, 2.0, 10.0))
        assertEquals(8, Calibration.tuning(t, before).pixelThreads)
        assertFalse(Calibration.tuning(t, before).pixelPinned)
    }

    @Test
    fun theSetupInEffectStaysWithinNoise() {
        val t = listOf(Timing(jxl(4, true), 110.0, 600.0, 10.0, 80.0), Timing(jxl(8, false), 100.0, 560.0, 10.0, 80.0))
        assertEquals(jxl(4, true), Calibration.pick(t, incumbent = jxl(4, true))!!.candidate)
        assertEquals(jxl(8, false), Calibration.pick(t)!!.candidate)
        // Beyond the spread, the cheaper one wins.
        val clear = listOf(Timing(jxl(4, true), 110.0, 700.0, 10.0, 20.0), Timing(jxl(8, false), 100.0, 560.0, 10.0, 20.0))
        assertEquals(jxl(8, false), Calibration.pick(clear, incumbent = jxl(4, true))!!.candidate)
    }

    @Test
    fun wholeMillisecondCpuTimesThatSpreadZeroStillCountNoise() {
        // CPU in whole ms, every burst alike: spread 0. A 1-4 ms CPU gap on ~85 ms is noise.
        val t = listOf(Timing(pixels(2, true), 50.6, 85.0, 1.1, 0.0), Timing(pixels(4, true), 45.0, 82.0, 1.0, 0.0))
        assertEquals(pixels(2, true), Calibration.pick(t, incumbent = pixels(2, true))!!.candidate)
        assertEquals(pixels(4, true), Calibration.pick(t, incumbent = pixels(4, true))!!.candidate)
        // Tiny CPU times: at least 1 ms.
        val tiny = listOf(Timing(pixels(1, true), 10.0, 11.0), Timing(pixels(2, true), 9.0, 10.0))
        assertEquals(pixels(1, true), Calibration.pick(tiny, incumbent = pixels(1, true))!!.candidate)
        // Beyond 5 %: the cheaper one wins.
        val clear = listOf(Timing(pixels(2, true), 50.6, 85.0, 1.1, 0.0), Timing(pixels(4, true), 45.0, 78.0, 1.0, 0.0))
        assertEquals(pixels(4, true), Calibration.pick(clear, incumbent = pixels(2, true))!!.candidate)
        assertEquals(3.9, Calibration.cpuNoise(clear[0], clear[1]), 1e-9)
    }

    @Test
    fun withinTheSpreadOfTheFastestCountsAsFast() {
        // 25 % slower, but the fastest's runs spread over 30 ms.
        val t = listOf(Timing(jxl(8, false), 100.0, 1000.0, wallSpreadMs = 30.0), Timing(jxl(4, true), 125.0, 600.0))
        assertEquals(jxl(4, true), Calibration.pick(t)!!.candidate)
    }

    @Test
    fun pagesReadAheadDecodeOnAsManyThreadsAsPagesShown() {
        val chosen = Calibration.tuning(listOf(Timing(jxl(2, true), 160.0, 330.0), Timing(jxl(4, true), 100.0, 400.0)), Tuning.defaults(tablet, 8))
        assertEquals(4, chosen.jxlThreads)
        assertTrue(chosen.jxlPinned)
        val kept = listOf(NativeResampler.engine, NativeResampler.threads, NativeResampler.laterThreads, NativeResampler.pinned, JxlPages.threads, JxlPages.laterThreads, JxlPages.pinned)
        try {
            JxlPages.laterThreads = 1
            chosen.apply()
            assertEquals(4, JxlPages.laterThreads)
            chosen.copy(jpegPinned = false).apply()
            assertFalse(PlatformPages.jpegPinned)
        } finally {
            PlatformPages.jpegPinned = true
            NativeResampler.engine = kept[0] as PixelEngine
            NativeResampler.threads = kept[1] as Int
            NativeResampler.laterThreads = kept[2] as Int
            NativeResampler.pinned = kept[3] as Boolean
            JxlPages.threads = kept[4] as Int
            JxlPages.laterThreads = kept[5] as Int
            JxlPages.pinned = kept[6] as Boolean
        }
    }

    @Test
    fun tuningRoundTripsAndRejectsJunk() {
        val t = Tuning(PixelEngine.GPU, 6, false, 3, true, false)
        assertEquals(t, Tuning.of(org.json.JSONObject(t.toJson().toString())))
        // Kept before platform JPEG was calibrated: pinned.
        assertTrue(Tuning.of(org.json.JSONObject(t.toJson().apply { remove("jpegPinned") }.toString()))!!.jpegPinned)
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
    fun aSlowSetupsOwnOutlierDoesNotMakeItFast() {
        // 2 threads 23.4 ms with one 37 ms run, 8 threads 14.2 steady: 2 is 65 % slower, its own spread no excuse.
        val t = listOf(Timing(pixels(2, false), 23.4, 45.0, wallSpreadMs = 14.0), Timing(pixels(8, false), 14.2, 77.0, wallSpreadMs = 1.0))
        assertEquals(pixels(8, false), Calibration.pick(t)!!.candidate)
    }

    @Test
    fun spreadIgnoresAnOutlier() {
        // Max-min would say 14 ms.
        assertEquals(0.3, Calibration.spread(listOf(23.2, 23.3, 23.4, 23.5, 37.2)), 1e-9)
        // Steady runs: three median absolute deviations.
        assertEquals(6.0, Calibration.spread(listOf(98.0, 100.0, 102.0, 104.0, 96.0)), 1e-9)
        assertEquals(0.0, Calibration.spread(emptyList()), 0.0)
    }

    @Test
    fun dueForANewBuildOrACalibrationUnderBatterySaver() {
        fun kept(f: String, saver: Boolean? = null) = org.json.JSONObject().put("fingerprint", f).apply { saver?.let { put("powerSave", it) } }
        assertFalse(Calibration.due(null, null))
        assertTrue(Calibration.due(null, "a"))
        assertTrue(Calibration.due(kept("b"), "a"))
        assertFalse(Calibration.due(kept("a"), "a"))
        assertFalse(Calibration.due(kept("a", saver = false), "a"))
        assertTrue(Calibration.due(kept("a", saver = true), "a"))
    }

    @Test
    fun aForcedRunGoesOnUnderBatterySaverAndOffTheCharger() {
        assertEquals("battery saver on", Calibration.waitReason(busy = false, thermalStatus = 0, powerSave = true, forced = false))
        assertNull(Calibration.waitReason(busy = false, thermalStatus = 0, powerSave = true, forced = true))
        assertEquals("too warm (thermal status 2)", Calibration.waitReason(busy = false, thermalStatus = 2, powerSave = false, forced = true))
        assertEquals("app busy", Calibration.waitReason(busy = true, thermalStatus = 0, powerSave = true, forced = true))
        assertNull(Calibration.waitReason(busy = false, thermalStatus = 1, powerSave = false, forced = false))
        // After a wait for heat, light throttling is waited out too.
        assertEquals("too warm (thermal status 1)", Calibration.waitReason(busy = false, thermalStatus = 1, powerSave = false, forced = false, cooling = true))
        assertNull(Calibration.waitReason(busy = false, thermalStatus = 0, powerSave = false, forced = false, cooling = true))
        var asked = false
        assertNull(Calibration.stopReason(held = false, reading = false, inFront = true, forced = true) { asked = true; false })
        assertFalse(asked)
        assertEquals("not charging", Calibration.stopReason(held = false, reading = false, inFront = true, forced = false) { false })
        assertEquals("reading", Calibration.stopReason(held = false, reading = true, inFront = true, forced = true) { true })
        assertEquals("app not in front", Calibration.stopReason(held = false, reading = false, inFront = false, forced = true) { true })
        assertEquals("held off by a check", Calibration.stopReason(held = true, reading = false, inFront = true, forced = true) { true })
    }

    @Test
    fun heatWhileTimingEndsTheRunAndWaitsAreBudgetedInAll() {
        val warm = "too warm (thermal status 2)"
        // Before timing, heat is waited out, within the budget.
        assertNull(Calibration.giveUp(warm, hot = true, timing = false, waitedMs = 60_000))
        assertEquals("too warm (thermal status 2) while timing", Calibration.giveUp(warm, hot = true, timing = true, waitedMs = 0))
        // Other waits, before or during timing, share one budget for the whole run.
        assertNull(Calibration.giveUp("app busy", hot = false, timing = true, waitedMs = Calibration.GIVE_UP_MS))
        assertEquals("waited over 120 s in all: app busy", Calibration.giveUp("app busy", hot = false, timing = true, waitedMs = Calibration.GIVE_UP_MS + 1))
    }

    @Test
    fun theTargetIsThePageFitToTheScreenHeldUpright() {
        assertEquals(1848 to 2772, Calibration.target(1848, 2960, 2000, 3000))
        assertEquals(1848 to 2772, Calibration.target(2960, 1848, 2000, 3000))
        assertEquals(1200 to 1800, Calibration.target(1200, 2000, 2000, 3000))
        assertEquals(1440 to 2160, Calibration.target(1440, 3120, 2000, 3000))
        // Never enlarged.
        assertEquals(2000 to 3000, Calibration.target(2560, 4000, 2000, 3000))
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
        val still: () -> Boolean = { true },
        override val why: String? = null
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
        // One run per sample, unwarmed; the first round is the warm-up: 1000 ms, left out.
        val probe = FakeProbe(mapOf(a to listOf(1000.0, 10.0, 30.0, 20.0), b to listOf(1000.0, 5.0, 6.0, 7.0)))
        val t = CalibrationRun(probe, listOf(a, b), rounds = 3, warmup = 1, burst = 1, warm = false).run()!!
        assertEquals(listOf(a.key, b.key, b.key, a.key, a.key, b.key, b.key, a.key), probe.order)
        assertEquals(Timing(a, 20.0, 40.0, 30.0, 60.0), t[0])
        assertEquals(Timing(b, 6.0, 12.0, 3.0, 6.0), t[1])
    }

    @Test
    fun eachSampleIsTheMedianOfAWarmedBurst() {
        val a = pixels(1, true)
        // Per sample: a cold run (left out), then 3 back to back, one an outlier.
        val probe = FakeProbe(mapOf(a to listOf(500.0, 10.0, 90.0, 12.0, 500.0, 14.0, 11.0, 70.0)))
        val t = CalibrationRun(probe, listOf(a), rounds = 2).run()!!
        assertEquals(8, probe.order.size)
        // Samples 12 and 14.
        assertEquals(13.0, t[0].wallMs, 0.0)
    }

    @Test
    fun aSetupThatCannotRunIsLeftOut() {
        val a = pixels(1, true)
        val probe = FakeProbe(mapOf(a to listOf(10.0)))
        val t = CalibrationRun(probe, listOf(a, gpu), rounds = 2, burst = 1, warm = false).run()!!
        assertEquals(listOf(a), t.map { it.candidate })
        // The one that runs ran in both rounds.
        assertEquals(listOf(a.key, a.key), probe.order)
    }

    @Test
    fun disturbedSamplesAreMadeAgainUpToALimit() {
        val a = pixels(1, true)
        var n = 0
        // Every other sample is disturbed.
        val probe = FakeProbe(mapOf(a to listOf(10.0)), still = { n++ % 2 == 1 })
        val t = CalibrationRun(probe, listOf(a), rounds = 2, retries = 5).run()
        assertNotNull(t)
        // 4 samples of a warm run and 3 timed.
        assertEquals(16, probe.order.size)
        val never = FakeProbe(mapOf(a to listOf(10.0)), still = { false })
        val run = CalibrationRun(never, listOf(a), rounds = 2, burst = 1, warm = false, retries = 3)
        assertNull(run.run())
        assertEquals(4, never.order.size)
        assertEquals("disturbed 4 times", run.why)
    }

    /** Times every run 10 ms; [still] and [saver] script the device's state. */
    private class SaverProbe(val still: () -> Boolean, val saver: () -> Boolean) : CalibrationProbe {
        override fun time(candidate: Candidate) = 10.0 to 20.0
        override fun awaitFair() = true
        override fun stillFair() = still()
        override val powerSave: Boolean get() = saver()
    }

    @Test
    fun batterySaverIsRecordedOnlyForSamplesKeptUnderIt() {
        val a = pixels(1, true)
        // Off for every sample (on only while an automatic run waits): not recorded.
        val off = CalibrationRun(SaverProbe({ true }, { false }), listOf(a), rounds = 2)
        assertNotNull(off.run())
        assertFalse(off.sawPowerSave)
        // On for a sample a forced run keeps: recorded.
        var on = false
        val kept = CalibrationRun(SaverProbe({ true.also { on = !on } }, { on }), listOf(a), rounds = 2)
        assertNotNull(kept.run())
        assertTrue(kept.sawPowerSave)
        // On only for a disturbed sample, made again with it off: not recorded.
        var n = 0
        val redone = CalibrationRun(SaverProbe({ n++ > 0 }, { n == 0 }), listOf(a), rounds = 1)
        assertNotNull(redone.run())
        assertFalse(redone.sawPowerSave)
    }

    @Test
    fun anUnfairDeviceOrAStopEndsTheRunSayingWhy() {
        val a = pixels(1, true)
        val unfair = CalibrationRun(FakeProbe(mapOf(a to listOf(10.0)), fair = { false }, why = "battery saver on"), listOf(a))
        assertNull(unfair.run())
        assertEquals("battery saver on", unfair.why)
        var asked = 0
        val stopped = CalibrationRun(FakeProbe(mapOf(a to listOf(10.0))), listOf(a))
        assertNull(stopped.run { asked++ > 2 })
        assertFalse(asked < 3)
        assertEquals("stopped", stopped.why)
    }

    /**
     * A device whose cores clock down while calibration checks it is idle: the first run
     * after each check is slower, threads spread wider the more (one data point: 8 threads
     * 1.5x, 2 on the large cores 1.2x), and runs jitter by [jitter] either way with an
     * outlier 60 % slower one run in [outlierEvery] (0: none).
     */
    private class ClockingProbe(val steady: Map<Candidate, Double>, val cold: Map<Candidate, Double>, val cpuPerWall: Map<Candidate, Double>, seed: Int, val jitter: Double, val outlierEvery: Int) : CalibrationProbe {
        private val random = java.util.Random(seed.toLong())
        private var idle = false

        override fun awaitFair(): Boolean = true.also { idle = true }
        override fun stillFair(): Boolean = true
        override fun time(candidate: Candidate): Pair<Double, Double> {
            val factor = if (idle) cold.getValue(candidate) else 1.0
            idle = false
            val noise = 1 + (random.nextDouble() * 2 - 1) * jitter + if (outlierEvery > 0 && random.nextInt(outlierEvery) == 0) 0.6 else 0.0
            val wall = steady.getValue(candidate) * factor * noise
            return wall to wall * cpuPerWall.getValue(candidate)
        }
    }

    @Test
    fun warmedBurstsFindASteadyWinThatColdStartsHide() {
        val two = jxl(2, true)
        val eight = jxl(8, false)
        // Steady: 8 threads on any core 142 ms / 828 CPU, 2 on the large cores 187 / 361: 8 is 24 % faster.
        val steady = mapOf(two to 187.0, eight to 142.0)
        val cold = mapOf(two to 1.2, eight to 1.5)
        val ratio = mapOf(two to 361.0 / 187, eight to 828.0 / 142)
        val before = Tuning(PixelEngine.NATIVE, 2, true, 2, true)
        for (seed in 0 until 50) {
            val probe = ClockingProbe(steady, cold, ratio, seed, jitter = 0.08, outlierEvery = 10)
            val t = CalibrationRun(probe, listOf(two, eight)).run()!!
            assertEquals("seed $seed", Tuning(PixelEngine.NATIVE, 2, true, 8, false), Calibration.tuning(t, before))
        }
        // Timed straight after each idle check, as before: 8 threads look only 5 % faster, and 2 win on CPU.
        val coldOnly = CalibrationRun(ClockingProbe(steady, cold, ratio, 0, jitter = 0.0, outlierEvery = 0), listOf(two, eight), warmup = 1, burst = 1, warm = false).run()!!
        assertEquals(2, Calibration.tuning(coldOnly, before).jxlThreads)
    }
}
