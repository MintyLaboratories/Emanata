import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

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
    }

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
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Installs beside a release build instead of replacing it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // AVIF decoding: libavif and dav1d, fetched and built by CMake (src/main/cpp).
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
        // Robolectric bitmaps live on the test JVM's heap; the 512 MB default runs out.
        unitTests.all { it.maxHeapSize = "2g" }
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
tasks.withType<Test>().configureEach {
    inputs.files(releaseModules)
    // NoticesTest reads the Java resources the release APK packs.
    dependsOn("mergeReleaseJavaResource")
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
    implementation("com.github.junrar:junrar:8.1.2")          // RAR / CBR (RAR4 + RAR5/7)
    implementation("org.apache.commons:commons-compress:1.26.2") // 7z / CB7
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.tukaani:xz:1.9")
    implementation("org.slf4j:slf4j-nop:2.0.18")              // silence junrar/slf4j on Android
    implementation("io.github.awxkee:jxl-coder:2.6.1")         // JPEG XL decoding
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
