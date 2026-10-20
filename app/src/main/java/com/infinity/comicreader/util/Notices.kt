package com.infinity.comicreader.util

/**
 * A third-party component shipped in the app and the license it is used under.
 * [artifacts] are the Gradle modules it covers: `group:name`, `group:*` for every
 * module of a group, or `group.*` for a group and all its subgroups. Native code
 * bundled inside another artifact has none.
 */
data class Notice(
    val name: String,
    val license: String,
    val url: String,
    val artifacts: List<String> = emptyList()
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

    const val UPSTREAM_URL = "https://github.com/infinity6901/ComicReader"
    const val SOURCE_URL = "https://github.com/MintyLaboratories/EmanataComicReader"

    val all = listOf(
        Notice("AndroidX", APACHE_2, "https://developer.android.com/jetpack/androidx", listOf("androidx.*")),
        Notice(
            "Material Components for Android", APACHE_2,
            "https://github.com/material-components/material-components-android",
            listOf("com.google.android.material:material")
        ),
        Notice("Kotlin standard library", APACHE_2, "https://github.com/JetBrains/kotlin", listOf("org.jetbrains.kotlin:*")),
        Notice("kotlinx.coroutines", APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines", listOf("org.jetbrains.kotlinx:*")),
        Notice("JetBrains Java Annotations", APACHE_2, "https://github.com/JetBrains/java-annotations", listOf("org.jetbrains:annotations")),
        Notice("JSpecify", APACHE_2, "https://jspecify.dev/", listOf("org.jspecify:jspecify")),
        Notice(
            "Error Prone annotations", APACHE_2, "https://github.com/google/error-prone",
            listOf("com.google.errorprone:error_prone_annotations")
        ),
        Notice("Guava ListenableFuture", APACHE_2, "https://github.com/google/guava", listOf("com.google.guava:listenablefuture")),
        Notice("junrar", "UnRAR License", "https://github.com/junrar/junrar", listOf("com.github.junrar:junrar")),
        Notice(
            "Apache Commons Compress", APACHE_2, "https://commons.apache.org/proper/commons-compress/",
            listOf("org.apache.commons:commons-compress")
        ),
        Notice("Apache Commons IO", APACHE_2, "https://commons.apache.org/proper/commons-io/", listOf("commons-io:commons-io")),
        Notice(
            "Apache Commons Lang", APACHE_2, "https://commons.apache.org/proper/commons-lang/",
            listOf("org.apache.commons:commons-lang3")
        ),
        Notice(
            "Apache Commons Codec", APACHE_2, "https://commons.apache.org/proper/commons-codec/",
            listOf("commons-codec:commons-codec")
        ),
        Notice("XZ for Java", "Public domain", "https://tukaani.org/xz/java.html", listOf("org.tukaani:xz")),
        Notice("SLF4J", MIT, "https://www.slf4j.org/", listOf("org.slf4j:*")),
        Notice("jxl-coder", DUAL_APACHE, "https://github.com/awxkee/jxl-coder", listOf("io.github.awxkee:jxl-coder")),
        Notice("libjxl", BSD_3, "https://github.com/libjxl/libjxl"),
        Notice("skcms", BSD_3, "https://skia.googlesource.com/skcms/"),
        Notice("Highway", DUAL_APACHE, "https://github.com/google/highway"),
        Notice("Brotli", MIT, "https://github.com/google/brotli"),
        Notice("Little CMS", MIT, "https://github.com/mm2/Little-CMS"),
        Notice("libpng (with APNG patch)", "PNG Reference Library License version 2", "https://github.com/pnggroup/libpng"),
        Notice("GIFLIB", MIT, "https://sourceforge.net/projects/giflib/"),
        Notice("Eigen", "Mozilla Public License 2.0", "https://gitlab.com/libeigen/eigen"),
        Notice("Android NDK cpufeatures", BSD_2, "https://android.googlesource.com/platform/ndk/"),
        Notice("Rust standard library and crates", DUAL_APACHE, "https://www.rust-lang.org/"),
        Notice("LLVM C++ runtime (libc++abi, libunwind)", "$APACHE_2 with LLVM Exceptions", "https://llvm.org/"),
        Notice("Material Symbols", APACHE_2, "https://github.com/google/material-design-icons"),
    )

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
