package com.infinity.comicreader.util

/**
 * A third-party component shipped in the app and the license it is used under.
 * [artifacts] are the Gradle `group:name` coordinates it covers; native code bundled
 * inside another artifact has none.
 */
data class Notice(
    val name: String,
    val license: String,
    val url: String,
    val artifacts: List<String> = emptyList()
)

/** Every third-party component in the app, as listed on the About screen. */
object Notices {
    const val APACHE_2 = "Apache License 2.0"
    const val MIT = "MIT License"
    const val BSD_3 = "BSD 3-Clause License"

    const val UPSTREAM_URL = "https://github.com/infinity6901/ComicReader"
    const val SOURCE_URL = "https://github.com/MintyLaboratories/EmanataComicReader"

    val all = listOf(
        Notice(
            "AndroidX", APACHE_2, "https://developer.android.com/jetpack/androidx",
            listOf(
                "androidx.core:core-ktx", "androidx.appcompat:appcompat",
                "androidx.constraintlayout:constraintlayout", "androidx.recyclerview:recyclerview",
                "androidx.viewpager2:viewpager2", "androidx.preference:preference-ktx",
                "androidx.activity:activity-ktx", "androidx.lifecycle:lifecycle-runtime-ktx",
                "androidx.documentfile:documentfile"
            )
        ),
        Notice(
            "Material Components for Android", APACHE_2,
            "https://github.com/material-components/material-components-android",
            listOf("com.google.android.material:material")
        ),
        Notice("Kotlin standard library", APACHE_2, "https://github.com/JetBrains/kotlin"),
        Notice(
            "kotlinx.coroutines", APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines",
            listOf("org.jetbrains.kotlinx:kotlinx-coroutines-android")
        ),
        Notice(
            "junrar", "UnRAR License", "https://github.com/junrar/junrar",
            listOf("com.github.junrar:junrar")
        ),
        Notice(
            "Apache Commons Compress", APACHE_2, "https://commons.apache.org/proper/commons-compress/",
            listOf("org.apache.commons:commons-compress")
        ),
        Notice(
            "Apache Commons IO", APACHE_2, "https://commons.apache.org/proper/commons-io/",
            listOf("commons-io:commons-io")
        ),
        Notice("XZ for Java", "Public domain", "https://tukaani.org/xz/java.html", listOf("org.tukaani:xz")),
        Notice("SLF4J", MIT, "https://www.slf4j.org/", listOf("org.slf4j:slf4j-nop")),
        Notice(
            "jxl-coder", "$APACHE_2 or $BSD_3", "https://github.com/awxkee/jxl-coder",
            listOf("io.github.awxkee:jxl-coder")
        ),
        Notice("libjxl", BSD_3, "https://github.com/libjxl/libjxl"),
        Notice("Highway", APACHE_2, "https://github.com/google/highway"),
        Notice("Brotli", MIT, "https://github.com/google/brotli"),
        Notice("Little CMS", MIT, "https://github.com/mm2/Little-CMS"),
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

    /** The notice covering Gradle coordinate `group:name`, or null if none does. */
    fun forArtifact(coordinate: String): Notice? = all.firstOrNull { coordinate in it.artifacts }
}
