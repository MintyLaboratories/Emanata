package minty.emanata.minified

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// What a minified build (R8) must keep although code reaches it in ways R8 cannot see, checked
// against the build's outputs (check): R8's mapping, its dex files, and the app's classes as
// compiled before R8. The rules are app/r8-survivors.txt (Rules).

/** [member] of [cls] (or [cls] itself, when null) must survive R8; under its own names when [sameName]. Binary names (`a.B$C`). */
data class Survivor(val cls: String, val member: String?, val sameName: Boolean)

/** The rules of r8-survivors.txt: [survivors], and the types ([weak]) that what they register with holds only weakly. */
class Rules(val survivors: List<Survivor>, val weak: Set<String>) {
    companion object {
        /**
         * One rule per line, `#` starts a comment: `<class>`, `<class> <member>`, either followed
         * by `same-name`, or `weak <type>`.
         */
        fun parse(lines: List<String>): Rules {
            val survivors = ArrayList<Survivor>()
            val weak = LinkedHashSet<String>()
            for ((i, raw) in lines.withIndex()) {
                val words = raw.substringBefore('#').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) continue
                val sameName = words.last() == SAME_NAME
                val rest = if (sameName) words.dropLast(1) else words
                when {
                    rest.firstOrNull() == "weak" && rest.size == 2 && !sameName -> weak += rest[1]
                    rest.size in 1..2 && rest[0] != "weak" -> survivors += Survivor(rest[0], rest.getOrNull(1), sameName)
                    else -> throw IllegalArgumentException("r8-survivors.txt line ${i + 1}: $raw")
                }
            }
            return Rules(survivors, weak)
        }

        private const val SAME_NAME = "same-name"
    }
}

/** One class of R8's mapping: its [name] after R8, and the names after R8 of its [fields] and [methods] that R8 lists, by name before. */
class MappedClass(val name: String) {
    val fields = HashMap<String, String>()
    val methods = HashMap<String, MutableSet<String>>()

    /** [member]'s names after R8 as the mapping lists them; empty when it lists none. */
    fun namesOf(member: String): Set<String> = listOfNotNull(fields[member]).toSet() + methods[member].orEmpty()
}

/** R8's mapping (mapping.txt): [classes] by name before R8. */
class Mapping(val classes: Map<String, MappedClass>) {
    /** Each class's name before R8, by its name after; where R8 merged classes into one, the one whose name it is. */
    val before: Map<String, String> by lazy {
        classes.entries.groupBy({ it.value.name }, { it.key }).mapValues { (after, names) -> if (after in names) after else names.first() }
    }

    companion object {
        private val CLASS = Regex("""^(\S+) -> (\S+):$""")

        // [start:end:]type name(arguments)[:line[:end]] -> name after
        private val METHOD = Regex("""^(?:\d+:\d+:)?\S+ ([^\s(]+)\([^)]*\)(:\d+)?(:\d+)? -> (\S+)$""")
        private val FIELD = Regex("""^\S+ (\S+) -> (\S+)$""")

        /**
         * Reads a mapping. A method inlined into another is listed as a stack of frames, the
         * inlined one first, each caller after it with a single line number: only the last frame
         * of a stack is a method of the class. One whose name has a class in front came from
         * that class and is left out.
         */
        fun read(lines: Sequence<String>): Mapping {
            val classes = HashMap<String, MappedClass>()
            var current: MappedClass? = null
            var pending: Pair<String, String>? = null
            fun commit() {
                val (name, after) = pending ?: return
                if ('.' !in name) current?.methods?.getOrPut(name) { HashSet() }?.add(after)
                pending = null
            }
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (!raw.startsWith(" ")) {
                    commit()
                    current = CLASS.find(line)?.let { m -> MappedClass(m.groupValues[2]).also { classes[m.groupValues[1]] = it } }
                    continue
                }
                val method = METHOD.find(line)
                if (method != null) {
                    val (name, line1, line2, after) = method.destructured
                    val caller = line1.isNotEmpty() && line2.isEmpty()
                    if (!caller) commit()
                    pending = name to after
                    continue
                }
                commit()
                FIELD.find(line)?.let { m -> current?.fields?.put(m.groupValues[1], m.groupValues[2]) }
            }
            commit()
            return Mapping(classes)
        }
    }
}

