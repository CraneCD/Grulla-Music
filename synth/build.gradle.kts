import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

// FluidSynth (LGPL-2.1-or-later) is used as the official prebuilt Android release:
// separate, unmodified shared libraries that the app only links to. The archive is
// downloaded at build time and checked against a pinned SHA-256.
val fluidSynthVersion = "2.5.7"
val fluidSynthUrl =
    "https://github.com/FluidSynth/fluidsynth/releases/download/v$fluidSynthVersion/fluidsynth-v$fluidSynthVersion-android24.zip"
val fluidSynthSha256 = "ff3e4a524932c40072ca93f6d3b7c92f93da3cfc6affba65c6fbe75d40068dc8"

// The libraries libfluidsynth.so needs at run time (see `readelf -d`); the rest of the
// archive (gio, gobject, libinstpatch, ...) is not packaged.
val fluidSynthLibraries = listOf(
    "libfluidsynth.so", "libglib-2.0.so", "libgthread-2.0.so", "libpcre.so", "liboboe.so",
    "libsndfile.so", "libFLAC.so", "libogg.so", "libopus.so", "libvorbis.so", "libvorbisenc.so",
)

// ABIs to build; override with -Pgrulla.abis=arm64-v8a,x86_64 (e.g. for an emulator).
val abis = providers.gradleProperty("grulla.abis").getOrElse("arm64-v8a,armeabi-v7a").split(",").map { it.trim() }

val fluidSynthDir = layout.buildDirectory.dir("fluidsynth")
val fluidSynthZip = fluidSynthDir.map { it.file("fluidsynth-v$fluidSynthVersion-android24.zip") }
val fluidSynthDist = fluidSynthDir.map { it.dir("dist") }
val fluidSynthJniLibs = fluidSynthDir.map { it.dir("jniLibs") }

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

val downloadFluidSynth = tasks.register("downloadFluidSynth") {
    description = "Downloads the FluidSynth Android release and verifies its checksum."
    outputs.file(fluidSynthZip)
    doLast {
        val zip = fluidSynthZip.get().asFile
        if (zip.isFile && sha256(zip) == fluidSynthSha256) return@doLast
        zip.parentFile.mkdirs()
        val partial = File(zip.path + ".part")
        URI(fluidSynthUrl).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = sha256(partial)
        check(actual == fluidSynthSha256) { "FluidSynth download checksum mismatch: $actual" }
        partial.renameTo(zip)
    }
}

val unpackFluidSynth = tasks.register<Sync>("unpackFluidSynth") {
    description = "Unpacks the FluidSynth headers and the needed shared libraries."
    dependsOn(downloadFluidSynth)
    from(zipTree(fluidSynthZip)) {
        include("include/**")
        abis.forEach { abi -> fluidSynthLibraries.forEach { include("lib/$abi/$it") } }
    }
    into(fluidSynthDist)
}

val stageFluidSynthLibraries = tasks.register<Sync>("stageFluidSynthLibraries") {
    description = "Lays out the FluidSynth libraries as jniLibs/<abi>/ for packaging."
    dependsOn(unpackFluidSynth)
    from(fluidSynthDist.map { it.dir("lib") })
    into(fluidSynthJniLibs)
}

android {
    namespace = "com.grulla.music.synth"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += abis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DFLUIDSYNTH_DIR=${fluidSynthDist.get().asFile.absolutePath}")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(fluidSynthJniLibs.get().asFile.absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") { dependsOn(stageFluidSynthLibraries) }
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(unpackFluidSynth)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
}
