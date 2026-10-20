package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {

    /** `group:name` of every module in the resolved release classpath, written by `releaseModules`. */
    private fun shippedArtifacts(): List<String> =
        File("build/notices/release-modules.txt").readLines().filter { it.isNotBlank() }

    @Test
    fun everyShippedModuleHasANotice() {
        val artifacts = shippedArtifacts()
        assertTrue("no modules listed", artifacts.size > 20)
        assertTrue("transitive modules missing", "org.apache.commons:commons-lang3" in artifacts)
        for (a in artifacts) assertNotNull("no notice for $a", Notices.forArtifact(a))
    }

    @Test
    fun noticesNameOnlyShippedModules() {
        val shipped = shippedArtifacts()
        for (n in Notices.all) for (p in n.artifacts) {
            assertTrue("$p matches no shipped module", shipped.any { Notice.matches(p, it) })
        }
    }

    @Test
    fun patternsMatchExactGroupOrSubgroups() {
        assertTrue(Notice.matches("a.b:c", "a.b:c"))
        assertFalse(Notice.matches("a.b:c", "a.b:cd"))
        assertTrue(Notice.matches("a.b:*", "a.b:anything"))
        assertFalse(Notice.matches("a.b:*", "a.bc:x"))
        assertFalse(Notice.matches("a.b:*", "a.b.c:x"))
        assertTrue(Notice.matches("a.*", "a:x"))
        assertTrue(Notice.matches("a.*", "a.b.c:x"))
        assertFalse(Notice.matches("a.*", "ab:x"))
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