/** The fields and methods a class of the build's output defines, by name, and its native methods. */
class Members(val fields: Set<String>, val methods: Set<String>, val natives: Set<String>)

/** The classes the build's dex files define, by binary name. */
class Output(val classes: Map<String, Members>) {
    fun has(cls: String, member: String): Boolean = classes[cls]?.let { member in it.fields || member in it.methods } == true

    companion object {
        fun read(dexes: List<ByteArray>): Output = Output(dexes.flatMap { Dex(it).classes() }.toMap())
    }

    /** One dex file: its classes and what each defines (class_def_item and class_data_item). */
    private class Dex(bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private var at = 0

        private fun int(offset: Int) = buf.getInt(offset)

        private val strings = Array(int(0x38)) { i -> string(int(int(0x3C) + 4 * i)) }

        private fun type(index: Int) = strings[int(int(0x44) + 4 * index)]

        private fun fieldName(index: Int) = strings[int(int(0x54) + 8 * index + 4)]

        private fun methodName(index: Int) = strings[int(int(0x5C) + 8 * index + 4)]

        fun classes(): List<Pair<String, Members>> = List(int(0x60)) { c ->
            val def = int(0x64) + 32 * c
            val name = type(int(def)).removePrefix("L").removeSuffix(";").replace('/', '.')
            val data = int(def + 24)
            val fields = HashSet<String>()
            val methods = HashSet<String>()
            val natives = HashSet<String>()
            if (data != 0) {
                at = data
                val sizes = IntArray(4) { uleb() }
                for (list in 0..1) {
                    var index = 0
                    repeat(sizes[list]) {
                        index += uleb()
                        uleb()
                        fields += fieldName(index)
                    }
                }
                for (list in 2..3) {
                    var index = 0
                    repeat(sizes[list]) {
                        index += uleb()
                        val access = uleb()
                        uleb()
                        val method = methodName(index)
                        methods += method
                        if (access and ACC_NATIVE != 0) natives += method
                    }
                }
            }
            name to Members(fields, methods, natives)
        }

        private fun uleb(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = buf.get(at++).toInt()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        // MUTF-8 after a ULEB128 length in UTF-16 units; plain UTF-8 decoding suffices for names.
        private fun string(offset: Int): String {
            var start = offset
            while (buf.get(start).toInt() and 0x80 != 0) start++
            start++
            var end = start
            while (buf.get(end).toInt() != 0) end++
            return String(buf.array(), start, end - start, Charsets.UTF_8)
        }
    }
}

/** A class as compiled, before R8: its [name], [superName] and [interfaces], and each field's descriptor and generic signature by field name. */
class Compiled(val name: String, val superName: String?, val interfaces: List<String>, val fields: Map<String, String>) {
    companion object {
        /** Reads a class file as far as its fields. */
        fun read(bytes: ByteArray): Compiled {
            val input = DataInputStream(bytes.inputStream())
            input.skipBytes(8)
            val count = input.readUnsignedShort()
            val utf = arrayOfNulls<String>(count)
            val classIndex = IntArray(count)
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf[i] = input.readUTF()
                    7 -> classIndex[i] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.skipBytes(2)
                    15 -> input.skipBytes(3)
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                    5, 6 -> {
                        input.skipBytes(8)
                        i++
                    }
                    else -> error("constant pool tag $tag")
                }
                i++
            }
            fun className(index: Int): String? = if (index == 0) null else utf[classIndex[index]]!!.replace('/', '.')
            input.skipBytes(2)
            val name = className(input.readUnsignedShort())!!
            val superName = className(input.readUnsignedShort())
            val interfaces = List(input.readUnsignedShort()) { className(input.readUnsignedShort())!! }
            val fields = LinkedHashMap<String, String>()
            repeat(input.readUnsignedShort()) {
                input.skipBytes(2)
                val field = utf[input.readUnsignedShort()]!!
                var types = utf[input.readUnsignedShort()]!!
                repeat(input.readUnsignedShort()) {
                    val attribute = utf[input.readUnsignedShort()]
                    val length = input.readInt()
                    if (attribute == "Signature") types += " " + utf[input.readUnsignedShort()]!! else input.skipBytes(length)
                }
                fields[field] = types
            }
            return Compiled(name, superName, interfaces, fields)
        }
    }
}

