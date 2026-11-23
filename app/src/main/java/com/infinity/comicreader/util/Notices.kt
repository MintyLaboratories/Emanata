package com.infinity.comicreader.util

import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelSource

/**
 * A third-party component shipped in the app and the license it is used under.
 * [texts] are the license and NOTICE files shown for it, under `assets/licenses/`;
 * [note] is shown above them. [artifacts] are the Gradle modules it covers:
 * `group:name`, `group:*` for every module of a group, or `group.*` for a group and
 * all its subgroups. Native code bundled inside another artifact has none.
 */
data class Notice(
    val name: String,
    val license: String,
    val url: String,
    val texts: List<String>,
    val artifacts: List<String> = emptyList(),
    val note: String? = null
) {
    fun covers(coordinate: String): Boolean = artifacts.any { matches(it, coordinate) }

    companion object {
        fun matches(pattern: String, coordinate: String): Boolean {
            val group = coordinate.substringBefore(':')
            return when {
                pattern.endsWith(".*") -> group == pattern.dropLast(2) || group.startsWith(pattern.dropLast(1))
                pattern.endsWith(":*") -> group == pattern.dropLast(2)
                else -> pattern == coordinate
            }
        }
    }
}

/** Every third-party component in the app, as listed on the About screen. */
object Notices {
    const val APACHE_2 = "Apache License 2.0"
    const val MIT = "MIT License"
    const val BSD_2 = "BSD 2-Clause License"
    const val BSD_3 = "BSD 3-Clause License"
    const val DUAL_APACHE = "$APACHE_2 (dual-licensed; used under Apache-2.0)"
    const val GLSLANG = "$BSD_3 and others"

    const val UPSTREAM_URL = "https://github.com/infinity6901/ComicReader"
    const val SOURCE_URL = "https://github.com/MintyLaboratories/EmanataComicReader"
    const val TIP_URL = "https://ko-fi.com/mmeminty"

    /** Where the dot-pattern test picture of an import's before and after is from. */
    const val PREVIEW_DOTS_URL = "https://www.flickr.com/photos/seattlemunicipalarchives/44998418715/"

    /** Folder under `assets/` holding the license and NOTICE files. */
    const val ASSET_DIR = "licenses"

    /**
     * The app's own license texts under [ASSET_DIR], shown after its notice (`res/raw/license.txt`):
     * the GNU GPL version 3, then upstream ComicReader's MIT license.
     */
    val appTexts = listOf("gpl-3.0.txt", "comicreader-mit.txt")

    private val RULE = "\n\n${"-".repeat(40)}\n\n"

    private const val APACHE_TEXT = "apache-2.0.txt"
    private val APACHE = listOf(APACHE_TEXT)
    private fun withApache(notice: String) = listOf(notice, APACHE_TEXT)

