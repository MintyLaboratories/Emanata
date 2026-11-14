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

/** A runtime handing out [FakeModel]s for the chains it is asked to open ([make]; null: they do not compile). */
internal class FakeShaders(
    override val available: Boolean = true,
    override val floatBytes: Int = 8,
    override val maxSide: Int = 16384,
    private val make: (ShaderChain) -> TileModel? = { FakeModel(scale = it.scale) }
) : ShaderRuntime {
    val chains = ArrayList<ShaderChain>()
    val opened = ArrayList<TileModel>()

    override fun open(chain: ShaderChain): TileModel? {
        chains += chain
        return make(chain)?.also { opened += it }
    }
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
    fun tooLittleMemoryIsRefused() {
        val runtime = FakeShaders(make = { FakeModel(scale = 2) })
        val o = open(runtime, memory = 4L * 1024)
        assertEquals(ModelRefusal.MEMORY, o.refusal)
        assertTrue(o.bytes > 0)
        assertTrue((runtime.opened.single() as FakeModel).closed)
    }
}
