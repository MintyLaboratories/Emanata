package com.infinity.comicreader.ai

import kotlin.math.roundToInt

/**
 * A pass of a chain as the GPU runs it: [pass] at [stage], reading one texture per entry of
 * [bindings] (binding i is texture unit i), each also known by its [aliases], storing the
 * first [components] channels of its result (the rest read 0, alpha 1, as from a texture of
 * that many channels); its fragment shader is [source] ([ShaderSource]).
 */
class ShaderProgram(val pass: ShaderPass, val stage: String, val components: Int = pass.components ?: 4) {
    /** The names bound, one texture each: a pass's BINDs less those naming the same image twice. */
    val bindings: List<String>

    /** Every name the shader may read binding i by: HOOKED and the stage's own name are one image. */
    val aliases: List<List<String>>

    init {
        val names = LinkedHashSet<String>()
        val bound = ArrayList<String>()
        val all = ArrayList<List<String>>()
        for (b in pass.binds) {
            val same = when (b) {
                ShaderHooks.HOOKED -> listOf(b, stage)
                stage -> listOf(b, ShaderHooks.HOOKED)
                else -> listOf(b)
            }
            if (same.all { it in names }) continue
            val fresh = same.filter { names.add(it) }
            bound += b
            all += fresh
        }
        bindings = bound
        aliases = all
    }

    val source: String by lazy { ShaderSource.fragment(this) }
}

/** One draw: [program] into texture slot [out], [width] x [height], binding i reading slot [inputs]\[i]. */
class ShaderStep(val program: Int, val out: Int, val width: Int, val height: Int, val inputs: IntArray)

/**
 * A chain's run on one [input] size, for a [target] size: texture [slots] by size (slot 0 the
 * input, 8-bit; the rest float), [steps] in order, its image left in slot [result]. Slots are
 * reused once nothing reads them any more, so a texture lives only as long as some pass
 * still needs it.
 */
class ShaderPlan(val input: Dims, val target: Dims, val slots: List<Dims>, val steps: List<ShaderStep>, val result: Int) {
    /** The image the chain makes. */
    val output: Dims get() = slots[result]

    /** Bytes its textures hold: [floatBytes] per pixel of a float slot, 4 for the input and for the result's 8-bit copy. */
    fun bytes(floatBytes: Int): Long {
        var sum = 4L * input.width * input.height + 4L * output.width * output.height
        for (i in 1 until slots.size) sum += floatBytes.toLong() * slots[i].width * slots[i].height
        return sum
    }

    /** [encode]d once. */
    val encoded: IntArray by lazy { encode() }

    /**
     * As the native runner reads it: target width and height, slot count and each slot's width
     * and height, step count and each step's program, slot, width, height, input count and
     * inputs, then the result's slot.
     */
    fun encode(): IntArray {
        val out = ArrayList<Int>()
        out += target.width
        out += target.height
        out += slots.size
        for (s in slots) {
            out += s.width
            out += s.height
        }
        out += steps.size
        for (s in steps) {
            out += s.program
            out += s.out
            out += s.width
            out += s.height
            out += s.inputs.size
            for (i in s.inputs) out += i
        }
        out += result
        return out.toIntArray()
    }
}

/**
 * Shader files run in order as one model ([ShaderModel]): their [passes] form [programs], every
 * pass hooking MAIN in file order, then every pass hooking PREKERNEL, as mpv runs them. The
 * PREKERNEL stage starts from MAIN's result; binding MAIN there reads that result, whatever
 * PREKERNEL passes did since. The target size WHEN and WIDTH read as OUTPUT is the input
 * [scale] times larger. Throws [ShaderException] for a pass saving into another stage's image.
 */
class ShaderChain(val passes: List<ShaderPass>, val scale: Int) {
    init {
        require(scale >= 1) { "Scale $scale" }
    }

    /**
     * The passes by stage, each storing as many channels as it says, else as many as the image
     * it hooks has at that point: the page's four to start with (passes a [ShaderPass.condition]
     * skips counted as run).
     */
    val programs: List<ShaderProgram> = run {
        var mainComponents = 4
        ShaderHooks.STAGES.flatMap { stage ->
            var hooked = mainComponents
            passes.filter { stage in it.hooks }.map { pass ->
                val saves = pass.saves
                if (saves != null && saves in ShaderHooks.STAGES && saves != stage) {
                    throw ShaderException("${pass.desc ?: "A pass"} at $stage saves into $saves")
                }
                val n = pass.components ?: hooked
                if (saves == null || saves == ShaderHooks.HOOKED || saves == stage) {
                    hooked = n
                    if (stage == ShaderHooks.MAIN) mainComponents = n
                }
                ShaderProgram(pass, stage, n)
            }
        }
    }

