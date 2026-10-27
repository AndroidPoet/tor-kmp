import io.github.androidpoet.tor.Configuration

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
}

// The Rust crate (Arti + the C ABI) in native/. Gradle compiles it per target so
// no binary is ever committed.
val nativeDir = rootProject.file("native")
val cargo =
    File(System.getProperty("user.home"), ".cargo/bin/cargo")
        .takeIf { it.exists() }
        ?.path ?: "cargo"

fun cargoBuild(
    triple: String,
    jni: Boolean,
): TaskProvider<Exec> {
    val name = "cargoBuild" + triple.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + if (jni) "Jni" else ""
    // JNI builds get their own target dir so they never overwrite the plain build.
    val targetDir = if (jni) "target/jni" else "target"
    return tasks.register<Exec>(name) {
        group = "rust"
        workingDir = nativeDir
        commandLine(
            buildList {
                addAll(listOf(cargo, "build", "--release", "--target", triple, "--target-dir", targetDir))
                if (jni) addAll(listOf("--features", "jni"))
            },
        )
        inputs.dir(nativeDir.resolve("src"))
        inputs.files(nativeDir.resolve("Cargo.toml"), nativeDir.resolve("Cargo.lock"))
        outputs.dir(nativeDir.resolve("$targetDir/$triple/release"))
    }
}

// Kotlin/Native target -> Rust triple. Each gets a static libtorkmp.a embedded
// in its klib through cinterop.
val nativeTriples =
    mapOf(
        "macosArm64" to "aarch64-apple-darwin",
    )

// JVM desktop: the host's shared library, bundled as a resource and loaded by
// NativeLibrary.kt. Resource folder names must match NativeLibrary.platformFolder().
data class JvmHost(
    val triple: String,
    val folder: String,
    val fileName: String,
)

val jvmHost: JvmHost? =
    run {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arm = System.getProperty("os.arch").orEmpty().let { it == "aarch64" || it == "arm64" }
        when {
            os.contains("mac") && arm -> JvmHost("aarch64-apple-darwin", "darwin-aarch64", "libtorkmp.dylib")
            os.contains("mac") -> JvmHost("x86_64-apple-darwin", "darwin-x86-64", "libtorkmp.dylib")
            os.contains("linux") && arm -> JvmHost("aarch64-unknown-linux-gnu", "linux-aarch64", "libtorkmp.so")
            os.contains("linux") -> JvmHost("x86_64-unknown-linux-gnu", "linux-x86-64", "libtorkmp.so")
            os.contains("windows") -> JvmHost("x86_64-pc-windows-msvc", "windows-x86-64", "torkmp.dll")
            else -> null
        }
    }

val jvmNativeResources =
    jvmHost?.let { host ->
        val build = cargoBuild(host.triple, jni = true)
        tasks.register<Copy>("copyJvmNativeLibrary") {
            group = "rust"
            from(build.map { nativeDir.resolve("target/jni/${host.triple}/release/${host.fileName}") }) {
                into("tor-kmp/native/${host.folder}")
            }
            into(layout.buildDirectory.dir("generated/torNative/jvm"))
        }
    }

kotlin {
    explicitApi()
    jvmToolchain(17)

    compilerOptions {
        // TorBridge is an expect/actual object.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    // jniMain: code shared by the JVM and Android, which both reach Rust through JNI.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    jvm()
    listOf(macosArm64()).forEach { target ->
        val triple = nativeTriples.getValue(target.name)
        val interop =
            target.compilations.getByName("main").cinterops.create("torkmp") {
                defFile(project.file("src/nativeInterop/cinterop/torkmp.def"))
                includeDirs(nativeDir.resolve("include"))
                extraOpts("-libraryPath", nativeDir.resolve("target/$triple/release").path)
            }
        // cinterop embeds libtorkmp.a into the klib, so Rust must build first.
        val rust = cargoBuild(triple, jni = false)
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
            jvmNativeResources?.let { resources.srcDir(it) }
        }
    }
}

mavenPublishing {
    coordinates(Configuration.GROUP, "tor", Configuration.VERSION)
}
