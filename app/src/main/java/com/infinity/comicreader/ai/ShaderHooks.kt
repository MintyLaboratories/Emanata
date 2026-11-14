package com.infinity.comicreader.ai

/** Why a shader does not run here: a directive the GLES runner does not support, bad syntax, or a pass it cannot plan. */
class ShaderException(message: String) : Exception(message)

/**
 * A size or condition in mpv's hook headers (`WIDTH`, `HEIGHT`, `WHEN`): reverse Polish
 * notation, tokens apart by spaces. A token is a number, a texture's size (`NAME.w` or
 * `NAME.width`, `NAME.h` or `NAME.height`), or an operator: `+ - * / %` and the comparisons
 * `> < =` (1 when true, else 0) take two values, `!` (1 for 0, else 0) one.
 */
class ShaderExpr private constructor(val text: String, private val tokens: List<Token>) {
    private sealed interface Token {
        class Number(val value: Float) : Token
        class Size(val texture: String, val width: Boolean) : Token
        class Op(val symbol: Char) : Token
    }

    /** The textures whose sizes it reads. */
    val textures: Set<String> get() = tokens.filterIsInstance<Token.Size>().map { it.texture }.toSet()

    /** Its value, texture sizes from [size] (null: no such texture). Throws [ShaderException] for a texture [size] lacks. */
    fun eval(size: (String) -> Dims?): Float {
        val stack = ArrayDeque<Float>()
        for (t in tokens) {
            when (t) {
                is Token.Number -> stack.addLast(t.value)
                is Token.Size -> {
                    val d = size(t.texture) ?: throw ShaderException("'$text' reads ${t.texture}, which no pass made")
                    stack.addLast((if (t.width) d.width else d.height).toFloat())
                }
                is Token.Op -> if (t.symbol == '!') {
                    stack.addLast(if (stack.removeLast() == 0f) 1f else 0f)
                } else {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(apply(t.symbol, a, b))
                }
            }
        }
        return stack.single()
    }

    override fun toString() = text

    companion object {
        private const val OPS = "+-*/%><=!"

        private fun apply(op: Char, a: Float, b: Float): Float = when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> a / b
            '%' -> a % b
            '>' -> if (a > b) 1f else 0f
            '<' -> if (a < b) 1f else 0f
            else -> if (a == b) 1f else 0f
        }

        /** [text] parsed; throws [ShaderException] for a token it does not know or values that do not add up to one. */
        fun parse(text: String): ShaderExpr {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { s ->
                s.toFloatOrNull()?.let { Token.Number(it) }
                    ?: if (s.length == 1 && s[0] in OPS) Token.Op(s[0]) else size(s) ?: throw ShaderException("'$text': unknown token '$s'")
            }
            var depth = 0
            for (t in tokens) {
                depth += when (t) {
                    is Token.Op -> if (t.symbol == '!') 0 else -1
                    else -> 1
                }
                if (depth < 1) throw ShaderException("'$text': an operator lacks values")
            }
            if (depth != 1) throw ShaderException("'$text' leaves $depth values")
            return ShaderExpr(text.trim(), tokens)
        }

        private fun size(s: String): Token.Size? {
            val name = s.substringBeforeLast('.', "")
            if (name.isEmpty()) return null
            return when (s.substringAfterLast('.')) {
                "w", "width" -> Token.Size(name, true)
                "h", "height" -> Token.Size(name, false)
                else -> null
            }
        }
    }
}

/**
 * One pass of an mpv user shader: GLSL code ([body]) defining `vec4 hook()`, run once per
 * output pixel at each stage it [hooks], reading the textures it [binds] and writing the one
 * it [saves] (null: the hooked image itself), [width] x [height] (null: the hooked image's
 * size), only while [condition] (null: always) is not 0. It stores the first [components]
 * channels of its result (null: as many as the hooked image has; [ShaderChain] resolves it).
 */
data class ShaderPass(
    val desc: String?,
    val hooks: List<String>,
    val binds: List<String>,
    val saves: String?,
    val width: ShaderExpr?,
    val height: ShaderExpr?,
    val components: Int?,
    val condition: ShaderExpr?,
    val body: String
)