    /** The fragment shader of each program. */
    val sources: List<String> get() = programs.map { it.source }

    private val plans = HashMap<Dims, ShaderPlan>()

    /**
     * The run on a [width] x [height] input, made once per size. Throws [ShaderException] when a
     * pass reads a texture no pass before made, or a size comes out below 1 or above [MAX_SIDE].
     */
    @Synchronized
    fun plan(width: Int, height: Int): ShaderPlan = plans.getOrPut(Dims(width, height)) { planOf(Dims(width, height)) }

    private class Value(val dims: Dims) {
        var lastUse = -1
        var slot = -1
    }

    private fun planOf(input: Dims): ShaderPlan {
        require(input.width > 0 && input.height > 0) { "Empty input" }
        val target = Dims(input.width * scale, input.height * scale)
        val source = Value(input).also { it.slot = 0 }
        // MAIN's image, kept once PREKERNEL starts; and the image of the stage running.
        var main = source
        var hooked = source
        val saved = HashMap<String, Value>()
        class Draw(val program: Int, val out: Value, val inputs: List<Value>)
        val draws = ArrayList<Draw>()
        for ((p, program) in programs.withIndex()) {
            val pass = program.pass
            val stage = program.stage
            fun valueOf(name: String): Value? = when (name) {
                ShaderHooks.HOOKED, stage -> hooked
                ShaderHooks.MAIN -> main
                // A later stage's image does not exist yet.
                in ShaderHooks.STAGES -> null
                else -> saved[name]
            }
            fun dimsOf(name: String): Dims? = if (name == ShaderHooks.OUTPUT) target else valueOf(name)?.dims
            if (pass.condition?.eval(::dimsOf) == 0f) continue
            val inputs = program.bindings.map { valueOf(it) ?: throw ShaderException("${pass.desc ?: "A pass"} binds $it, which no pass before it saves") }
            val w = pass.width?.let { side(it.eval(::dimsOf), it) } ?: hooked.dims.width
            val h = pass.height?.let { side(it.eval(::dimsOf), it) } ?: hooked.dims.height
            val out = Value(Dims(w, h))
            for (v in inputs) v.lastUse = draws.size
            draws += Draw(p, out, inputs)
            when (val name = pass.saves ?: ShaderHooks.HOOKED) {
                ShaderHooks.HOOKED, stage -> {
                    hooked = out
                    if (stage == ShaderHooks.MAIN) main = out
                }
                else -> saved[name] = out
            }
        }
        hooked.lastUse = Int.MAX_VALUE
        // Slots by first fit of the same size among those no later draw reads.
        val slots = arrayListOf(input)
        val free = ArrayList<Int>()
        val steps = ArrayList<ShaderStep>(draws.size)
        for ((i, d) in draws.withIndex()) {
            val reuse = free.firstOrNull { slots[it] == d.out.dims }
            d.out.slot = if (reuse != null) {
                free.remove(reuse)
                reuse
            } else {
                slots += d.out.dims
                slots.size - 1
            }
            steps += ShaderStep(d.program, d.out.slot, d.out.dims.width, d.out.dims.height, IntArray(d.inputs.size) { d.inputs[it].slot })
            for (v in d.inputs + d.out) if (v.slot > 0 && v.lastUse <= i && v.slot !in free) free += v.slot
        }
        return ShaderPlan(input, target, slots, steps, hooked.slot)
    }

    private fun side(v: Float, expr: ShaderExpr): Int {
        val n = v.roundToInt()
        if (v.isNaN() || n < 1 || n > MAX_SIDE) throw ShaderException("'$expr' gives $v")
        return n
    }