    val all = listOf(
        Notice("AndroidX", APACHE_2, "https://developer.android.com/jetpack/androidx", APACHE, listOf("androidx.*")),
        Notice(
            "Material Components for Android", APACHE_2,
            "https://github.com/material-components/material-components-android", APACHE,
            listOf("com.google.android.material:material")
        ),
        Notice(
            "Kotlin standard library", APACHE_2, "https://github.com/JetBrains/kotlin",
            withApache("kotlin-notice.txt"), listOf("org.jetbrains.kotlin:*")
        ),
        Notice(
            "kotlinx.coroutines", APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines", APACHE,
            listOf("org.jetbrains.kotlinx:*")
        ),
        Notice(
            "JetBrains Java Annotations", APACHE_2, "https://github.com/JetBrains/java-annotations", APACHE,
            listOf("org.jetbrains:annotations")
        ),
        Notice("JSpecify", APACHE_2, "https://jspecify.dev/", APACHE, listOf("org.jspecify:jspecify")),
        Notice(
            "Error Prone annotations", APACHE_2, "https://github.com/google/error-prone", APACHE,
            listOf("com.google.errorprone:error_prone_annotations")
        ),
        Notice(
            "Guava ListenableFuture", APACHE_2, "https://github.com/google/guava", APACHE,
            listOf("com.google.guava:listenablefuture")
        ),
        Notice(
            "Apache Commons Compress", APACHE_2, "https://commons.apache.org/proper/commons-compress/",
            withApache("commons-compress-notice.txt"), listOf("org.apache.commons:commons-compress")
        ),
        Notice(
            "Apache Commons IO", APACHE_2, "https://commons.apache.org/proper/commons-io/",
            withApache("commons-io-notice.txt"), listOf("commons-io:commons-io")
        ),
        Notice(
            "Apache Commons Lang", APACHE_2, "https://commons.apache.org/proper/commons-lang/",
            withApache("commons-lang3-notice.txt"), listOf("org.apache.commons:commons-lang3")
        ),
        Notice(
            "Apache Commons Codec", APACHE_2, "https://commons.apache.org/proper/commons-codec/",
            withApache("commons-codec-notice.txt"), listOf("commons-codec:commons-codec")
        ),
        Notice(
            "XZ for Java", "Public domain", "https://tukaani.org/xz/java.html", listOf("xz-java.txt"),
            listOf("org.tukaani:xz")
        ),
        Notice("SLF4J", MIT, "https://www.slf4j.org/", listOf("slf4j.txt"), listOf("org.slf4j:*")),
        Notice(
            "jxl-coder", DUAL_APACHE, "https://github.com/awxkee/jxl-coder", APACHE,
            listOf("io.github.awxkee:jxl-coder"), note = "Also offered under the BSD 3-Clause License."
        ),
        Notice("libjxl", BSD_3, "https://github.com/libjxl/libjxl", listOf("libjxl.txt"), note = "Bundled in jxl-coder."),
        Notice("skcms", BSD_3, "https://skia.googlesource.com/skcms/", listOf("skcms.txt"), note = "Bundled in jxl-coder."),
        Notice(
            "Highway", DUAL_APACHE, "https://github.com/google/highway", APACHE,
            note = "Bundled in jxl-coder. Also offered under the BSD 3-Clause License."
        ),
        Notice("Brotli", MIT, "https://github.com/google/brotli", listOf("brotli.txt"), note = "Bundled in jxl-coder."),
        Notice("Little CMS", MIT, "https://github.com/mm2/Little-CMS", listOf("lcms2.txt"), note = "Bundled in jxl-coder."),
        Notice(
            "libpng", "PNG Reference Library License version 2", "https://github.com/pnggroup/libpng",
            listOf("libpng.txt"),
            note = "Bundled in jxl-coder, with the APNG patch under the same license: " +
                "Portions Copyright (c) 2006-2007 Andrew Smith, Copyright (c) 2008-2024 Max Stepin."
        ),
        Notice("GIFLIB", MIT, "https://sourceforge.net/projects/giflib/", listOf("giflib.txt"), note = "Bundled in jxl-coder."),
        Notice(
            "Eigen", "Mozilla Public License 2.0", "https://gitlab.com/libeigen/eigen", listOf("mpl-2.0.txt"),
            note = "Bundled in jxl-coder. Source code: https://gitlab.com/libeigen/eigen"
        ),
        Notice(
            "Android NDK cpufeatures", BSD_2, "https://android.googlesource.com/platform/ndk/",
            listOf("cpufeatures.txt"), note = "Bundled in jxl-coder."
        ),
        Notice(
            "Rust standard library and crates", DUAL_APACHE, "https://www.rust-lang.org/", APACHE,
            note = "Bundled in jxl-coder. Crates: addr2line, gimli, libc, miniz_oxide, rustc-demangle " +
                "(MIT or Apache-2.0); novtb, pic-scale, pxfm (BSD-3-Clause or Apache-2.0)."
        ),
        Notice(
            "LLVM C++ and OpenMP runtimes", "$APACHE_2 with LLVM Exceptions", "https://llvm.org/",
            listOf(APACHE_TEXT, "llvm-exception.txt"),
            note = "libc++abi and libunwind, bundled in jxl-coder; libc++, libc++abi and the OpenMP runtime, linked into the app's ncnn library."
        ),
        Notice(
            "libavif", BSD_2, "https://github.com/AOMediaCodec/libavif", listOf("libavif.txt"),
            note = "Built from source for AVIF pages, with parts of libyuv (BSD 3-Clause License, in the same text)."
        ),
        Notice(
            "dav1d", BSD_2, "https://code.videolan.org/videolan/dav1d", listOf("dav1d.txt"),
            note = "Built from source; decodes the AV1 data of AVIF pages."
        ),
        Notice(
            "xxHash", BSD_2, "https://github.com/Cyan4973/xxHash", listOf("xxhash.txt"),
            note = "Built from source; its XXH3-64 hash makes Book IDs."
        ),
        Notice(
            "libarchive", BSD_2, "https://github.com/libarchive/libarchive", listOf("libarchive.txt"),
            note = "Built from source; reads RAR archives (RAR 4 and 5) with its own code. Parts under the " +
                "BSD 3-Clause License, CC0 and in the public domain, in the same text."
        ),
        Notice(
            "ncnn", BSD_3, "https://github.com/Tencent/ncnn", listOf("ncnn.txt"),
            note = "Built from source; runs AI models on the GPU (Vulkan) or the CPU. Parts under the zlib and " +
                "BSD 2-Clause licenses, and its CPU feature check (ruapu) under the MIT License, in the same text."
        ),
        Notice(
            "glslang", GLSLANG, "https://github.com/KhronosGroup/glslang", listOf("glslang.txt"),
            note = "Built from source with ncnn, which compiles its GPU programs with it. Parts under the BSD 2-Clause, " +
                "MIT and Apache 2.0 licenses; its generated parser under GPL 3.0 with the Bison exception, which lets " +
                "it be used under any terms; its preprocessor also under an NVIDIA license. All in the same text."
        ),
        Notice(
            "AMD FidelityFX Super Resolution 1", MIT, "https://github.com/GPUOpen-Effects/FidelityFX-FSR",
            listOf("fidelityfx-fsr.txt"),
            note = "Its RCAS sharpening pass, written anew from AMD's reference, sharpens pages after scaling when " +
                "\"Sharpen after scaling\" is on."
        ),
        Notice("ZXing", APACHE_2, "https://github.com/zxing/zxing", APACHE, listOf("com.google.zxing:core")),
        Notice(
            "SMBJ and ASN-One", APACHE_2, "https://github.com/hierynomus/smbj", APACHE, listOf("com.hierynomus:*")
        ),
        Notice(
            "Bouncy Castle", MIT, "https://www.bouncycastle.org/", listOf("bouncycastle.txt"),
            listOf("org.bouncycastle:*"), note = "The Bouncy Castle Licence, an MIT License; the provider (bcprov), used by SMBJ."
        ),
        Notice(
            "OkHttp and Okio", APACHE_2, "https://square.github.io/okhttp/", APACHE,
            listOf("com.squareup.okhttp3:*", "com.squareup.okio:*"), note = "Used for WebDAV shares."
        ),
        Notice("MBassador", MIT, "https://github.com/bennidi/mbassador", listOf("mbassador.txt"), listOf("net.engio:mbassador")),
        Notice(
            "PaddleOCR text detector", ModelCatalog.DETECTOR_LICENSE, ModelCatalog.DETECTOR_SOURCE, APACHE,
            note = "PP-OCRv5 mobile text detection model by the PaddlePaddle authors, not bundled: downloaded, " +
                "unchanged, the first time a cover needs it (telling a wraparound cover's front or reading " +
                "direction), as a third party's ncnn conversion with fp16 weights from a pinned " +
                "Hugging Face repository (Apache-2.0, ${ModelCatalog.DETECTOR_REPOSITORY}); its weights file is " +
                "from the PaddleOCR-ncnn-CPP v0.3.0 release archive (MIT), as the repository's manifest states. Finds where text " +
                "is on covers, on this device only."
        ),
        Notice("Material Symbols", APACHE_2, "https://github.com/google/material-design-icons", APACHE),
        Notice(
            "Pepper&Carrot artwork", "CC BY 4.0", "https://www.peppercarrot.com/", listOf("cc-by-4.0.txt"),
            note = "By David Revoy. Small cutouts of \"Portrait of Pepper\" (2017) and \"In Bloom\" (2022), scaled " +
                "down, the second JPEG-compressed, are two of the test pictures an imported model's before and after shows."
        ),
        Notice(
            "Water conservation brochure, circa 1985", "CC BY 2.0", PREVIEW_DOTS_URL, listOf("cc-by-2.0.txt"),
            note = "By the Seattle Municipal Archives. A small cutout at its scanned size, in gray, is the test picture with dot " +
                "patterns an imported model's before and after shows."
        ),
    )