/**
 * Reads mpv user shader files ("hooks"), as mpv's manual describes them: a file is a series of
 * passes, each a block of header lines starting with `//!`, then its GLSL code up to the next
 * header line; text before the first header (a license comment) belongs to no pass. Headers
 * read here: `HOOK`, `BIND`, `SAVE`, `DESC`, `WIDTH`, `HEIGHT`, `COMPONENTS`, `WHEN`, and
 * `OFFSET ALIGN` (the GLES runner aligns every texture alike). Compute passes, embedded
 * textures, buffers and parameters are refused ([ShaderException]), as are stages other than
 * [STAGES].
 */
object ShaderHooks {
    /** The stage of the source image as RGB, where models hook. */
    const val MAIN = "MAIN"

    /** The stage right before the main scaler: MAIN's result, after every pass hooking MAIN. */
    const val PREKERNEL = "PREKERNEL"

    /** The stages the GLES runner has, in the order they run. */
    val STAGES = listOf(MAIN, PREKERNEL)

    /** The name a pass binds to read the image of the stage it hooks. */
    const val HOOKED = "HOOKED"

    /** The name WIDTH, HEIGHT and WHEN read the target size by. */
    const val OUTPUT = "OUTPUT"

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The passes of [text], one file; throws [ShaderException] for anything not supported or malformed. */
    fun parse(text: String): List<ShaderPass> {
        val passes = ArrayList<ShaderPass>()
        var header: MutableList<String>? = null
        val body = StringBuilder()
        fun finish() {
            header?.let { passes += pass(it, body.toString()) }
            body.setLength(0)
        }
        for (line in text.replace("\r\n", "\n").split('\n')) {
            if (line.startsWith("//!")) {
                // A header line after code starts the next pass.
                if (header == null || body.isNotBlank()) {
                    finish()
                    header = ArrayList()
                }
                header!!.add(line.removePrefix("//!").trim())
            } else if (header != null) {
                body.append(line).append('\n')
            }
        }
        finish()
        if (passes.isEmpty()) throw ShaderException("No passes: no //!HOOK header")
        return passes
    }

    private fun pass(lines: List<String>, body: String): ShaderPass {
        var desc: String? = null
        val hooks = ArrayList<String>()
        val binds = ArrayList<String>()
        var saves: String? = null
        var width: ShaderExpr? = null
        var height: ShaderExpr? = null
        var components: Int? = null
        var condition: ShaderExpr? = null
        for (line in lines) {
            val key = line.substringBefore(' ').trim()
            val value = line.substringAfter(' ', "").trim()
            when (key) {
                "DESC" -> desc = value
                "HOOK" -> hooks += name(key, value).also { if (it !in STAGES) throw ShaderException("Hooks stage $it; supported: ${STAGES.joinToString()}") }
                "BIND" -> binds += name(key, value)
                "SAVE" -> saves = name(key, value)
                "WIDTH" -> width = ShaderExpr.parse(value)
                "HEIGHT" -> height = ShaderExpr.parse(value)
                "WHEN" -> condition = ShaderExpr.parse(value)
                "COMPONENTS" -> components = value.toIntOrNull()?.takeIf { it in 1..4 } ?: throw ShaderException("COMPONENTS '$value'")
                "OFFSET" -> if (value != "ALIGN") throw ShaderException("OFFSET '$value': only ALIGN is supported")
                "COMPUTE", "TEXTURE", "BUFFER", "PARAM", "STORAGE", "VAR" -> throw ShaderException("//!$key is not supported")
                else -> throw ShaderException("Unknown header //!$line")
            }
        }
        if (hooks.isEmpty()) {
            // A header block without HOOK: a texture, parameter or buffer block, refused above, or nothing.
            throw ShaderException("A pass hooks no stage${desc?.let { " ($it)" } ?: ""}")
        }
        if ("hook" !in body) throw ShaderException("Pass ${desc ?: ""} has no hook()")
        return ShaderPass(desc, hooks, binds, saves, width, height, components, condition, body)
    }

    private fun name(key: String, value: String): String =
        value.takeIf { NAME.matches(it) } ?: throw ShaderException("//!$key '$value' is not a texture name")
}