    companion object {
        /** The longest side a texture may have: GLES 3.0 guarantees 2048, this tablet's GPU allows 16384. */
        const val MAX_SIDE = 16384

        /** [texts], shader files run in that order, read as one chain enlarging [scale] times; throws [ShaderException]. */
        fun parse(texts: List<String>, scale: Int): ShaderChain = ShaderChain(texts.flatMap(ShaderHooks::parse), scale)
    }
}

/**
 * The GLSL ES 3.00 fragment shader of a program: each binding i is sampler `emanata_t<i>` with
 * its size `emanata_s<i>` and texel size `emanata_p<i>` (uniforms the runner sets), and every
 * alias of it gets mpv's names: `NAME_raw`, `NAME_pos` (the output pixel's place, in
 * coordinates every texture shares: all cover the same image), `NAME_size`, `NAME_pt`,
 * `NAME_tex(pos)` (sampled bilinearly, edges clamped), `NAME_texOff(offset)` (offset in
 * NAME's pixels), `NAME_mul`, `NAME_rot` and `NAME_off`. The place is [BIAS] of a pixel past
 * the pixel's center: a sample meant for a texel's center lands just past it, where the GPU's
 * coarse filter weights give that texel alone, not a rounding error before it, where they mix
 * in 1/256 of the texel before (sizes not a power of two round so); deep networks amplify
 * that into differences of many codes between tile sizes. Samples between texels move by too
 * little to change their weights. `target_size` and `input_size` are the target's and the
 * input's sizes, `random` and `frame` 0. The pass's code follows, then a `main` writing
 * `hook()` to the target, the channels past [ShaderProgram.components] as a texture of that
 * many channels reads them (0, alpha 1). The runner draws one triangle covering the target.
 */
object ShaderSource {
    /**
     * The runner's version: raised whenever what a chain computes may change other than through
     * its files (these conventions, `shader_gles.c`'s vertex and encode shaders, the formats or
     * arithmetic between passes), so compiled programs ([GlesShaders.cacheName]) and stored
     * pages ([ShaderModel.fingerprint]) of an earlier runner are not used.
     */
    const val RUNNER = 2

    /** How far past a pixel's center its place is taken, in pixels: 1/1024, past fp32 rounding, short of a filter weight's 1/256. */
    const val BIAS = "0.0009765625"

    fun fragment(program: ShaderProgram): String = buildString {
        append("#version 300 es\n")
        append("precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n")
        append("uniform vec2 emanata_out;\nuniform vec2 emanata_target;\nuniform vec2 emanata_input;\n")
        append("vec2 emanata_pos;\n")
        append("#define target_size emanata_target\n#define input_size emanata_input\n#define random 0.0\n#define frame 0\n")
        for ((i, names) in program.aliases.withIndex()) {
            append("uniform sampler2D emanata_t").append(i).append(";\n")
            append("uniform vec2 emanata_s").append(i).append(";\n")
            append("uniform vec2 emanata_p").append(i).append(";\n")
            for (n in names) {
                append("#define ").append(n).append("_raw emanata_t").append(i).append('\n')
                append("#define ").append(n).append("_pos emanata_pos\n")
                append("#define ").append(n).append("_size emanata_s").append(i).append('\n')
                append("#define ").append(n).append("_pt emanata_p").append(i).append('\n')
                append("#define ").append(n).append("_mul 1.0\n")
                append("#define ").append(n).append("_rot mat2(1.0, 0.0, 0.0, 1.0)\n")
                append("#define ").append(n).append("_off vec2(0.0)\n")
                append("#define ").append(n).append("_tex(pos) texture(emanata_t").append(i).append(", pos)\n")
                append("#define ").append(n).append("_texOff(off) texture(emanata_t").append(i).append(", emanata_pos + emanata_p").append(i).append(" * vec2(off))\n")
            }
        }
        append("#line 1\n")
        append(program.pass.body)
        append("\nout vec4 emanata_color;\n")
        append("void main() {\n  emanata_pos = (gl_FragCoord.xy + vec2(").append(BIAS).append(")) / emanata_out;\n")
        append(
            when (program.components) {
                1 -> "  emanata_color = vec4(hook().r, 0.0, 0.0, 1.0);\n"
                2 -> "  emanata_color = vec4(hook().rg, 0.0, 1.0);\n"
                3 -> "  emanata_color = vec4(hook().rgb, 1.0);\n"
                else -> "  emanata_color = hook();\n"
            }
        )
        append("}\n")
    }
}
