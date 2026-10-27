import io.github.androidpoet.tor.Configuration
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
}

// ---------------------------------------------------------------------------
// Rust (native/): Arti + the C ABI, compiled per target by Gradle so no binary
// is ever committed. Every build enables the `jni` feature; Kotlin/Native
// simply never calls those symbols and the app linker drops them.
//
// Needs: rustup targets, cargo-zigbuild + zig (Linux/Windows), cargo-ndk (Android).
//   -Ptor.jvm.allPlatforms=true  bundle every desktop OS in the JVM jar (release)
//   -Ptor.rust.prune=true        delete cargo intermediates after each build (disk)
// ---------------------------------------------------------------------------

val nativeDir = rootProject.file("native")
val rustOut = layout.buildDirectory.dir("rust")
val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo").takeIf { it.exists() }?.path ?: "cargo"
val pruneRust = providers.gradleProperty("tor.rust.prune").map(String::toBoolean).getOrElse(false)
val allJvmPlatforms = providers.gradleProperty("tor.jvm.allPlatforms").map(String::toBoolean).getOrElse(false)

/** Import library the `windows` crate needs at link time; it ships inside the crate. */
val windowsImportLib = "libwindows.0.52.0.a"

fun camel(triple: String) = triple.split('-', '_', '.').joinToString("") { it.replaceFirstChar(Char::uppercase) }

fun cargoRegistryFile(path: String): File? =
    File(System.getProperty("user.home"), ".cargo/registry/src")
        .listFiles()
        ?.map { it.resolve(path) }
        ?.firstOrNull { it.exists() }

val rustBuilds = mutableMapOf<String, TaskProvider<Exec>>()

/** Rust target for Windows; `-Ptor.windows.triple=x86_64-pc-windows-gnullvm` selects the LLVM flavour. */
val windowsTriple: String = providers.gradleProperty("tor.windows.triple").orElse("x86_64-pc-windows-gnu").get()

/** Builds libtorkmp for [triple] once and copies the libraries to build/rust/<triple>. */
fun rustBuild(triple: String): TaskProvider<Exec> =
    rustBuilds.getOrPut(triple) {
        // zig cross-builds Linux and Windows from any host; on a Windows host the
        // windows-gnu library is built natively with the toolchain's own mingw gcc,
        // while windows-gnullvm always goes through zig's clang.
        val hostIsWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val zig = "linux" in triple || ("windows" in triple && (!hostIsWindows || "gnullvm" in triple))
        // Linux binaries target glibc 2.17 so they run on every mainstream distro.
        val cargoTarget = if ("linux-gnu" in triple) "$triple.2.17" else triple
        val outputDir = rustOut.map { it.dir(triple) }
        tasks.register<Exec>("rustBuild${camel(triple)}") {
            group = "rust"
            workingDir = nativeDir
            commandLine(cargo, if (zig) "zigbuild" else "build", "--locked", "--release", "--target", cargoTarget, "--features", "jni")
            inputs.dir(nativeDir.resolve("src"))
            inputs.files(nativeDir.resolve("Cargo.toml"), nativeDir.resolve("Cargo.lock"), nativeDir.resolve(".cargo/config.toml"))
            outputs.dir(outputDir)
            doLast {
                val release = nativeDir.resolve("target/$triple/release")
                val destination = outputDir.get().asFile
                destination.mkdirs()
                listOf("libtorkmp.a", "libtorkmp.dylib", "libtorkmp.so", "torkmp.dll")
                    .map(release::resolve)
                    .filter { it.exists() }
                    .forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }
                if ("windows" in triple) {
                    val importCrate = if ("gnullvm" in triple) "windows_x86_64_gnullvm-0.52.6" else "windows_x86_64_gnu-0.52.6"
                    val importLib = cargoRegistryFile("$importCrate/lib/$windowsImportLib")
                    checkNotNull(importLib) { "$windowsImportLib not found in the cargo registry" }
                    importLib.copyTo(destination.resolve(windowsImportLib), overwrite = true)
                }
                if (pruneRust) nativeDir.resolve("target/$triple").deleteRecursively()
            }
        }
    }

// Android: every ABI in one cargo-ndk run, laid out as jniLibs/<abi>/libtorkmp.so.
val androidAbis =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
        "x86" to "i686-linux-android",
    )
