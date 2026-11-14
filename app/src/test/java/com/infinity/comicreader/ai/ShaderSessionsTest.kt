package com.infinity.comicreader.ai

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A runtime handing out [FakeModel]s for the chains it is asked to open ([make]; null: they do not compile). */
internal class FakeShaders(
    override val available: Boolean = true,
    override val floatBytes: Int = 8,
    override val maxSide: Int = 16384,
    private val make: (ShaderChain) -> TileModel? = { FakeModel(scale = it.scale) }
) : ShaderRuntime {
    val chains = ArrayList<ShaderChain>()
    val opened = ArrayList<TileModel>()
    val caches = ArrayList<File?>()

    override fun open(chain: ShaderChain, cache: File?): TileModel? {
        chains += chain
        caches += cache
        return make(chain)?.also { opened += it }
    }

    override fun key(chain: ShaderChain): String = GlesShaders.cacheName(listOf("fake"), chain.sources).removeSuffix(".programs")
}

/** Synthetic shader files: a 2x upscaler of two passes. */
internal object FakeShaderFiles {
    val UPSCALER = """
        //!DESC feature
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE feature
        vec4 hook() { return HOOKED_texOff(vec2(1, 0)) - HOOKED_tex(HOOKED_pos); }
        //!DESC enlarge
        //!HOOK MAIN
        //!BIND MAIN
        //!BIND feature
        //!SAVE MAIN
        //!WIDTH MAIN.w 2 *
        //!HEIGHT MAIN.h 2 *
        vec4 hook() { return MAIN_tex(MAIN_pos) + feature_tex(feature_pos); }
    """.trimIndent()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShaderSessionsTest {

    private fun open(runtime: ShaderRuntime, texts: List<String> = listOf(FakeShaderFiles.UPSCALER), memory: Long = Long.MAX_VALUE) =
        ShaderSessions.attempt(runtime, { texts }, scale = 2, memoryBytes = memory)

    @Test
    fun opensMeasuredOnTheGpuAtTheDefaultTile() {
        val runtime = FakeShaders()
        val o = open(runtime)
        assertNull(o.refusal)
        val s = o.session!!
        assertEquals(Geometry(2, 0, 3 + ModelProbe.SAFETY), s.geometry)
        assertEquals(ShaderSessions.DEFAULT_MAX, s.size)
        assertTrue(s.fp16)
        assertEquals(listOf("feature", "enlarge"), runtime.chains.single().programs.map { it.pass.desc })
        val model = s.model as FakeModel
        assertEquals("its textures freed once open", 1, model.trims.get())
        assertTrue(!model.closed)
    }

    @Test
    fun whatWasMeasuredIsKeptForTheNextOpen() {
        val dir = File("build/tmp/shader-fit-test").apply { deleteRecursively(); mkdirs() }
        val runtime = FakeShaders()
        val first = ShaderSessions.attempt(runtime, { listOf(FakeShaderFiles.UPSCALER) }, 2, cache = dir).session!!
        assertTrue((first.model as FakeModel).runs.get() > 0)
        val fit = File(dir, runtime.key(runtime.chains.single()) + ShaderSessions.FIT_SUFFIX)
        assertEquals(ShaderSessions.Fit(2, first.geometry.context, first.size, 0), ShaderSessions.Fit.read(fit))
        first.close()
        val again = ShaderSessions.attempt(runtime, { listOf(FakeShaderFiles.UPSCALER) }, 2, cache = dir).session!!
        assertEquals("no measuring, no seam check", 0, (again.model as FakeModel).runs.get())
        assertEquals(first.geometry, again.geometry)
        assertEquals(first.size, again.size)
        again.close()
        // Another scale, a smaller budget, or a file that does not read: measured again.
        val tight = ShaderSessions.attempt(runtime, { listOf(FakeShaderFiles.UPSCALER) }, 2, memoryBytes = 4L * ShaderChain.parse(listOf(FakeShaderFiles.UPSCALER), 2).plan(256, 256).bytes(8), cache = dir).session!!
        assertTrue((tight.model as FakeModel).runs.get() > 0)
        assertTrue(tight.size < first.size)
        tight.close()
        fit.writeText("not a fit")
        val unread = ShaderSessions.attempt(runtime, { listOf(FakeShaderFiles.UPSCALER) }, 2, cache = dir).session!!
        assertTrue((unread.model as FakeModel).runs.get() > 0)
        unread.close()
        dir.deleteRecursively()
    }

    @Test
    fun compiledPassesGoToTheCacheAskedFor() {
        val runtime = FakeShaders()
        val dir = File("build/tmp/shader-cache-test").apply { deleteRecursively() }
        ShaderSessions.attempt(runtime, { listOf(FakeShaderFiles.UPSCALER) }, 2, cache = dir).session!!.close()
        open(runtime)
        assertEquals(listOf(dir, null), runtime.caches)
    }

    @Test
    fun cacheNamesFollowTheSourcesAndTheDriver() {
        val gpu = listOf("GPU", "8", "16384", "16", "OpenGL ES 3.2 V@1")
        val name = GlesShaders.cacheName(gpu, listOf("a", "b"))
        assertTrue(name, name.matches(Regex("[0-9a-f]{32}\\.programs")))
        assertEquals(name, GlesShaders.cacheName(gpu, listOf("a", "b")))
        assertTrue(name != GlesShaders.cacheName(gpu, listOf("a", "c")))
        assertTrue(name != GlesShaders.cacheName(gpu, listOf("ab")))
        assertTrue("a driver update compiles again", name != GlesShaders.cacheName(gpu.dropLast(1) + "OpenGL ES 3.2 V@2", listOf("a", "b")))
        assertTrue("so does a runner update", name != GlesShaders.cacheName(gpu, listOf("a", "b"), ShaderSource.RUNNER + 1))
    }

    @Test
    fun onlyTheMostRecentlyUsedCachesStay() {
        val dir = File("build/tmp/shader-prune-test").apply { deleteRecursively(); mkdirs() }
        val files = (0 until GlesShaders.CACHED + 3).map { i -> File(dir, "$i.programs").apply { writeText("x"); setLastModified(1_000_000L * (i + 1)) } }
        val other = File(dir, "keep.txt").apply { writeText("x") }
        val now = System.currentTimeMillis()
        val left = File(dir, "a.programs.part").apply { writeText("x"); setLastModified(now - GlesShaders.PART_AGE_MS - 1000) }
        val writing = File(dir, "b.programs.part").apply { writeText("x"); setLastModified(now) }
        GlesShaders.prune(files[0], now)
        assertTrue("just used", files[0].isFile)
        assertEquals(GlesShaders.CACHED, dir.listFiles { f -> f.name.endsWith(".programs") }!!.size)
        assertTrue(files.last().isFile)
        assertTrue(!files[1].isFile && !files[2].isFile)
        assertTrue(other.isFile)
        assertTrue("an old partial cache goes, one being written stays", !left.isFile && writing.isFile)
        val keptFit = File(dir, "0" + ShaderSessions.FIT_SUFFIX).apply { writeText("x") }
        val goneFit = File(dir, "1" + ShaderSessions.FIT_SUFFIX).apply { writeText("x") }
        GlesShaders.prune(files[0], now)
        assertTrue("a fit stays with its cache, and goes with it", keptFit.isFile && !goneFit.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun noUsableGpuRefusesBeforeReadingTheFiles() {
        var read = false
        val runtime = FakeShaders(available = false)
        val o = ShaderSessions.attempt(runtime, { read = true; listOf(FakeShaderFiles.UPSCALER) }, 2)
        assertEquals(ModelRefusal.RUNTIME, o.refusal)
        assertTrue(!read && runtime.chains.isEmpty())
    }

    @Test
    fun filesThatDoNotParseOrCompileAreNotLoaded() {
        val runtime = FakeShaders()
        assertEquals(ModelRefusal.NOT_LOADED, open(runtime, listOf("//!HOOK LUMA\nvec4 hook() { return vec4(0.0); }\n")).refusal)
        assertEquals(ModelRefusal.NOT_LOADED, open(runtime, listOf("no headers at all")).refusal)
        assertTrue("nothing compiled", runtime.chains.isEmpty())
        assertEquals(ModelRefusal.NOT_LOADED, open(FakeShaders(make = { null })).refusal)
    }

    @Test
    fun aChainThatDoesNotEnlargeAsDeclaredIsRefused() {
        val runtime = FakeShaders(make = { FakeModel(scale = 1) })
        assertEquals(ModelRefusal.SHAPE, open(runtime).refusal)
        assertTrue((runtime.opened.single() as FakeModel).closed)
    }

    @Test
    fun outputThatDependsOnTheTilesShapeIsRefused() {
        val runtime = FakeShaders(make = { FakeModel(scale = 2, edgeNoise = true) })
        assertEquals(ModelRefusal.REACH, open(runtime).refusal)
        assertTrue((runtime.opened.single() as FakeModel).closed)
    }

    @Test
    fun noiseNearATilesEdgeIsMeasuredIntoTheMargin() {
        val runtime = FakeShaders(make = { FakeModel(scale = 2, borderNoise = 6) })
        val s = open(runtime).session!!
        assertTrue("margin ${s.geometry.margin}", s.geometry.margin >= 6)
    }

    @Test
    fun tilesFitTheTextureBudget() {
        val chain = ShaderChain.parse(listOf(FakeShaderFiles.UPSCALER), 2)
        val g = Geometry(2, 0, 5)
        val bytes = chain.plan(256, 256).bytes(8)
        assertEquals(listOf(256, 192, 128, 96, 64), ShaderSessions.sizes(chain, g, 8, bytes, 16384))
        assertEquals("up to the cap", listOf(384, 256, 192, 128, 96, 64), ShaderSessions.sizes(chain, g, 8, Long.MAX_VALUE, 16384, maxTile = 384))
        assertEquals("the output within the GPU's largest texture", 384, ShaderSessions.sizes(chain, g, 8, Long.MAX_VALUE, 1000, maxTile = 1024).first())
        assertEquals("32-bit floats take twice the room", 192, ShaderSessions.sizes(chain, g, 16, bytes, 16384).first())
        assertEquals(emptyList<Int>(), ShaderSessions.sizes(chain, g, 8, 1000, 16384))
    }

    @Test
    fun eachPageTakesTheTileThatLeavesTheLeastWork() {
        val g = Geometry(2, 0, 20)
        // 3 x 4 tiles either way, but 416 px ones overlap less than 512 px ones.
        assertEquals(416, ShaderSessions.pageTile(1000, 1500, g, 512))
        assertEquals(472, ShaderSessions.pageTile(2000, 3000, g, 512))
        assertEquals("one tile just large enough", 440, ShaderSessions.pageTile(300, 400, g, 512))
        assertTrue("never past the most", ShaderSessions.pageTile(5000, 5000, g, 512) <= 512)
        assertEquals("nothing fits: the most", 40, ShaderSessions.pageTile(100, 100, g, 40))
    }

    @Test
    fun theEngineRunsEachPageAtItsOwnTile() {
        val s = open(FakeShaders()).session!!
        val model = s.model as FakeModel
        val expected = ShaderSessions.pageTile(300, 400, s.geometry, s.size)
        assertTrue("$expected", expected < s.size)
        assertEquals(expected, s.sizeFor(300, 400))
        model.inputs.clear()
        val page = android.graphics.Bitmap.createBitmap(300, 400, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        val run = TileEngine.run(s, page)
        assertTrue(run.bitmap != null)
        assertEquals(1, run.stats.planned)
        assertTrue(model.inputs.all { it.width == expected && it.height == expected })
        assertEquals("a session without a pick keeps its size", 256, Session(model, s.geometry, 256).sizeFor(300, 400))
    }

    @Test
    fun slowTilesMakeTheLargestTileSmaller() {
        var now = 0L
        val runtime = FakeShaders(make = { FakeModel(scale = 2, onRun = { now += 400 }) })
        val s = ShaderSessions.attempt(runtime, { listOf(FakeShaderFiles.UPSCALER) }, 2, clock = { now }).session!!
        val m = s.geometry.margin
        assertEquals((s.size - 2 * m) % TilePlan.ALIGN, 0)
        fun ms(size: Int) = 400.0 * size * size / (ShaderSessions.DEFAULT_MAX * ShaderSessions.DEFAULT_MAX)
        assertTrue("${s.size}", s.size < ShaderSessions.DEFAULT_MAX && ms(s.size) <= ShaderSessions.TILE_MS)
        assertTrue("the next size up would take longer", ms(s.size + TilePlan.ALIGN) > ShaderSessions.TILE_MS)
        assertTrue(s.sizeFor(5000, 5000) <= s.size)
        assertEquals("fast tiles keep their size", 512, ShaderSessions.capped(512, s.geometry, ShaderSessions.TILE_MS))
        assertEquals("the smallest that fits at worst", TilePlan.MIN_CORE + 2 * m, ShaderSessions.capped(512, s.geometry, 1_000_000))
    }

    @Test
    fun tooLittleMemoryIsRefused() {
        val runtime = FakeShaders(make = { FakeModel(scale = 2) })
        val o = open(runtime, memory = 4L * 1024)
        assertEquals(ModelRefusal.MEMORY, o.refusal)
        assertTrue(o.bytes > 0)
        assertTrue((runtime.opened.single() as FakeModel).closed)
    }
}
