import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, loaded from keystore.properties (not committed). If the file
// is absent the release build still compiles, just unsigned -- handy on CI.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.infinity.comicreader"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "minty.emanata"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        vectorDrawables { useSupportLibrary = true }
        // 64-bit ARM devices and x86_64 emulators; 32-bit libraries only add size.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Trace sections (util/Traces.kt) for profiling; off in release builds, where R8 then drops them.
        buildConfigField("boolean", "TRACES", "true")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-release.pro"
            )
            buildConfigField("boolean", "TRACES", "false")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Installs beside a release build instead of replacing it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // Release code with the debug build's checks and benchmarks, debug-signed: installs over
        // the debug build, keeping its data, to time what a release build does (not debuggable).
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Keeps trace sections: the release rules without proguard-release.pro.
            buildConfigField("boolean", "TRACES", "true")
            proguardFiles.clear()
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.getByName("bench") {
        java.srcDir("src/debug/java")
        res.srcDir("src/debug/res")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    // AVIF decoding (libavif, dav1d) and Book IDs (xxHash), fetched and built by CMake (src/main/cpp).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric bitmaps live on the test JVM's heap; the 512 MB default runs out (-PtestHeap, default 2g).
        // A fresh JVM every 10 classes: activities tests never destroy stay listening to app singletons,
        // and Robolectric's legacy graphics never frees their themes: up to ~100 MB per class; 20 such
        // classes filled 2 GB, and pages then failed to decode or convert.
        // Test JVMs in parallel: -PtestForks (default 2). A run past 30 minutes fails instead of hanging.
        unitTests.all {
            it.maxHeapSize = providers.gradleProperty("testHeap").getOrElse("2g")
            it.forkEvery = 10
            it.maxParallelForks = providers.gradleProperty("testForks").map(String::toInt).getOrElse(2)
            it.timeout.set(Duration.ofMinutes(30))
            // Java thread priorities ignored: Windows runs a below-normal thread only when no normal one
            // waits, so on a busy machine the app's background readers (similar books, analysis) stall
            // for minutes; Android's scheduler only weighs them less.
            it.jvmArgs("-XX:-UseThreadPriorities")
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            // OkHttp's public suffix list (MPL-2.0): read only for cookies and topPrivateDomain(), neither used.
            "okhttp3/internal/publicsuffix/**"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Writes the group:name of every module the release APK bundles, one per line;
// NoticesTest checks each one has a notice on the About screen.
val releaseModules = tasks.register("releaseModules") {
    val out = layout.buildDirectory.file("notices/release-modules.txt")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val modules = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}" }
            .distinct().sorted()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(modules.joinToString("\n", postfix = "\n"))
    }
}
// Reads a release or bench APK's dex files: a release APK must hold no android.os.Trace calls
// and none of the probe section names, a bench APK all of them (so the probes stay current).
// assembleRelease and assembleBench run it.
abstract class TraceSectionCheck : DefaultTask() {
    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val traced: Property<Boolean>

    @get:Input
    abstract val probes: ListProperty<String>

    @TaskAction
    fun verify() {
        val apks = loader.get().load(apkDir.get())?.elements?.map { File(it.outputFile) }.orEmpty()
        check(apks.isNotEmpty()) { "No APK in ${apkDir.get()}" }
        val strings = HashSet<String>()
        val traceCalls = HashSet<String>()
        for (apk in apks) ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("""classes\d*\.dex""")) }.forEach { entry ->
                val dex = Dex(zip.getInputStream(entry).use { it.readBytes() })
                strings += dex.strings
                traceCalls += dex.methodRefs().filter { it.first == "Landroid/os/Trace;" }.map { it.second }
            }
        }
        val names = probes.get().filter { it in strings }
        if (traced.get()) {
            val missing = probes.get() - names.toSet()
            check(missing.isEmpty() && traceCalls.isNotEmpty()) {
                "Trace sections missing from ${apks.joinToString { it.name }}: names $missing, android.os.Trace calls $traceCalls"
            }
        } else {
            check(names.isEmpty() && traceCalls.isEmpty()) {
                "Trace sections left in ${apks.joinToString { it.name }}: names $names, android.os.Trace calls $traceCalls"
            }
        }
    }

    /** The string and method tables of one dex file. */
    private class Dex(bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val strings: List<String> = List(buf.getInt(0x38)) { i -> string(buf.getInt(buf.getInt(0x3C) + 4 * i)) }

        /** (class descriptor, method name) of every method the dex refers to. */
        fun methodRefs(): List<Pair<String, String>> {
            val types = buf.getInt(0x44)
            return List(buf.getInt(0x58)) { i ->
                val at = buf.getInt(0x5C) + 8 * i
                strings[buf.getInt(types + 4 * (buf.getShort(at).toInt() and 0xFFFF))] to strings[buf.getInt(at + 4)]
            }
        }

        // MUTF-8 after a ULEB128 length in UTF-16 units; plain UTF-8 decoding suffices for ASCII names.
        private fun string(off: Int): String {
            var at = off
            while (buf.get(at).toInt() and 0x80 != 0) at++
            at++
            var end = at
            while (buf.get(end).toInt() != 0) end++
            return String(buf.array(), at, end - at, Charsets.UTF_8)
        }
    }
}

// Section names from util/Traces.kt callers, one per kind of call site.
val traceProbes = listOf("book open", "freeze copy", "open: book id", "Resize shown", "processed read")

androidComponents {
    onVariants(selector().withBuildType("release")) { variant -> traceCheck(variant, traced = false) }
    onVariants(selector().withBuildType("bench")) { variant -> traceCheck(variant, traced = true) }
}

fun traceCheck(variant: ApplicationVariant, traced: Boolean) {
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val check = tasks.register<TraceSectionCheck>("check${name}TraceSections") {
        apkDir.set(variant.artifacts.get(SingleArtifact.APK))
        loader.set(variant.artifacts.getBuiltArtifactsLoader())
        this.traced.set(traced)
        probes.set(traceProbes)
    }
    tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(check) }
}

tasks.withType<Test>().configureEach {
    inputs.files(releaseModules)
    // NoticesTest reads the Java resources the release APK packs, under this build directory.
    dependsOn("mergeReleaseJavaResource")
    systemProperty("emanata.buildDir", layout.buildDirectory.get().asFile.absolutePath)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Archive support
    implementation("org.apache.commons:commons-compress:1.26.2") // 7z / CB7
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.tukaani:xz:1.9")
    implementation("org.slf4j:slf4j-nop:2.0.18")              // silences SMBJ's SLF4J logging
    // JPEG XL decoding, and the libjxl.so lossless compaction calls (jxl_jni.c): it must
    // stay libjxl 0.12.x, the headers CMakeLists.txt pins, or compaction turns itself off.
    implementation("io.github.awxkee:jxl-coder:2.6.1")
    implementation("com.google.zxing:core:3.5.3")             // barcodes on wraparound covers
    implementation("com.hierynomus:smbj:0.15.0")              // SMB 2/3 network shares
    implementation("com.squareup.okhttp3:okhttp:4.12.0")      // WebDAV network shares
    // PDF uses the platform android.graphics.pdf.PdfRenderer (no dependency)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")    // HTTPS for the test server
}
