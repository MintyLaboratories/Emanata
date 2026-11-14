package com.infinity.comicreader.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chains planned as mpv runs them: stage order, sizes, conditions, textures reused once read for the last time, and the GLSL each pass gets. */
class ShaderChainTest {

    /** A clamp file as Anime4K lays one out: statistics at MAIN, the clamp itself at PREKERNEL. */
    private val clamp = """
        //!DESC stats
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE stats
        //!COMPONENTS 1
        vec4 hook() { return vec4(MAIN_texOff(vec2(1, 0)).x, 0.0, 0.0, 0.0); }
        //!DESC clamp
        //!HOOK PREKERNEL
        //!BIND HOOKED
        //!BIND stats
        vec4 hook() { return min(HOOKED_tex(HOOKED_pos), stats_tex(HOOKED_pos).xxxx); }
    """.trimIndent()

    /** A small upscaler: two features, a mix, one more layer, then a 2x step adding the input back. */
    private val upscale = """
        //!DESC a
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE a
        vec4 hook() { return HOOKED_texOff(vec2(-1, 0)); }
        //!DESC b
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE b
        vec4 hook() { return HOOKED_texOff(vec2(0, 1)); }
        //!DESC c
        //!HOOK MAIN
        //!BIND a
        //!BIND b
        //!SAVE c
        //!WIDTH a.w
        //!HEIGHT a.h
        vec4 hook() { return a_tex(a_pos) - b_tex(b_pos); }
        //!DESC d
        //!HOOK MAIN
        //!BIND c
        //!SAVE d
        vec4 hook() { return max(c_tex(c_pos), 0.0); }
        //!DESC up
        //!HOOK MAIN
        //!BIND MAIN
        //!BIND d
        //!SAVE MAIN
        //!WIDTH d.w 2 *
        //!HEIGHT d.h 2 *
        //!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *
        vec4 hook() { return d_tex(d_pos) + MAIN_tex(MAIN_pos); }
    """.trimIndent()

    private val chain = ShaderChain.parse(listOf(clamp, upscale), 2)

    @Test
    fun mainPassesRunInFileOrderThenPrekernel() {
        assertEquals(listOf("stats", "a", "b", "c", "d", "up", "clamp"), chain.programs.map { it.pass.desc })
        assertEquals(listOf("MAIN", "MAIN", "MAIN", "MAIN", "MAIN", "MAIN", "PREKERNEL"), chain.programs.map { it.stage })
    }

    @Test
    fun hookedAndTheStagesNameAreOneTexture() {
        val stats = chain.programs[0]
        assertEquals(listOf("HOOKED"), stats.bindings)
        assertEquals(listOf(listOf("HOOKED", "MAIN")), stats.aliases)
        val up = chain.programs[5]
        assertEquals(listOf("MAIN", "d"), up.bindings)
        assertEquals(listOf(listOf("MAIN", "HOOKED"), listOf("d")), up.aliases)
        assertEquals(listOf(listOf("HOOKED", "PREKERNEL"), listOf("stats")), chain.programs[6].aliases)
        // Bound twice, read once.
        val twice = ShaderProgram(ShaderHooks.parse("//!HOOK MAIN\n//!BIND HOOKED\n//!BIND MAIN\nvec4 hook() { return MAIN_tex(MAIN_pos); }").single(), "MAIN")
        assertEquals(listOf("HOOKED"), twice.bindings)
    }

    @Test
    fun texturesAreReusedOnceNothingReadsThem() {
        val plan = chain.plan(10, 8)
        val small = Dims(10, 8)
        val large = Dims(20, 16)
        // stats, a and b each new; c new (a and b still read by it); d takes a's slot; up and clamp at 2x.
        assertEquals(listOf(small, small, small, small, small, large, large), plan.slots)
        assertEquals(listOf(1, 2, 3, 4, 2, 5, 6), plan.steps.map { it.out })
        assertArrayEquals(intArrayOf(0), plan.steps[1].inputs)
        assertArrayEquals(intArrayOf(2, 3), plan.steps[3].inputs)
        assertArrayEquals(intArrayOf(0, 2), plan.steps[5].inputs)
        assertArrayEquals(intArrayOf(5, 1), plan.steps[6].inputs)
        assertEquals(6, plan.result)
        assertEquals(large, plan.output)
        assertEquals(Dims(20, 16), plan.target)
        for (s in plan.steps) for (i in s.inputs) assertTrue("a pass draws into what it reads", i != s.out)
        assertTrue("planned once per size", plan === chain.plan(10, 8))
    }

    @Test
    fun aFalseConditionSkipsThePass() {
        // At 1x the target is no larger than MAIN: no upscale, the clamp works at the input's size.
        val plan = ShaderChain.parse(listOf(clamp, upscale), 1).plan(10, 8)
        assertEquals(listOf(0, 1, 2, 3, 4, 6), plan.steps.map { it.program })
        assertEquals(Dims(10, 8), plan.output)
    }

    @Test
    fun bytesCountEveryTextureOnce() {
        val plan = chain.plan(10, 8)
        // Input and the read-back copy at 4 bytes, five 10 x 8 and two 20 x 16 float slots at 8.
        assertEquals(4L * 80 + 4L * 320 + 8L * (4 * 80 + 2 * 320), plan.bytes(8))
    }

    @Test
    fun theEncodedPlanIsWhatTheRunnerReads() {
        val plan = ShaderChain.parse(listOf(upscale), 2).plan(3, 2)
        val e = plan.encode()
        assertEquals(listOf(6, 4, 5, 3, 2, 3, 2, 3, 2, 3, 2, 6, 4, 5), e.take(14))
        // a: program 0 into slot 1 at 3 x 2, one input, slot 0.
        assertEquals(listOf(0, 1, 3, 2, 1, 0), e.drop(14).take(6))
        assertEquals(plan.result, e.last())
        assertArrayEquals(e, plan.encoded)
    }

