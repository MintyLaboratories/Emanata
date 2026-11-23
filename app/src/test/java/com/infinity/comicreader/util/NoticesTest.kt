package com.infinity.comicreader.util

import com.infinity.comicreader.ai.PreviewPicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
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
    fun noUnrarCodeShipsOrIsNamed() {
        assertTrue(shippedArtifacts().none { it.startsWith("com.github.junrar:") })
        assertTrue(Notices.all.none { it.license.contains("UnRAR") || it.note.orEmpty().contains("UnRAR") })
        val readme = File("../README.md").readText()
        assertFalse(readme.contains("junrar"))
        assertFalse(readme.contains("UnRAR License"))
    }

    @Test
    fun codeBuiltFromSourceIsInTheReadmesLicenseTable() {
        val readme = File("../README.md").readText()
        val built = Notices.all.filter { it.note?.startsWith("Built from source") == true }
        assertEquals(setOf("libavif", "dav1d", "xxHash", "libarchive", "ncnn", "glslang"), built.map { it.name }.toSet())
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
    fun noGooglePlayServicesOrMlKitShips() {
        val google = listOf("com.google.android.gms:", "com.google.mlkit:", "com.google.firebase:", "com.google.android.datatransport:", "com.google.android.odml:")
        val shipped = shippedArtifacts().filter { a -> google.any { a.startsWith(it) } }
        assertTrue("shipped: $shipped", shipped.isEmpty())
        assertFalse(File("../README.md").readText().contains("ML Kit"))
    }

    @Test
    fun theDownloadedTextDetectorsSourceIsStatedInAboutAndReadme() {
        val repository = com.infinity.comicreader.ai.ModelCatalog.DETECTOR_REPOSITORY
        val paddle = Notices.all.single { it.name == "PaddleOCR text detector" }
        assertTrue(paddle.note!!.contains(repository))
        assertTrue(paddle.note!!.contains("third party"))
        assertTrue(File("../README.md").readText().lines().single { it.contains("PP-OCRv5 mobile text detector") }.contains("]($repository)"))
        // Wraparound covers download it, whatever the crop anchor.
        assertTrue(paddle.note!!.contains("the first time a cover needs it (telling a wraparound cover's front or reading direction)"))
        val flat = File("../README.md").readText().replace(Regex("\\s+"), " ")
        assertTrue(flat.contains("the first time a cover needs it, the 2.4 MB cover text detector (to tell a wraparound cover's front or a book's reading direction)"))
        // Neither names a Smart crop anchor.
        val smart = Regex("\\bSmart (crop|anchor|\\(experimental\\))")
        assertFalse(smart.containsMatchIn(paddle.note!!))
        assertFalse(smart.containsMatchIn(flat))
    }

    @Test
    fun theImportsTestPicturesAreAttributedInAboutAndReadme() {
        val shipped = File("src/main/assets/import-preview").list()!!.map { "import-preview/$it" }.toSet()
        assertEquals(PreviewPicture.entries.map { it.asset }.toSet(), shipped)
        val readme = File("../README.md").readText()
        val revoy = Notices.all.single { it.name == "Pepper&Carrot artwork" }
        assertEquals("CC BY 4.0", revoy.license)
        assertTrue(revoy.note!!.contains("By David Revoy") && revoy.note.contains("JPEG-compressed"))
        assertTrue(readme.contains("by David Revoy ([Pepper&Carrot](https://www.peppercarrot.com/)), scaled down, the second JPEG-compressed | CC BY 4.0 |"))
        val dots = Notices.all.single { it.url == Notices.PREVIEW_DOTS_URL }
        assertEquals("CC BY 2.0", dots.license)
        assertTrue(dots.note!!.contains("By the Seattle Municipal Archives"))
        assertTrue(readme.contains("](${Notices.PREVIEW_DOTS_URL}) by the Seattle Municipal Archives, at its scanned size, in gray | CC BY 2.0 |"))
        assertTrue(File(assetDir, "cc-by-2.0.txt").readText().startsWith("Attribution 2.0 Generic"))
    }

    @Test
    fun noticesAreComplete() {
        for (n in Notices.all) {
            assertTrue(n.name, n.name.isNotBlank() && n.license.isNotBlank())
            assertTrue(n.name, n.url.startsWith("https://"))
        }
    }

    private val notice by lazy { File("src/main/res/raw/license.txt").readText() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** [markdown] as plain text: hard line breaks and links dropped, whitespace runs as one space. */
    private fun flat(markdown: String): String =
        markdown.replace("\\\n", " ").replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1").replace(Regex("\\s+"), " ")

    @Test
    fun theRepositoryLicenseIsTheUnmodifiedGplVersion3() {
        val license = File("../LICENSE").readText().replace("\r\n", "\n")
        // SHA-256 of https://www.gnu.org/licenses/gpl-3.0.txt
        assertEquals("3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", sha256(license))
        assertEquals(license, File(assetDir, "gpl-3.0.txt").readText().replace("\r\n", "\n"))
        assertFalse(File("../LICENSE-EXCEPTION").exists())
    }

    @Test
    fun upstreamsMitLicenseShipsUnchanged() {
        val upstream = File("../LICENSE-MIT-upstream").readText()
        assertTrue(upstream.startsWith("MIT License\n\nCopyright (c) 2026 ComicReader contributors\n\nPermission is hereby granted"))
        assertFalse(upstream.contains("Emanata"))
        assertEquals(upstream, File(assetDir, "comicreader-mit.txt").readText())
    }

    @Test
    fun theNoticeStatesGplVersion3OrLaterAndUpstreamsMit() {
        assertTrue(notice.startsWith("Emanata Comic Reader\nCopyright (C) 2026 Emanata Comic Reader contributors\n\n"))
        assertTrue(notice.contains("either version 3 of the License, or (at your option) any later version."))
        assertTrue(notice.contains("WITHOUT ANY WARRANTY"))
        assertTrue(notice.trimEnd().endsWith(
            "Parts of this program come from ComicReader, Copyright (c) 2026 ComicReader contributors, under the MIT License."
        ))
        assertFalse(notice.contains("additional permission", ignoreCase = true))
    }

    @Test
    fun theReadmesLicenseSectionCarriesTheNotice() {
        val section = File("../README.md").readText().substringAfter("\n## License\n").substringBefore("\nThird-party components")
        val readme = flat(section)
        for (p in Notices.reflow(notice).split("\n\n")) assertTrue(p, readme.contains(flat(p).removeSuffix(".")))
        assertTrue(section.contains("(see [LICENSE-MIT-upstream](LICENSE-MIT-upstream))"))
        assertFalse(readme.contains("released under the MIT License"))
    }

    @Test
    fun theAppsLicenseIsTheNoticeThenTheGplThenUpstreamsMit() {
        val text = Notices.appLicense(notice) { File(assetDir, it).readText() }
        val order = listOf(
            "Copyright (C) 2026 Emanata Comic Reader contributors",
            "GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007",
            "END OF TERMS AND CONDITIONS",
            "MIT License\n\nCopyright (c) 2026 ComicReader contributors\n\nPermission is hereby granted"
        ).map { text.indexOf(it) }
        assertTrue("$order", order.all { it >= 0 } && order == order.sorted())
        assertEquals(2, Regex("-{40}").findAll(text).count())
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

    @Test
    fun theTextDetectorsOriginIsTheArchiveItsRepositoryNames() {
        // The repository's manifest names the v0.3.0 archive; v0.3.1 came out after the repository took its copy.
        val paddle = Notices.all.single { it.name == "PaddleOCR text detector" }.note!!
        val readme = File("../README.md").readText().lines().single { it.contains("PP-OCRv5 mobile text detector") }
        for (text in listOf(paddle, readme)) {
            assertTrue(text, text.contains("v0.3.0 release archive") && text.contains("manifest"))
            assertFalse(text, text.contains("v0.3.1") || text.contains("byte-identical"))
        }
    }
}
