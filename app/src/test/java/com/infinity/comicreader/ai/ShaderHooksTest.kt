package com.infinity.comicreader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** mpv hook headers read as the manual describes them, on synthetic shaders. */
class ShaderHooksTest {

    private val sizes = mapOf("MAIN" to Dims(100, 60), "OUTPUT" to Dims(200, 120), "feat" to Dims(50, 30))

    private fun eval(text: String): Float = ShaderExpr.parse(text).eval { sizes[it] }

    @Test
    fun expressionsAreReversePolish() {
        assertEquals(200f, eval("MAIN.w 2 *"), 0f)
        assertEquals(60f, eval("MAIN.height"), 0f)
        assertEquals(25f, eval("feat.width 2 /"), 0f)
        assertEquals(1f, eval("OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *"), 0f)
        assertEquals(0f, eval("MAIN.w OUTPUT.w >"), 0f)
        assertEquals(1f, eval("MAIN.w 100 ="), 0f)
        assertEquals(1f, eval("MAIN.w OUTPUT.w > !"), 0f)
        assertEquals(1f, eval("7 3 %"), 0f)
        assertEquals(-40f, eval("MAIN.h MAIN.w -"), 0f)
        assertEquals(setOf("OUTPUT", "MAIN"), ShaderExpr.parse("OUTPUT.w MAIN.w / 1.2 >").textures)
    }

    @Test
    fun malformedExpressionsAreRefused() {
        for (bad in listOf("", "MAIN.w *", "1 2", "MAIN.x", "MAIN", "2 ^ 3", "+")) {
            assertThrows(bad, ShaderException::class.java) { ShaderExpr.parse(bad) }
        }
        assertThrows(ShaderException::class.java) { eval("missing.w") }
    }

    private val twoPasses = """
        // Some license text, not part of any pass.
        //!DESC First pass
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE half
        //!WIDTH HOOKED.w 2 /
        //!HEIGHT HOOKED.h 2 /
        //!COMPONENTS 1

        vec4 hook() { return HOOKED_tex(HOOKED_pos); }
        //!DESC Second pass
        //!HOOK MAIN
        //!BIND MAIN
        //!BIND half
        //!WHEN OUTPUT.w MAIN.w / 1.2 >
        #define x(o) half_texOff(o)
        vec4 hook() { return MAIN_tex(MAIN_pos) + x(vec2(1, 0)); }
    """.trimIndent()

    @Test
    fun passesSplitAtHeadersAfterCode() {
        val passes = ShaderHooks.parse(twoPasses)
        assertEquals(2, passes.size)
        val (a, b) = passes
        assertEquals("First pass", a.desc)
        assertEquals(listOf("MAIN"), a.hooks)
        assertEquals(listOf("HOOKED"), a.binds)
        assertEquals("half", a.saves)
        assertEquals("HOOKED.w 2 /", a.width.toString())
        assertEquals(1, a.components)
        assertNull(a.condition)
        assertTrue(a.body.contains("return HOOKED_tex(HOOKED_pos)"))
        assertTrue("the license stays out", !a.body.contains("license"))
        assertEquals(listOf("MAIN", "half"), b.binds)
        assertNull(b.saves)
        assertNull(b.width)
        assertEquals(4, b.components)
        assertEquals("OUTPUT.w MAIN.w / 1.2 >", b.condition.toString())
        assertTrue(b.body.startsWith("#define x(o)"))
    }

    @Test
    fun windowsLineEndingsRead() {
        assertEquals(2, ShaderHooks.parse(twoPasses.replace("\n", "\r\n")).size)
    }

    @Test
    fun aPassMayHookSeveralStages() {
        val p = ShaderHooks.parse("//!HOOK MAIN\n//!HOOK PREKERNEL\n//!BIND HOOKED\nvec4 hook() { return HOOKED_tex(HOOKED_pos); }\n").single()
        assertEquals(listOf("MAIN", "PREKERNEL"), p.hooks)
    }

    @Test
    fun unsupportedFeaturesAreRefused() {
        val hook = "vec4 hook() { return vec4(0.0); }\n"
        for (header in listOf(
            "//!HOOK LUMA", "//!HOOK OUTPUT", "//!HOOK MAIN\n//!COMPUTE 16 16", "//!HOOK MAIN\n//!OFFSET 0.5 0.5",
            "//!HOOK MAIN\n//!COMPONENTS 5", "//!HOOK MAIN\n//!BIND not a name", "//!HOOK MAIN\n//!FANCY 1",
            "//!TEXTURE lut\n//!SIZE 4 4", "//!PARAM strength\n//!TYPE float\n0.5", "//!DESC no hook"
        )) {
            assertThrows(header, ShaderException::class.java) { ShaderHooks.parse("$header\n$hook") }
        }
        assertThrows(ShaderException::class.java) { ShaderHooks.parse("// just a comment\nvoid main() {}\n") }
        assertThrows("no hook()", ShaderException::class.java) { ShaderHooks.parse("//!HOOK MAIN\nvoid f() {}\n") }
        // OFFSET ALIGN asks what the runner does anyway.
        assertEquals(1, ShaderHooks.parse("//!HOOK MAIN\n//!OFFSET ALIGN\n$hook").size)
    }
}
