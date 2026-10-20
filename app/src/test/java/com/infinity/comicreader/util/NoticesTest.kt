package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {

    /** `group:name` of every `implementation(...)` dependency the app ships. */
    private fun shippedArtifacts(): List<String> {
        val gradle = File("build.gradle.kts").readText()
        return Regex("""^\s*implementation\("([^:"]+):([^:"]+):[^"]+"\)""", RegexOption.MULTILINE)
            .findAll(gradle).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toList()
    }

    @Test
    fun everyShippedDependencyHasANotice() {
        val artifacts = shippedArtifacts()
        assertTrue("no dependencies parsed", artifacts.size > 5)
        for (a in artifacts) assertNotNull("no notice for $a", Notices.forArtifact(a))
    }

    @Test
    fun noticesNameOnlyShippedDependencies() {
        val shipped = shippedArtifacts().toSet()
        for (n in Notices.all) for (a in n.artifacts) assertTrue("$a is not a dependency", a in shipped)
    }

    @Test
    fun noticesAreComplete() {
        for (n in Notices.all) {
            assertTrue(n.name, n.name.isNotBlank() && n.license.isNotBlank())
            assertTrue(n.name, n.url.startsWith("https://"))
        }
    }

    @Test
    fun bundledLicenseMatchesTheRepository() {
        val bundled = File("src/main/res/raw/license.txt").readText()
        assertEquals(File("../LICENSE").readText(), bundled)
        assertTrue(bundled.contains("Emanata Comic Reader contributors"))
    }

    @Test
    fun reflowJoinsWrappedLinesKeepingParagraphs() {
        val text = "MIT License\r\n\r\nPermission is hereby\n  granted, free\nof charge.\n\n\nTHE SOFTWARE\nIS PROVIDED\n"
        assertEquals(
            "MIT License\n\nPermission is hereby granted, free of charge.\n\nTHE SOFTWARE IS PROVIDED",
            Notices.reflow(text)
        )
    }

    @Test
    fun reflowKeepsEachCopyrightLine() {
        val text = "Copyright (c) 2026 A\nCopyright (c) 2026 B\n\nPermission\nis granted."
        assertEquals("Copyright (c) 2026 A\nCopyright (c) 2026 B\n\nPermission is granted.", Notices.reflow(text))
    }
}