val androidJniLibs = rustOut.map { it.dir("jniLibs") }
val rustBuildAndroid =
    tasks.register<Exec>("rustBuildAndroid") {
        group = "rust"
        workingDir = nativeDir
        commandLine(
            buildList {
                addAll(listOf(cargo, "ndk", "--platform", Configuration.MIN_SDK.toString()))
                androidAbis.keys.forEach { addAll(listOf("-t", it)) }
                addAll(listOf("build", "--locked", "--release", "--features", "jni"))
            },
        )
        inputs.dir(nativeDir.resolve("src"))
        inputs.files(nativeDir.resolve("Cargo.toml"), nativeDir.resolve("Cargo.lock"), nativeDir.resolve(".cargo/config.toml"))
        outputs.dir(androidJniLibs)
        doFirst { environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath) }
        doLast {
            androidAbis.forEach { (abi, triple) ->
                val library = nativeDir.resolve("target/$triple/release/libtorkmp.so")
                library.copyTo(androidJniLibs.get().asFile.resolve("$abi/libtorkmp.so"), overwrite = true)
                if (pruneRust) nativeDir.resolve("target/$triple").deleteRecursively()
            }
        }
    }

// JVM desktop: libraries bundled at tor-kmp/native/<folder>/ and picked at runtime
// by NativeLibrary.kt, whose platformFolder() must produce the same names.
data class JvmPlatform(
    val triple: String,
    val folder: String,
    val fileName: String,
)

val jvmPlatforms =
    listOf(
        JvmPlatform("aarch64-apple-darwin", "darwin-aarch64", "libtorkmp.dylib"),
        JvmPlatform("x86_64-apple-darwin", "darwin-x86-64", "libtorkmp.dylib"),
        JvmPlatform("x86_64-unknown-linux-gnu", "linux-x86-64", "libtorkmp.so"),
        JvmPlatform("aarch64-unknown-linux-gnu", "linux-aarch64", "libtorkmp.so"),
        JvmPlatform(windowsTriple, "windows-x86-64", "torkmp.dll"),
    )
val hostFolder =
    run {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arm = System.getProperty("os.arch").orEmpty().let { it == "aarch64" || it == "arm64" }
        val osName =
            when {
                "mac" in os -> "darwin"
                "windows" in os -> "windows"
                else -> "linux"
            }
        "$osName-${if (arm) "aarch64" else "x86-64"}"
    }
val bundledJvmPlatforms = if (allJvmPlatforms) jvmPlatforms else jvmPlatforms.filter { it.folder == hostFolder }

val jvmNativeResources =
    tasks.register<Sync>("jvmNativeResources") {
        group = "rust"
        bundledJvmPlatforms.forEach { platform ->
            from(rustBuild(platform.triple).map { rustOut.get().file("${platform.triple}/${platform.fileName}") }) {
                into("tor-kmp/native/${platform.folder}")
            }
        }
        into(layout.buildDirectory.dir("generated/torNative/jvm"))
    }

// Kotlin/Native target -> Rust triple; libtorkmp.a is embedded in each klib.
val nativeTriples =
    mapOf(
        "iosArm64" to "aarch64-apple-ios",
        "iosSimulatorArm64" to "aarch64-apple-ios-sim",
        "iosX64" to "x86_64-apple-ios",
        "macosArm64" to "aarch64-apple-darwin",
        "macosX64" to "x86_64-apple-darwin",
        "linuxX64" to "x86_64-unknown-linux-gnu",
        "linuxArm64" to "aarch64-unknown-linux-gnu",
        "mingwX64" to windowsTriple,
    )

kotlin {
    explicitApi()
    jvmToolchain(17)

    compilerOptions {
        // TorBridge is an expect/actual object.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    // jniMain: code shared by the JVM and Android, which both reach Rust through JNI.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release")
        // Common tests need the real native library, so they run on a device
        // (connectedAndroidTest), not as host unit tests.
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        val triple = nativeTriples.getValue(name)
        val rust = rustBuild(triple)
        val interop =
            compilations.getByName("main").cinterops.create("torkmp") {
                defFile(project.file("src/nativeInterop/cinterop/torkmp.def"))
                includeDirs(nativeDir.resolve("include"))
                extraOpts(
                    "-libraryPath",
                    rustOut
                        .get()
                        .dir(triple)
                        .asFile.path,
                )
                if ("windows" in triple) extraOpts("-staticLibrary", windowsImportLib)
            }
        // cinterop embeds the static library into the klib, so Rust must build first.
        tasks.named(interop.interopProcessingTaskName) { dependsOn(rust) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain {
            resources.srcDir(jvmNativeResources)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
        }
    }
}

android {
    namespace = "io.github.androidpoet.tor"
    compileSdk = Configuration.COMPILE_SDK
    ndkVersion = Configuration.NDK_VERSION
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        minSdk = Configuration.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].jniLibs.srcDir(androidJniLibs)
}

// AGP picks jniLibs up by folder, so wire the Rust build in explicitly.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(rustBuildAndroid)
}

// The simulator Kotlin/Native boots for iOS tests; override with -Ptor.simulator=...
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("tor.simulator").orElse("iPhone 17"))
}

mavenPublishing {
    coordinates(Configuration.GROUP, "tor", Configuration.VERSION)
}
