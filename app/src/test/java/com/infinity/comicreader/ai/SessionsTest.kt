package com.infinity.comicreader.ai

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun gpu(budgetMb: Int = 2048, fp16: Boolean = true, maxBuffer: Long = Long.MAX_VALUE) =
        GpuInfo("Test GPU", budgetMb, if (fp16) Fp16Modes.ALL else Fp16Modes.NONE, maxBuffer)

    @Test
    fun opensOnTheGpuAfterMeasuringOnTheCpu() {
        val runtime = FakeRuntime()
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertTrue(s.fp16)
        assertEquals(listOf(false, true), runtime.asked)
        assertEquals("the CPU measures in fp32", listOf(false, true), runtime.askedFp16)
        assertEquals(Geometry(2, 0, 3 + ModelProbe.SAFETY), s.geometry)
        assertEquals(TileSizing.DEFAULT_MAX, s.size)
        assertEquals(1, (s.model as FakeModel).trims.get())
        assertTrue("the CPU model was left open", (runtime.opened[0] as FakeModel).closed)
    }

    @Test
    fun opensOnTheCpuWithoutAGpu() {
        val runtime = FakeRuntime(gpu = null)
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertFalse(s.fp16)
        assertEquals(listOf(false), runtime.asked)
    }

    @Test
    fun opensOnTheCpuWhenAskedTo() {
        val runtime = FakeRuntime()
        assertFalse(Sessions.open(runtime, fakeFiles(tmp.root), threads = 4, gpu = false)!!.model.onGpu)
        assertEquals(listOf(false), runtime.asked)
    }

    @Test
    fun aModelTheGpuCannotOpenStaysOnTheCpu() {
        val runtime = FakeRuntime(gpuOpens = false)
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertEquals(listOf(false, true), runtime.asked)
        assertSame("the measured CPU model is kept", runtime.opened.single(), s.model)
    }

    @Test
    fun aModelThatFailsOnTheGpuStaysOnTheCpu() {
        val runtime = FakeRuntime(make = { gpu, _ -> FakeModel(onGpu = gpu, failAt = if (gpu) 0 else -1) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertEquals("fp16, then fp32", listOf(false, true, false), runtime.askedFp16)
        assertTrue("a GPU model was left open", runtime.opened.drop(1).all { (it as FakeModel).closed })
        assertFalse((runtime.opened[0] as FakeModel).closed)
    }

    @Test
    fun aModelThatRunsNowhereOpensNoSession() {
        val runtime = FakeRuntime(make = { gpu, _ -> FakeModel(onGpu = gpu, failAt = 0) })
        assertNull(Sessions.open(runtime, fakeFiles(tmp.root), threads = 4))
        assertTrue(runtime.opened.all { (it as FakeModel).closed })
        assertNull(Sessions.open(FakeRuntime(available = false), fakeFiles(tmp.root), threads = 4))
    }

    @Test
    fun fp16RoundingThatAlignedTilesEvenOutStaysInFp16() {
        val runtime = FakeRuntime(make = { gpu, fp16 -> FakeModel(onGpu = gpu, phaseNoise = gpu && fp16) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertTrue(s.fp16)
    }

    @Test
    fun fp16RoundingNearTheTileEdgeGetsAWiderMargin() {
        val runtime = FakeRuntime(make = { gpu, fp16 -> FakeModel(onGpu = gpu, borderNoise = if (gpu && fp16) 10 else 0) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.fp16)
        // The measured margin, 5, widened once.
        assertEquals(5 + TilePlan.ALIGN, s.geometry.margin)
        assertEquals(listOf(false, true), runtime.askedFp16)
    }

    @Test
    fun fp16RoundingThatSeamsShowRunsInFp32() {
        val runtime = FakeRuntime(make = { gpu, fp16 -> FakeModel(onGpu = gpu, edgeNoise = gpu && fp16) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertFalse(s.fp16)
        assertEquals(listOf(false, true, false), runtime.askedFp16)
        assertEquals(TileSizing.DEFAULT_MAX, s.size)
        assertTrue((runtime.opened[1] as FakeModel).closed)
    }

    @Test
    fun aLargeTileGoneWrongGetsTheNextSmallerSize() {
        val runtime = FakeRuntime(make = { gpu, _ -> FakeModel(onGpu = gpu, breaksAbove = if (gpu) 150 else Int.MAX_VALUE) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertTrue(s.fp16)
        assertEquals(128, s.size)
    }

    @Test
    fun aGpuWithPackedFp16OnlyTriesFp16() {
        val packed = Fp16Modes(storage = false, packed = true, arithmetic = false)
        val runtime = FakeRuntime(gpu = GpuInfo("Test GPU", 2048, packed, Long.MAX_VALUE))
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertTrue(s.fp16)
        assertEquals(listOf(false, true), runtime.askedFp16)
    }

    @Test
    fun aGpuWithFp16ArithmeticOnlyRunsInFp32() {
        val arithmetic = Fp16Modes(storage = false, packed = false, arithmetic = true)
        val runtime = FakeRuntime(gpu = GpuInfo("Test GPU", 2048, arithmetic, Long.MAX_VALUE))
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertFalse(s.fp16)
        assertEquals(listOf(false, false), runtime.askedFp16)
    }

    @Test
    fun aGpuComputingNonsenseLeavesItToTheCpu() {
        val runtime = FakeRuntime(make = { gpu, _ -> FakeModel(onGpu = gpu, wrong = gpu) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertEquals(listOf(false, true, false), runtime.askedFp16)
    }

    @Test
    fun aModelPoolingTheWholeTileIsNotOpened() {
        val runtime = FakeRuntime()
        val files = fakeFiles(tmp.root, extra = "Pooling gap 1 1 a b 0=1 4=1\n")
        assertNull(Sessions.open(runtime, files, threads = 4))
        assertTrue(runtime.asked.isEmpty())
    }

    @Test
    fun measuringCanBeCanceled() {
        var runs = 0
        val runtime = FakeRuntime(make = { gpu, _ -> FakeModel(onGpu = gpu, onRun = { runs++ }) })
        assertThrows(CancellationException::class.java) { Sessions.open(runtime, fakeFiles(tmp.root), threads = 4, canceled = { runs >= 3 }) }
        assertEquals(3, runs)
        assertTrue(runtime.opened.all { (it as FakeModel).closed })
    }

    @Test
    fun anOpenWaitingForAPageCanBeCanceled() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread { TileEngine.exclusive({ false }) { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val runtime = FakeRuntime()
        assertThrows(CancellationException::class.java) { Sessions.open(runtime, fakeFiles(tmp.root), threads = 4, canceled = { true }) }
        release.countDown()
        holder.join()
        assertTrue(runtime.asked.isEmpty())
    }

    @Test
    fun aSmallGpuBudgetGetsSmallerTiles() {
        // 32 channels at 2x: 1.5 KB per input pixel; a quarter of 64 MB holds 96 x 96, not 128 x 128.
        val runtime = FakeRuntime(gpu = gpu(budgetMb = 64, fp16 = false))
        assertEquals(96, Sessions.open(runtime, fakeFiles(tmp.root, channels = 32), threads = 4)!!.size)
    }

    @Test
    fun aGpuBufferLimitGetsSmallerTiles() {
        // 32 channels of a 192 tile at 2x: 9 MiB in fp16, 18 MiB in fp32; of a 128 tile, 4 MiB in fp16, 8 MiB in fp32.
        val s = Sessions.open(FakeRuntime(gpu = gpu(maxBuffer = (8L shl 20) - 1)), fakeFiles(tmp.root, channels = 32), threads = 4)!!
        assertTrue(s.fp16)
        assertEquals(128, s.size)
        val fp32 = Sessions.open(FakeRuntime(gpu = gpu(fp16 = false, maxBuffer = (8L shl 20) - 1)), fakeFiles(tmp.root, channels = 32), threads = 4)!!
        assertFalse(fp32.fp16)
        assertEquals(96, fp32.size)
    }

    @Test
    fun aGpuFittingNoTileLeavesItToTheCpu() {
        val runtime = FakeRuntime(gpu = gpu(budgetMb = 1, fp16 = false))
        val s = Sessions.open(runtime, fakeFiles(tmp.root, channels = 1024), threads = 4)
        // The GPU fits no tile; the CPU's budget fits 64 x 64 at most for 1024 channels.
        assertNotNull(s)
        assertFalse(s!!.model.onGpu)
        assertEquals(64, s.size)
        assertEquals(listOf(false, true), runtime.asked)
    }

    @Test
    fun widestReadsConvolutionOutputs() {
        val param = """
            7767517
            5 5
            Input            in0      0 1 in0
            Convolution      conv_1   1 1 in0 1 0=48 1=3 4=1 5=1 6=1296
            PReLU            prelu_1  1 1 1 2 0=48
            Convolution      conv_2   1 1 2 3 0=12 1=3 4=1 5=1 6=5184
            PixelShuffle     ps       1 1 3 out0 0=2
        """.trimIndent()
        assertEquals(48, ModelParam.widest(param))
        assertEquals(ModelParam.DEFAULT_WIDEST, ModelParam.widest("7767517\n1 1\nInput in0 0 1 in0\n"))
        assertEquals(64, ModelParam.widest("Deconvolution d 1 1 a b 1=4 0=64 5=1"))
        assertEquals(ModelParam.DEFAULT_WIDEST, ModelParam.widest(""))
    }

    @Test
    fun wholeTileLayersAreFound() {
        val param = """
            7767517
            9 9
            Input            in0      0 1 in0
            Pooling          pool     1 1 in0 a 0=1 1=2 2=2
            Pooling          gap      1 1 a b 0=1 4=1
            Pooling          adaptive 1 1 b c 0=1 7=1 8=1 18=1
            Reduction        mean     1 1 c d 0=3 1=1
            InnerProduct     fc       1 1 d e 0=8 1=1 2=64
            InstanceNorm     norm     1 1 e f 0=8
            Normalize        local    1 1 f g 0=0 1=1
            Normalize        spatial  1 1 g out0 0=1 1=1
        """.trimIndent()
        assertEquals(listOf("gap", "adaptive", "mean", "fc", "norm", "spatial"), ModelParam.wholeTileLayers(param))
        assertTrue(ModelParam.wholeTileLayers("7767517\n2 2\nInput in0 0 1 in0\nConvolution c 1 1 in0 out0 0=8 1=3\n").isEmpty())
    }

    @Test
    fun candidatesStartAtTheCapAndStepDown() {
        val g = Geometry(2, 0, 8)
        val per = TileSizing.bytesPerPixel(64, 2)
        assertEquals(64L * 4 * 3 * 4, per)
        assertEquals(TileSizing.DEFAULT_MAX, TileSizing.pick(Long.MAX_VALUE, 64, g))
        assertEquals(listOf(192, 128, 96, 64), TileSizing.candidates(Long.MAX_VALUE, 64, g))
        assertEquals(512, TileSizing.pick(Long.MAX_VALUE, 64, g, max = 1000))
        assertEquals(192, TileSizing.pick(192L * 192 * per, 64, g, max = 512))
        assertEquals(128, TileSizing.pick(192L * 192 * per - 1, 64, g, max = 512))
        assertNull(TileSizing.pick(64L * 64 * per - 1, 64, g))
        // A margin that leaves 64 x 64 tiles no core skips them.
        assertNull(TileSizing.pick(64L * 64 * per, 64, Geometry(2, 0, 30)))
    }

    @Test
    fun theLargestFeatureMapStaysWithinOneBuffer() {
        val g = Geometry(4, 0, 8)
        // 64 channels at 4x in fp32: 192 x 4 = 768 px, 151 MB; 128 x 4 = 512 px, 67 MB.
        assertEquals(64L * 768 * 768 * 4, TileSizing.largestMap(192, 64, 4, 4))
        assertEquals(128, TileSizing.pick(Long.MAX_VALUE, 64, g, maxBufferBytes = 128L shl 20))
        assertEquals(192, TileSizing.pick(Long.MAX_VALUE, 64, g, maxBufferBytes = 128L shl 20, elementBytes = 2))
    }

    @Test
    fun aWideMarginTakesALargerTileThanTheCap() {
        // Margin 60: a 192 tile keeps 14% of its input, 384 keeps 47%, 512 keeps 59%.
        val g = Geometry(2, 0, 60)
        assertEquals(0.586, TileSizing.kept(512, g), 0.001)
        assertEquals(listOf(512, 384, 256, 192), TileSizing.candidates(Long.MAX_VALUE, 64, g))
        // Memory for 384 at most: the largest that fits.
        assertEquals(384, TileSizing.pick(384L * 384 * TileSizing.bytesPerPixel(64, 2), 64, g))
        // A narrow margin keeps the cap.
        assertEquals(192, TileSizing.pick(Long.MAX_VALUE, 64, Geometry(2, 0, 22)))
    }

    @Test
    fun gpuInfoParsesNativeDescriptions() {
        assertEquals(GpuInfo("Some GPU 1", 2048, Fp16Modes.ALL, 134217728), GpuInfo.parse("Some GPU 1\t2048\t7\t134217728"))
        assertEquals(GpuInfo("x", 0, Fp16Modes.NONE, 1), GpuInfo.parse("x\t0\t0\t1"))
        assertEquals(Fp16Modes(storage = false, packed = true, arithmetic = false), GpuInfo.parse("x\t0\t2\t1")!!.fp16Modes)
        assertNull(GpuInfo.parse(null))
        assertNull(GpuInfo.parse("x\tmany\t1\t1"))
        assertNull(GpuInfo.parse("x\t1\t1"))
        assertNull(GpuInfo.parse("x\t1\t1\t0"))
        assertNull(GpuInfo.parse("x\t1\t8\t1"))
        assertNull(GpuInfo.parse("x\t1\t-1\t1"))
    }

    @Test
    fun fp16RunsWhereTheGpuStoresOrPacksIt() {
        fun modes(storage: Boolean, packed: Boolean, arithmetic: Boolean) = Fp16Modes(storage, packed, arithmetic)
        assertTrue(modes(storage = true, packed = false, arithmetic = false).usable)
        assertTrue("packed alone", modes(storage = false, packed = true, arithmetic = false).usable)
        assertTrue(modes(storage = true, packed = false, arithmetic = true).usable)
        assertFalse("arithmetic alone has nothing to compute on", modes(storage = false, packed = false, arithmetic = true).usable)
        assertFalse(Fp16Modes.NONE.usable)
        assertTrue(GpuInfo("x", 0, modes(storage = false, packed = true, arithmetic = false), 1).fp16)
        assertFalse(GpuInfo("x", 0, modes(storage = false, packed = false, arithmetic = true), 1).fp16)
    }

    @Test
    fun fp16TurnsOnOnlyTheModesTheGpuSupports() {
        assertEquals(Fp16Modes.PACKED, Fp16Modes(storage = false, packed = true, arithmetic = false).bits)
        assertEquals(Fp16Modes.STORAGE or Fp16Modes.ARITHMETIC, Fp16Modes(storage = true, packed = false, arithmetic = true).bits)
        assertEquals(Fp16Modes.PACKED or Fp16Modes.ARITHMETIC, Fp16Modes(storage = false, packed = true, arithmetic = true).bits)
        assertEquals(7, Fp16Modes.ALL.bits)
        assertEquals("arithmetic alone turns nothing on", 0, Fp16Modes(storage = false, packed = false, arithmetic = true).bits)
        for (bits in 1..7) if (bits != Fp16Modes.ARITHMETIC) assertEquals(bits, Fp16Modes.of(bits)!!.bits)
        val packedOnly = GpuInfo("x", 0, Fp16Modes(storage = false, packed = true, arithmetic = false), 1)
        assertEquals(Fp16Modes.PACKED, packedOnly.fp16Bits(fp16 = true))
        assertEquals("fp32 asked for", 0, packedOnly.fp16Bits(fp16 = false))
    }

    @Test
    fun aSessionRefusesATileWithoutCore() {
        val model = FakeModel()
        val result = runCatching { Session(model, Geometry(2, 0, 30), 64) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun refusalsSayWhy() {
        assertEquals(ModelRefusal.RUNTIME, Sessions.attempt(FakeRuntime(available = false), fakeFiles(tmp.root), threads = 4).refusal)
        assertEquals(ModelRefusal.NOT_LOADED, Sessions.attempt(FakeRuntime(make = { _, _ -> null }), fakeFiles(tmp.root), threads = 4).refusal)
        val pooled = fakeFiles(tmp.root, extra = "Pooling gap 1 1 a b 0=1 4=1\n")
        assertEquals(ModelRefusal.WHOLE_TILE, Sessions.attempt(FakeRuntime(), pooled, threads = 4).refusal)
        val broken = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, failAt = 0) })
        assertEquals(ModelRefusal.DOES_NOT_RUN, Sessions.attempt(broken, fakeFiles(tmp.root), threads = 4).refusal)
        val opened = Sessions.attempt(FakeRuntime(), fakeFiles(tmp.root), threads = 4)
        assertNotNull(opened.session)
        assertNull(opened.refusal)
    }

    @Test
    fun weightsTakingAllItsMemoryAreRefused() {
        val files = fakeFiles(tmp.root)
        val runtime = FakeRuntime()
        val o = Sessions.attempt(runtime, files, threads = 4, memoryBytes = files.bin.length())
        assertEquals(ModelRefusal.MEMORY, o.refusal)
        assertEquals(files.bin.length(), o.bytes)
        assertTrue("opened before refusing", runtime.asked.isEmpty())
    }

    @Test
    fun measuredMemoryShrinksTiles() {
        // 32 channels at 2x: estimated 1.5 KB per input pixel; measured 20 KB. 256 MB holds 96 x 96 at that, 100 MB 64 x 64.
        val runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, bytesPerPixel = 20_000) })
        assertEquals(96, Sessions.open(runtime, fakeFiles(tmp.root, channels = 32), threads = 4)!!.size)
        assertEquals(64, Sessions.open(runtime, fakeFiles(tmp.root, channels = 32), threads = 4, memoryBytes = 100L shl 20)!!.size)
    }

    @Test
    fun aModelNeedingMoreThanItsMemoryIsRefusedBeforeItRuns() {
        // 1 MB per input pixel: the 32 x 32 probe holds 1 GiB; the 48 x 48 one would need 2.25 GiB.
        val runtime = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, bytesPerPixel = 1L shl 20) })
        val o = Sessions.attempt(runtime, fakeFiles(tmp.root), threads = 4, memoryBytes = 2L shl 30)
        assertEquals(ModelRefusal.MEMORY, o.refusal)
        assertTrue(o.bytes > 2L shl 30)
        val cpu = runtime.opened.single() as FakeModel
        assertTrue(cpu.closed)
        assertTrue(cpu.inputs.all { it.width <= 32 })
    }

    @Test
    fun aGpuSizeHoldingMoreThanItsBudgetIsPassedOver() {
        // On the GPU only, 20 KB per input pixel: a 192 tile holds 703 MiB, past a quarter of 2 GiB; 128 holds 312 MiB.
        val runtime = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, bytesPerPixel = if (onGpu) 20_000 else 0) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertEquals(128, s.size)
    }

    /** [fakeFiles] whose `.bin` is [mb] MB. */
    private fun heavyFiles(mb: Int): ModelFiles = fakeFiles(tmp.root).also { f ->
        java.io.RandomAccessFile(f.bin, "rw").use { it.setLength(mb.toLong() shl 20) }
    }

    @Test
    fun weightsAsTheyLoadedPastItsMemoryAreRefused() {
        // A 4 MB file whose weights took 30 MB as they loaded.
        val runtime = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, weights = 30L shl 20) })
        val o = Sessions.attempt(runtime, heavyFiles(4), threads = 4, memoryBytes = 25L shl 20)
        assertEquals(ModelRefusal.MEMORY, o.refusal)
        assertEquals(30L shl 20, o.bytes)
        assertTrue((runtime.opened.single() as FakeModel).closed)
    }

    @Test
    fun theGpuIsCheckedWithinWhatBothCopiesLeave() {
        // 20 MB of weights on each: 10 MB of 50 left to check; at 1.5 KB per input pixel a 64 tile fits it, 96 does not.
        val runtime = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, weights = 20L shl 20) })
        val s = Sessions.open(runtime, heavyFiles(4), threads = 4, memoryBytes = 50L shl 20)!!
        assertTrue(s.model.onGpu)
        assertEquals(64, s.size)
    }

    @Test
    fun measuredWeightsStayWithinWhatTheFileCanComeTo() {
        assertEquals(30L, ModelMemory.weights(30, 10, gpu = false))
        assertEquals(10L, ModelMemory.weights(0, 10, gpu = false))
        assertEquals(5L, ModelMemory.weights(0, 10, gpu = true))
        assertEquals(80L, ModelMemory.weights(1000, 10, gpu = false))
    }

    @Test
    fun aModelMayTakeAnEighthOfMemoryAndHalfOfWhatIsFree() {
        assertEquals(1L shl 30, ModelMemory.budget(8L shl 30, 3L shl 30))
        assertEquals(512L shl 20, ModelMemory.budget(8L shl 30, 1L shl 30))
    }
}