/** What [check] found: [problems]; the [weakFields] it checked (`Class.field`) and the [natives] in the output. */
class Findings(val problems: List<String>, val weakFields: List<String>, val natives: Int)

/**
 * What R8 removed or renamed of what code reaches unseen: each of [rules]' survivors; each
 * native method in [output], which must keep its own name and its class's (JNI binds them by
 * name); and each field of [compiled] holding one of [Rules.weak] (or a collection of them, or a
 * class implementing one), which must survive in a class that survives.
 */
fun check(rules: Rules, mapping: Mapping, output: Output, compiled: List<Compiled>): Findings {
    val problems = ArrayList<String>()

    /** [member] of [cls] (named after R8 [after]) as the output has it: the names after R8 it is found under. */
    fun kept(cls: String, after: String, member: String): List<String> =
        (mapping.classes[cls]?.namesOf(member).orEmpty() + member).filter { output.has(after, it) }

    for (s in rules.survivors) {
        val after = mapping.classes[s.cls]?.name ?: s.cls
        if (after !in output.classes) {
            problems += "${s.cls}: removed"
            continue
        }
        if (s.member == null) {
            if (s.sameName && after != s.cls) problems += "${s.cls}: renamed $after"
            continue
        }
        val names = kept(s.cls, after, s.member)
        when {
            names.isEmpty() -> problems += "${s.cls}.${s.member}: removed"
            s.sameName && (after != s.cls || s.member !in names) -> problems += "${s.cls}.${s.member}: renamed $after.${names.joinToString("/")}"
        }
    }

    var natives = 0
    for ((after, members) in output.classes) {
        if (members.natives.isEmpty()) continue
        val cls = mapping.before[after] ?: after
        val renamed = mapping.classes[cls]?.methods.orEmpty()
        for (native in members.natives) {
            natives++
            val before = renamed.filterValues { native in it }.keys
            if (cls != after || (before.isNotEmpty() && native !in before)) {
                problems += "$cls.${before.firstOrNull() ?: native}: native method renamed $after.$native"
            }
        }
    }

    val byName = compiled.associateBy { it.name }
    fun weak(type: String, seen: MutableSet<String> = HashSet()): Boolean = type in rules.weak ||
        (seen.add(type) && byName[type]?.let { c -> (listOfNotNull(c.superName) + c.interfaces).any { weak(it, seen) } } == true)
    val held = (rules.weak + compiled.map { it.name }.filter { weak(it) }).associateBy { "L" + it.replace('.', '/') }
    val weakFields = ArrayList<String>()
    for (c in compiled) {
        val after = mapping.classes[c.name]?.name ?: c.name
        // A class R8 removed has no instances to hold anything.
        if (after !in output.classes) continue
        for ((field, types) in c.fields) {
            val type = held.entries.firstOrNull { (descriptor, _) -> "$descriptor;" in types || "$descriptor<" in types }?.value ?: continue
            weakFields += "${c.name}.$field"
            if (kept(c.name, after, field).isEmpty()) {
                problems += "${c.name}.$field: removed, yet it holds $type, which what it is registered with keeps only weakly"
            }
        }
    }
    return Findings(problems, weakFields, natives)
}

private const val ACC_NATIVE = 0x0100
