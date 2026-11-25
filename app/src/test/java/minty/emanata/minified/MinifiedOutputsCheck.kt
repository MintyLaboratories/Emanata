package minty.emanata.minified

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks a minified build's outputs against app/r8-survivors.txt ([check]). Only the Gradle tasks
 * checkBenchMinified and checkReleaseMinified run it, which pass it R8's outputs and the classes
 * R8 read; testDebugUnitTest leaves it out.
 */
class MinifiedOutputsCheck {

    /** The files under the paths Gradle passes in [property]. */
    private fun files(property: String): List<File> =
        checkNotNull(System.getProperty(property)) { "run through ./gradlew checkBenchMinified: $property unset" }
            .split(File.pathSeparator).filter { it.isNotEmpty() }
            .flatMap { File(it).walkTopDown().filter(File::isFile).toList() }

    @Test
    fun r8KeepsWhatCodeReachesUnseen() {
        val outputs = files("emanata.r8.outputs")
        val mapping = outputs.single { it.name == "mapping.txt" }
        val dexes = outputs.filter { it.name.endsWith(".dex") }
        assertTrue("no dex file among R8's outputs", dexes.isNotEmpty())
        val compiled = files("emanata.r8.compiled").filter { it.name.endsWith(".class") }.map { Compiled.read(it.readBytes()) }
        val rules = Rules.parse(File(checkNotNull(System.getProperty("emanata.r8.rules"))).readLines())

        val findings = mapping.useLines { lines -> check(rules, Mapping.read(lines), Output.read(dexes.map(File::readBytes)), compiled) }

        assertTrue("no rules read", rules.survivors.isNotEmpty() && rules.weak.isNotEmpty())
        assertTrue("no field holding ${rules.weak} among ${compiled.size} classes", findings.weakFields.isNotEmpty())
        assertTrue("no native method in the dex files", findings.natives > 0)
        assertTrue(
            "R8 removed or renamed what code reaches unseen (app/r8-survivors.txt):\n" + findings.problems.joinToString("\n") { "  $it" },
            findings.problems.isEmpty()
        )
        println("Checked ${rules.survivors.size} survivors, ${findings.natives} native methods, weakly held fields ${findings.weakFields}")
    }
}
