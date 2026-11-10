package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class NoticesTest {

    /** The app's build directory, as Gradle passes it to the test JVM. */
    private val buildDir by lazy { File(checkNotNull(System.getProperty("emanata.buildDir")) { "run through Gradle: emanata.buildDir unset" }) }

    /** `group:name` of every module in the resolved release classpath, written by `releaseModules`. */
    private fun shippedArtifacts(): List<String> =
        File(buildDir, "notices/release-modules.txt").readLines().filter { it.isNotBlank() }

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
    fun theApkLeavesOutOkHttpsPublicSuffixList() {
        // MPL-2.0 data, unused without cookies; packaging excludes it.
        val jar = File(buildDir, "intermediates/merged_java_res/release/mergeReleaseJavaResource/base.jar")
        assertTrue("no merged resources at $jar", jar.isFile)
        val names = ZipFile(jar).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("okhttp3/internal/publicsuffix/ shipped", names.none { it.startsWith("okhttp3/internal/publicsuffix/") })
    }

    private val assetDir = File("src/main/assets/${Notices.ASSET_DIR}")

    @Test
    fun everyNoticeShipsExistingTexts() {
        for (n in Notices.all) {
            assertTrue("${n.name} has no text", n.texts.isNotEmpty())
            for (t in n.texts) assertTrue("${n.name}: missing $t", File(assetDir, t).isFile)
        }
        assertEquals("unreferenced license files", assetDir.list()!!.toSet(), Notices.textFiles)
    }

    @Test
    fun attributionLicensesCarryTheirCopyright() {
        val attribution = listOf(Notices.MIT, Notices.BSD_2, Notices.BSD_3, "PNG Reference Library License version 2")
        for (n in Notices.all.filter { it.license in attribution }) {
            val text = n.texts.joinToString { File(assetDir, it).readText() }
            assertTrue("${n.name} lacks a copyright line", text.contains("Copyright"))
        }
    }

    @Test
    fun apacheNoticeFilesShowBeforeTheLicense() {
        val compress = Notices.forArtifact("org.apache.commons:commons-compress")!!
        val text = Notices.fullText(compress) { File(assetDir, it).readText() }
        assertTrue(text.startsWith("Apache Commons Compress\nCopyright"))
        assertTrue(text.contains("-".repeat(40)))
        assertTrue(text.contains("Apache License\n                           Version 2.0"))
    }

    @Test
    fun unrarRestrictionIsStatedInAboutAndReadme() {
        assertEquals(Notices.UNRAR_NOTE, Notices.forArtifact("com.github.junrar:junrar")!!.note)
        val readme = File("../README.md").readText().replace(Regex("\\s+"), " ")
        assertTrue(readme.contains(Notices.UNRAR_NOTE))
        assertTrue(Notices.UNRAR_NOTE.contains("may not be used to develop a RAR (WinRAR) compatible archiver"))
    }

    @Test
    fun codeBuiltFromSourceIsInTheReadmesLicenseTable() {
        val readme = File("../README.md").readText()
        val built = Notices.all.filter { it.note?.startsWith("Built from source") == true }
        assertEquals(setOf("libavif", "dav1d", "xxHash", "ncnn", "glslang"), built.map { it.name }.toSet())
        for (n in built) assertTrue("${n.name} not in README", readme.contains("| [${n.name}]("))
    }

    @Test
    fun ncnnNoticeCarriesRuapusMitLicense() {
        val text = File(assetDir, "ncnn.txt").readText()
        assertTrue(text.contains("1. ruapu.h\nCopyright (c) 2024 nihui"))
        assertTrue(text.contains("Copyright (c) 2024 kernelbin"))
        assertTrue(text.contains("Permission is hereby granted, free of charge"))
        assertTrue(Notices.all.single { it.name == "ncnn" }.note!!.contains("ruapu"))
        assertTrue(File("../README.md").readText().contains("and ruapu (MIT)"))
    }

    @Test
    fun mlKitIsMarkedProprietaryWithItsTerms() {
        val mlKit = Notices.forArtifact("com.google.android.gms:play-services-mlkit-text-recognition")!!
        assertEquals(Notices.PROPRIETARY_GOOGLE, mlKit.license)
        assertEquals(Notices.ML_KIT_TERMS_URL, mlKit.url)
        assertTrue(File(assetDir, mlKit.texts[0]).readText().contains(Notices.ML_KIT_TERMS_URL))
        assertTrue(mlKit.note!!.contains("off by default"))
        val readme = File("../README.md").readText()
        val row = readme.lines().single { it.startsWith("| [ML Kit Text Recognition v2](") }
        assertTrue(row, row.contains("| ${Notices.PROPRIETARY_GOOGLE}: [ML Kit Terms of Service](${Notices.ML_KIT_TERMS_URL})"))
        val flat = readme.replace(Regex("\\s+"), " ")
        assertTrue(flat.contains("**Optional ML Kit (off by default):**"))
        assertTrue(flat.contains("Google may collect usage data under the [ML Kit terms](${Notices.ML_KIT_TERMS_URL})"))
        assertTrue(flat.contains("(and, with ML Kit on, for its usage metrics); the app sends nothing else on its own."))
        assertTrue(flat.contains("metrics queued while it was on may still be sent after you turn it off"))
        assertTrue(flat.contains("Skip on metered connections does not hold them back"))
        assertTrue(flat.contains("ML Kit's usage metrics (recorded only while you have ML Kit on)"))
    }

    @Test
    fun theDownloadedTextDetectorsSourceIsStatedInAboutAndReadme() {
        val repository = com.infinity.comicreader.ai.ModelCatalog.DETECTOR_REPOSITORY
        val paddle = Notices.all.single { it.name == "PaddleOCR text detector" }
        assertTrue(paddle.note!!.contains(repository))
        assertTrue(paddle.note!!.contains("third party"))
        assertTrue(File("../README.md").readText().lines().single { it.contains("PP-OCRv5 mobile text detector") }.contains("]($repository)"))
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
