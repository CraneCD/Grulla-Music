import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Default SoundFont: FluidR3 Mono GM (MIT), as distributed with MuseScore 2.3.2.
// Downloaded at build time (checksum pinned) instead of being stored in git.
val soundFontUrl = "https://raw.githubusercontent.com/musescore/MuseScore/2.3.2/share/sound/FluidR3Mono_GM.sf3"
val soundFontSha256 = "cfcd66d89e8386823400eca64934b14fbea7bf48ba1f00d21189af1262794ec2"
val generatedAssets = layout.buildDirectory.dir("generated/soundfont-assets")

val downloadSoundFont = tasks.register("downloadSoundFont") {
    description = "Downloads the default SoundFont into the generated assets."
    val target = generatedAssets.map { it.file("soundfonts/FluidR3Mono_GM.sf3") }
    outputs.file(target)
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val file = target.get().asFile
        if (file.isFile && sha256(file) == soundFontSha256) return@doLast
        file.parentFile.mkdirs()
        val partial = File(file.path + ".part")
        URI(soundFontUrl).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = sha256(partial)
        check(actual == soundFontSha256) { "SoundFont download checksum mismatch: $actual" }
        partial.renameTo(file)
    }
}

android {
    namespace = "com.grulla.music"
    compileSdk = 37
    // Same NDK as :synth; AGP uses it to strip debug symbols from the native libraries.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.grulla.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.1.0"
    }

    signingConfigs {
        // A shared debug key in the repository: debug builds from any machine update each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The release (upload) key stays out of git: keystore.properties next to this file, or
        // the GRULLA_KEYSTORE* environment variables. See README "Release builds".
        val release = releaseKey()
        if (release != null) {
            create("release") {
                storeFile = file(release.getProperty("storeFile"))
                storePassword = release.getProperty("storePassword")
                keyAlias = release.getProperty("keyAlias")
                keyPassword = release.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the build is signed with the debug key, so it can still be installed for testing.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    bundle {
        // Google Play delivers only the device's native libraries (arm64 or armv7)...
        abi { enableSplit = true }
        // ...but every language, so the in-app language switch works offline.
        language { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(generatedAssets.get().asFile.absolutePath)
        }
    }

    testOptions {
        unitTests {
            // Screenshot tests (Robolectric + Roborazzi) render Compose with the real resources and assets.
            isIncludeAndroidResources = true
        }
    }

    androidResources {
        // The SoundFont is already compressed (Ogg Vorbis samples) and is read with openFd().
        noCompress += "sf3"
        // English and Spanish, offered in Android 13+'s per-app language settings.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":synth"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric runs Android 16 (SDK 36) only on Java 21; the build itself stays on JDK 17.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // Robolectric reaches into these JDK internals (FileDescriptor, shared memory).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}

roborazzi {
    // Reference screenshots live in the repository (see app/screenshots/README.md).
    outputDir.set(file("screenshots"))
}

tasks.named("preBuild") { dependsOn(downloadSoundFont) }

/** Release signing from keystore.properties or GRULLA_KEYSTORE, _PASSWORD, _ALIAS, _KEY_PASSWORD; null if neither is set. */
fun releaseKey(): Properties? {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) return Properties().apply { file.inputStream().use { load(it) } }
    val store = System.getenv("GRULLA_KEYSTORE") ?: return null
    return Properties().apply {
        setProperty("storeFile", store)
        setProperty("storePassword", System.getenv("GRULLA_KEYSTORE_PASSWORD") ?: "")
        setProperty("keyAlias", System.getenv("GRULLA_KEY_ALIAS") ?: "upload")
        setProperty("keyPassword", System.getenv("GRULLA_KEY_PASSWORD") ?: System.getenv("GRULLA_KEYSTORE_PASSWORD") ?: "")
    }
}
