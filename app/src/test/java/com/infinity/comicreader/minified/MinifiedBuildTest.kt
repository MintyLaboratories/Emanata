package com.infinity.comicreader.minified

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A listener of the app's own. */
private class OwnListener : SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = Unit
}

/** A class of the app holding listeners in each way the check knows, and a field it ignores. */
@Suppress("unused")
private class Holder {
    val one: SharedPreferences.OnSharedPreferenceChangeListener? = null
    val many = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    val own: OwnListener? = null
    val count = 0
}

/** [check] on a made-up mapping and output, and on class files compiled from this file. */
class MinifiedBuildTest {

    private val weak = "android.content.SharedPreferences\$OnSharedPreferenceChangeListener"
    private val holder = Holder::class.java.name
    private val ownListener = OwnListener::class.java.name

    private fun compiled(type: Class<*>): Compiled =
        Compiled.read(type.classLoader!!.getResourceAsStream(type.name.replace('.', '/') + ".class")!!.use { it.readBytes() })

    private fun mapping(text: String) = Mapping.read(text.trimIndent().lineSequence().map { if (it.startsWith("-")) "    " + it.drop(1) else it })

    private fun members(fields: Set<String> = emptySet(), methods: Set<String> = emptySet(), natives: Set<String> = emptySet()) =
        Members(fields, methods + natives, natives)

    @Test
    fun rulesReadClassesMembersSameNameAndWeakTypes() {
        val rules = Rules.parse(
            listOf(
                "# comment",
                "",
                "a.B",
                "a.B field # why",
                "a.B\$C call same-name",
                "a.D same-name",
                "weak a.Listener"
            )
        )
        assertEquals(
            listOf(Survivor("a.B", null, false), Survivor("a.B", "field", false), Survivor("a.B\$C", "call", true), Survivor("a.D", null, true)),
            rules.survivors
        )
        assertEquals(setOf("a.Listener"), rules.weak)
        assertThrows(IllegalArgumentException::class.java) { Rules.parse(listOf("a.B c d")) }
        assertThrows(IllegalArgumentException::class.java) { Rules.parse(listOf("weak a.Listener same-name")) }
    }

    @Test
    fun mappingCountsOnlyTheLastFrameOfAnInlinedStack() {
        val m = mapping(
            """
            # compiler: R8
            a.Foo -> x.a:
            # {"id":"sourceFile","fileName":"Foo.kt"}
            -java.util.Set listeners -> b
            -  # {"id":"com.android.tools.r8.residualsignature","signature":"Ljava/util/LinkedHashSet;"}
            -1:4:void <init>():8:8 -> <init>
            -1:3:void inlined():42:44 -> c
            -1:3:void inner():7 -> c
            -1:3:void outer():10 -> c
            -4:9:void outer():11:12 -> c
            -long nOpen(long) -> d
            -1:2:void a.Bar.moved():3:3 -> e
            a.Bar -> a.Bar:
            -int count -> count
            """
        )
        val foo = m.classes.getValue("a.Foo")
        assertEquals("x.a", foo.name)
        assertEquals(mapOf("listeners" to "b"), foo.fields)
        assertEquals(mapOf("<init>" to setOf("<init>"), "outer" to setOf("c"), "nOpen" to setOf("d")), foo.methods)
        assertEquals(mapOf("count" to "count"), m.classes.getValue("a.Bar").fields)
        assertEquals("a.Foo", m.before["x.a"])
    }

    @Test
    fun survivorsMustBeInTheOutputUnderTheirOwnNamesWhenSaid() {
        val rules = Rules.parse(
            listOf(
                "a.Gone",
                "a.Renamed same-name",
                "a.Foo listeners",
                "a.Foo instance",
                "a.Foo prefs",
                "a.Stop stop same-name",
                "a.Renamed call same-name"
            )
        )
        val m = mapping(
            """
            a.Foo -> x.a:
            -java.util.Set listeners -> b
            a.Renamed -> x.r:
            -void call() -> c
            """
        )
        val output = Output(
            mapOf(
                "x.a" to members(fields = setOf("b", "prefs")),
                "x.r" to members(methods = setOf("c")),
                "a.Stop" to members(methods = setOf("stop"))
            )
        )
        val findings = check(rules, m, output, emptyList())
        assertEquals(
            listOf(
                "a.Gone: removed",
                "a.Renamed: renamed x.r",
                "a.Foo.instance: removed",
                "a.Renamed.call: renamed x.r.c"
            ),
            findings.problems
        )
    }

    @Test
    fun nativeMethodsKeepTheirNamesAndTheirClasses() {
        val m = mapping(
            """
            a.Lib -> a.Lib:
            -long nOpen(long) -> a
            -1:2:int helper():5:5 -> b
            a.MergedIntoLib -> a.Lib:
            a.Moved -> x.m:
            -int nRun() -> nRun
            """
        )
        val output = Output(
            mapOf(
                "a.Lib" to members(methods = setOf("b"), natives = setOf("a", "nClose")),
                "x.m" to members(natives = setOf("nRun")),
                "a.Kept" to members(natives = setOf("nVersion"))
            )
        )
        val findings = check(Rules(emptyList(), emptySet()), m, output, emptyList())
        assertEquals(4, findings.natives)
        assertEquals(setOf("a.Lib.nOpen: native method renamed a.Lib.a", "a.Moved.nRun: native method renamed x.m.nRun"), findings.problems.toSet())
    }

    @Test
    fun fieldsHoldingWeaklyRegisteredListenersMustSurvive() {
        val compiled = listOf(compiled(Holder::class.java), compiled(OwnListener::class.java))
        assertEquals(listOf("one", "many", "own", "count"), compiled[0].fields.keys.toList())
        assertEquals(listOf(weak), compiled[1].interfaces)
        val rules = Rules(emptyList(), setOf(weak))
        val m = mapping(
            """
            $holder -> x.h:
            -${weak} one -> a
            """
        )
        val kept = check(rules, m, Output(mapOf("x.h" to members(fields = setOf("a", "many", "own")))), compiled)
        assertEquals(listOf("$holder.one", "$holder.many", "$holder.own"), kept.weakFields)
        assertTrue(kept.problems.toString(), kept.problems.isEmpty())

        val removed = check(rules, m, Output(mapOf("x.h" to members(fields = setOf("count")))), compiled)
        assertEquals(
            listOf(
                "$holder.one: removed, yet it holds $weak, which what it is registered with keeps only weakly",
                "$holder.many: removed, yet it holds $weak, which what it is registered with keeps only weakly",
                "$holder.own: removed, yet it holds $ownListener, which what it is registered with keeps only weakly"
            ),
            removed.problems
        )
    }

    @Test
    fun aClassR8RemovedHoldsNothing() {
        val findings = check(Rules(emptyList(), setOf(weak)), Mapping(emptyMap()), Output(emptyMap()), listOf(compiled(Holder::class.java)))
        assertTrue(findings.problems.isEmpty() && findings.weakFields.isEmpty())
    }
}