    /**
     * The AI models' sources ([ModelSource]), as notices: who made them, the license, which
     * catalog models come from each, downloaded unchanged on demand, and what a conversion made
     * for the app is.
     */
    val models: List<Notice>
        get() = ModelSource.entries.filter { it.models.isNotEmpty() }.map { s ->
            Notice(
                s.title, s.license, s.url, s.texts,
                note = "By ${s.author}. Models: ${modelNames(s)}. Downloaded on demand, unchanged, " +
                    "from ${s.repository} and run on this device only." + (s.conversion?.let { " $it" } ?: "")
            )
        }

    /** [s]'s models by name, a family with levels once, with their names. */
    private fun modelNames(s: ModelSource): String = s.models.map { m ->
        m.level?.let { "${it.familyName} (${it.names.joinToString("/")})" } ?: m.name
    }.distinct().joinToString()

    /** Every license and NOTICE file the app, the notices and the AI models refer to. */
    val textFiles: Set<String> get() = (appTexts + (all + models).flatMap { it.texts }).toSet()

    /** Every file of [notice] in order, each read by [read], separated by rules. */
    fun fullText(notice: Notice, read: (String) -> String): String =
        notice.texts.joinToString(RULE) { read(it).trimEnd() }

    /** The app's [notice], then each of [appTexts] read by [read], each reflowed, separated by rules. */
    fun appLicense(notice: String, read: (String) -> String): String =
        (listOf(notice) + appTexts.map(read)).joinToString(RULE) { reflow(it) }

    /**
     * Joins the hard-wrapped lines of each paragraph of [text]; blank lines separate
     * paragraphs, and each copyright line stays on its own line.
     */
    fun reflow(text: String): String =
        text.replace("\r\n", "\n").trim()
            .split(Regex("\n\\s*\n"))
            .joinToString("\n\n") { para ->
                para.lines().map { it.trim() }.reduce { acc, line ->
                    if (line.startsWith("Copyright")) "$acc\n$line" else "$acc $line"
                }
            }

    /** The notice covering Gradle module `group:name`, or null if none does. */
    fun forArtifact(coordinate: String): Notice? = all.firstOrNull { it.covers(coordinate) }
}