    @Test
    fun plansThatCannotRunAreRefused() {
        val early = "//!HOOK MAIN\n//!BIND later\nvec4 hook() { return later_tex(later_pos); }\n//!HOOK MAIN\n//!SAVE later\nvec4 hook() { return vec4(0.0); }\n"
        assertThrows(ShaderException::class.java) { ShaderChain.parse(listOf(early), 1).plan(4, 4) }
        val huge = "//!HOOK MAIN\n//!WIDTH MAIN.w 100000 *\nvec4 hook() { return vec4(0.0); }\n"
        assertThrows(ShaderException::class.java) { ShaderChain.parse(listOf(huge), 1).plan(4, 4) }
        val empty = "//!HOOK MAIN\n//!WIDTH MAIN.w 0 *\nvec4 hook() { return vec4(0.0); }\n"
        assertThrows(ShaderException::class.java) { ShaderChain.parse(listOf(empty), 1).plan(4, 4) }
    }

    @Test
    fun eachPassGetsMpvsNamesForWhatItBinds() {
        val stats = chain.programs[0].source
        assertTrue(stats.startsWith("#version 300 es\n"))
        assertTrue(stats.contains("uniform sampler2D emanata_t0;"))
        assertTrue(stats.contains("#define HOOKED_tex(pos) texture(emanata_t0, pos)"))
        assertTrue(stats.contains("#define MAIN_texOff(off) texture(emanata_t0, emanata_pos + emanata_p0 * vec2(off))"))
        assertTrue(stats.contains("#define MAIN_pt emanata_p0"))
        assertTrue(stats.contains("#define MAIN_size emanata_s0"))
        assertTrue(stats.contains("#define HOOKED_pos emanata_pos"))
        assertTrue(stats.contains("return vec4(MAIN_texOff(vec2(1, 0)).x"))
        assertTrue("just past the pixel's center", stats.contains("emanata_pos = (gl_FragCoord.xy + vec2(0.0009765625)) / emanata_out;"))
        assertTrue("one channel kept", stats.trimEnd().endsWith("emanata_color = vec4(hook().r, 0.0, 0.0, 1.0);\n}"))
        val up = chain.programs[5].source
        assertTrue(up.contains("#define d_tex(pos) texture(emanata_t1, pos)"))
        assertTrue(up.contains("uniform vec2 emanata_s1;"))
        assertTrue("no texture past the bound ones", !up.contains("emanata_t2"))
        assertEquals(chain.programs.map { it.source }, chain.sources)
    }

    @Test
    fun prekernelPassesReadMainAsItsStageLeftIt() {
        val text = """
            //!DESC brighten
            //!HOOK MAIN
            //!BIND HOOKED
            vec4 hook() { return HOOKED_tex(HOOKED_pos) * 2.0; }
            //!DESC first
            //!HOOK PREKERNEL
            //!BIND HOOKED
            vec4 hook() { return HOOKED_tex(HOOKED_pos) + 1.0; }
            //!DESC both
            //!HOOK PREKERNEL
            //!BIND HOOKED
            //!BIND MAIN
            vec4 hook() { return HOOKED_tex(HOOKED_pos) - MAIN_tex(MAIN_pos); }
        """.trimIndent()
        val c = ShaderChain.parse(listOf(text), 1)
        assertEquals(listOf(listOf("HOOKED", "PREKERNEL"), listOf("MAIN")), c.programs[2].aliases)
        val plan = c.plan(4, 4)
        val (brighten, first, both) = plan.steps
        assertArrayEquals("HOOKED is the PREKERNEL image so far, MAIN the MAIN stage's", intArrayOf(first.out, brighten.out), both.inputs)
        assertEquals(both.out, plan.result)
    }

    @Test
    fun stagesNotYetRunOrSavedIntoFromAnotherStageAreRefused() {
        val early = "//!HOOK MAIN\n//!BIND PREKERNEL\nvec4 hook() { return PREKERNEL_tex(PREKERNEL_pos); }\n"
        assertThrows(ShaderException::class.java) { ShaderChain.parse(listOf(early), 1).plan(4, 4) }
        val across = "//!HOOK PREKERNEL\n//!BIND HOOKED\n//!SAVE MAIN\nvec4 hook() { return HOOKED_tex(HOOKED_pos); }\n"
        assertThrows(ShaderException::class.java) { ShaderChain.parse(listOf(across), 1) }
    }

    @Test
    fun passesStoreTheirComponentsElseTheHookedImages() {
        val text = """
            //!DESC named
            //!HOOK MAIN
            //!BIND HOOKED
            //!SAVE feature
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
            //!DESC rgb
            //!HOOK MAIN
            //!BIND HOOKED
            //!COMPONENTS 3
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
            //!DESC after
            //!HOOK MAIN
            //!BIND HOOKED
            //!SAVE other
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
            //!DESC two
            //!HOOK PREKERNEL
            //!BIND HOOKED
            //!SAVE pair
            //!COMPONENTS 2
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
            //!DESC late
            //!HOOK PREKERNEL
            //!BIND HOOKED
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
        """.trimIndent()
        val c = ShaderChain.parse(listOf(text), 1)
        assertEquals(listOf(4, 3, 3, 2, 3), c.programs.map { it.components })
        assertTrue(c.programs[0].source.contains("emanata_color = hook();"))
        assertTrue(c.programs[1].source.contains("emanata_color = vec4(hook().rgb, 1.0);"))
        assertTrue(c.programs[3].source.contains("emanata_color = vec4(hook().rg, 0.0, 1.0);"))
    }
}
