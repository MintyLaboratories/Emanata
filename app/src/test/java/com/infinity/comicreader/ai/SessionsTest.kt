package com.infinity.comicreader.ai

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun opensOnTheGpuWhenThereIsOne() {
        val runtime = FakeRuntime()
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertTrue(s.model.onGpu)
        assertEquals(listOf(true), runtime.asked)
        assertEquals(Geometry(2, 0, 3 + ModelProbe.SAFETY), s.geometry)
        assertEquals(TileSizing.DEFAULT_MAX, s.size)
        assertEquals(1, (s.model as FakeModel).trims.get())
    }

    @Test
    fun opensOnTheCpuWithoutAGpu() {
        val runtime = FakeRuntime(gpu = null)
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertEquals(listOf(false), runtime.asked)
    }

    @Test
    fun opensOnTheCpuWhenAskedTo() {
        val runtime = FakeRuntime()
        assertFalse(Sessions.open(runtime, fakeFiles(tmp.root), threads = 4, gpu = false)!!.model.onGpu)
        assertEquals(listOf(false), runtime.asked)
    }

    @Test
    fun aModelTheGpuCannotOpenGoesToTheCpu() {
        val runtime = FakeRuntime(gpuOpens = false)
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertEquals(listOf(true, false), runtime.asked)
    }

    @Test
    fun aModelThatFailsOnTheGpuGoesToTheCpu() {
        val runtime = FakeRuntime(make = { gpu -> FakeModel(onGpu = gpu, failAt = if (gpu) 0 else -1) })
        val s = Sessions.open(runtime, fakeFiles(tmp.root), threads = 4)!!
        assertFalse(s.model.onGpu)
        assertTrue("the GPU model was left open", (runtime.opened[0] as FakeModel).closed)
        assertFalse((runtime.opened[1] as FakeModel).closed)
    }

    @Test
    fun aModelThatRunsNowhereOpensNoSession() {
        val runtime = FakeRuntime(make = { gpu -> FakeModel(onGpu = gpu, failAt = 0) })
        assertNull(Sessions.open(runtime, fakeFiles(tmp.root), threads = 4))
        assertTrue(runtime.opened.all { (it as FakeModel).closed })
        assertNull(Sessions.open(FakeRuntime(available = false), fakeFiles(tmp.root), threads = 4))
    }

    @Test
    fun aSmallGpuBudgetGetsSmallerTiles() {
        // 32 channels at 2x: 1.5 KB per input pixel; a quarter of 64 MB holds 96 x 96, not 128 x 128.
        val runtime = FakeRuntime(gpu = GpuInfo("Small GPU", 64, false))
        assertEquals(96, Sessions.open(runtime, fakeFiles(tmp.root, channels = 32), threads = 4)!!.size)
    }

    @Test
    fun aGpuFittingNoTileLeavesItToTheCpu() {
        val runtime = FakeRuntime(gpu = GpuInfo("Tiny GPU", 1, false))
        val s = Sessions.open(runtime, fakeFiles(tmp.root, channels = 1024), threads = 4)
        // The GPU fits no tile; the CPU's budget fits 64 x 64 at most for 1024 channels.
        assertNotNull(s)
        assertFalse(s!!.model.onGpu)
        assertEquals(64, s.size)
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
        assertEquals(48, TileSizing.widest(param))
        assertEquals(TileSizing.DEFAULT_WIDEST, TileSizing.widest("7767517\n1 1\nInput in0 0 1 in0\n"))
        assertEquals(64, TileSizing.widest("Deconvolution d 1 1 a b 1=4 0=64 5=1"))
    }

    @Test
    fun pickTakesTheLargestFittingSizeUpToTheCap() {
        val g = Geometry(2, 0, 8)
        val per = TileSizing.bytesPerPixel(64, 2)
        assertEquals(64L * 4 * 3 * 4, per)
        assertEquals(TileSizing.DEFAULT_MAX, TileSizing.pick(Long.MAX_VALUE, 64, g))
        assertEquals(512, TileSizing.pick(Long.MAX_VALUE, 64, g, max = 1000))
        assertEquals(192, TileSizing.pick(192L * 192 * per, 64, g, max = 512))
        assertEquals(128, TileSizing.pick(192L * 192 * per - 1, 64, g, max = 512))
        assertNull(TileSizing.pick(64L * 64 * per - 1, 64, g))
        // A margin that leaves 64 x 64 tiles no core skips them.
        assertNull(TileSizing.pick(64L * 64 * per, 64, Geometry(2, 0, 30)))
    }

    @Test
    fun gpuInfoParsesNativeDescriptions() {
        assertEquals(GpuInfo("Some GPU 1", 2048, true), GpuInfo.parse("Some GPU 1\t2048\t1"))
        assertEquals(GpuInfo("x", 0, false), GpuInfo.parse("x\t0\t0"))
        assertNull(GpuInfo.parse(null))
        assertNull(GpuInfo.parse("x\tmany\t1"))
        assertNull(GpuInfo.parse("x\t1"))
    }

    @Test
    fun aSessionRefusesATileWithoutCore() {
        val model = FakeModel()
        val result = runCatching { Session(model, Geometry(2, 0, 30), 64) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
