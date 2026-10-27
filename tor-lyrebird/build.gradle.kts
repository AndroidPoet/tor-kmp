import io.github.androidpoet.tor.Configuration
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
}

// ---------------------------------------------------------------------------
// lyrebird (Go): built from the upstream tag by Gradle, never committed.
// Needs: go on the PATH (or Homebrew's), git, and the Android NDK for the AAR.
//   -Ptor.jvm.allPlatforms=true  bundle every desktop OS in the JVM jar (release)
// ---------------------------------------------------------------------------

val lyrebirdTag = "lyrebird-${Configuration.LYREBIRD_VERSION}"
val lyrebirdRepo = "https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git"
val go = listOf("/opt/homebrew/bin/go", "/usr/local/go/bin/go", "/usr/local/bin/go").firstOrNull { File(it).exists() } ?: "go"
val goVersion =
    providers
        .exec { commandLine(go, "version") }
        .standardOutput.asText
        .map(String::trim)
val allJvmPlatforms = providers.gradleProperty("tor.jvm.allPlatforms").map(String::toBoolean).getOrElse(false)

val lyrebirdSource = layout.buildDirectory.dir("lyrebird-src/$lyrebirdTag")
val lyrebirdOut = layout.buildDirectory.dir("lyrebird")

val fetchLyrebird =
    tasks.register<Exec>("fetchLyrebird") {
        group = "lyrebird"
        description = "Clones lyrebird $lyrebirdTag"
        val target = lyrebirdSource.get().asFile
        onlyIf { !target.resolve("go.mod").exists() }
        outputs.dir(target)
        commandLine("git", "clone", "--quiet", "--depth", "1", "--branch", lyrebirdTag, lyrebirdRepo, target.absolutePath)
    }

/** One `go build` of cmd/lyrebird for [goos]/[goarch] into [output]. */
fun goBuild(
    name: String,
    goos: String,
    goarch: String,
    output: String,
    environment: Map<String, String> = emptyMap(),
    linkerFlags: String = "",
) = tasks.register<Exec>(name) {
    group = "lyrebird"
    dependsOn(fetchLyrebird)
    val outputFile = lyrebirdOut.get().file(output).asFile
    workingDir(lyrebirdSource)
    // Everything that changes the binary is an input, so a toolchain bump rebuilds it.
    inputs.property("tag", lyrebirdTag)
    inputs.property("go", goVersion)
    inputs.property("target", "$goos/$goarch")
    inputs.property("env", environment.toSortedMap().toString())
    inputs.property("ldflags", linkerFlags)
    outputs.file(outputFile)
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("GOFLAGS", "-mod=mod")
    environment("CGO_ENABLED", if (goos == "android") "1" else "0")
    environment.forEach { (key, value) -> environment(key, value) }
    doFirst { outputFile.parentFile.mkdirs() }
    commandLine(go, "build", "-trimpath", "-ldflags", "$linkerFlags-s -w -buildid=", "-o", outputFile.absolutePath, "./cmd/lyrebird")
}

// JVM desktop: resources at lyrebird/<goos>-<goarch>/lyrebird[.exe], read by LyrebirdJvm.kt.
val jvmPlatforms = listOf("darwin-arm64", "darwin-amd64", "linux-amd64", "linux-arm64", "windows-amd64")
val hostPlatform =
    run {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arm = System.getProperty("os.arch").orEmpty().let { it == "aarch64" || it == "arm64" }
        val goos =
            when {
                "mac" in os -> "darwin"
                "windows" in os -> "windows"
                else -> "linux"
            }
        "$goos-${if (arm) "arm64" else "amd64"}"
    }
val bundledJvmPlatforms = if (allJvmPlatforms) jvmPlatforms else listOf(hostPlatform)

// Registered up front: Gradle forbids registering tasks from inside another task's configuration.
val jvmBuilds =
    bundledJvmPlatforms.associateWith { platform ->
        val (goos, goarch) = platform.split('-')
        val fileName = if (goos == "windows") "lyrebird.exe" else "lyrebird"
        goBuild("buildLyrebird${platform.replace("-", "").replaceFirstChar(Char::uppercase)}", goos, goarch, "jvm/$platform/$fileName")
    }

val lyrebirdJvmResources =
    tasks.register<Sync>("lyrebirdJvmResources") {
        group = "lyrebird"
        jvmBuilds.forEach { (platform, build) -> from(build) { into("lyrebird/$platform") } }
        into(layout.buildDirectory.dir("generated/lyrebird/jvm"))
    }

// Android: liblyrebird.so per ABI so the installer extracts it into nativeLibraryDir.
// Go needs the NDK's clang for every Android ABI; -checklinkname=0 is for the
// `anet` dependency, which reaches into the standard library.
data class AndroidAbi(
    val abi: String,
    val goarch: String,
    val clangPrefix: String,
    val goarm: String? = null,
)

val androidAbis =
    listOf(
        AndroidAbi("arm64-v8a", "arm64", "aarch64-linux-android"),
        AndroidAbi("armeabi-v7a", "arm", "armv7a-linux-androideabi", goarm = "7"),
        AndroidAbi("x86_64", "amd64", "x86_64-linux-android"),
        AndroidAbi("x86", "386", "i686-linux-android"),
    )
val ndkHost =
    System.getProperty("os.name").orEmpty().lowercase().let { os ->
        when {
            "mac" in os -> "darwin-x86_64"
            "windows" in os -> "windows-x86_64"
            else -> "linux-x86_64"
        }
    }

val androidBuilds =
    androidAbis.map { target ->
        goBuild(
            name = "buildLyrebirdAndroid${target.abi.replace("-", "").replace("_", "").replaceFirstChar(Char::uppercase)}",
            goos = "android",
            goarch = target.goarch,
            output = "android/${target.abi}/liblyrebird.so",
            environment = target.goarm?.let { mapOf("GOARM" to it) }.orEmpty(),
            linkerFlags = "-checklinkname=0 ",
        ).also { task ->
            task.configure {
                inputs.property("ndk", Configuration.NDK_VERSION)
                doFirst {
                    val clang = "${android.ndkDirectory}/toolchains/llvm/prebuilt/$ndkHost/bin/${target.clangPrefix}${Configuration.MIN_SDK}-clang"
                    environment("CC", clang)
                }
            }
        }
    }

kotlin {
    explicitApi()
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":tor"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain {
            resources.srcDir(lyrebirdJvmResources)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
        }
    }
}

android {
    namespace = "io.github.androidpoet.tor.lyrebird"
    compileSdk = Configuration.COMPILE_SDK
    ndkVersion = Configuration.NDK_VERSION
    defaultConfig {
        minSdk = Configuration.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].jniLibs.srcDir(lyrebirdOut.map { it.dir("android") })
    packaging {
        // An executable, not a shared library: keep it byte-for-byte as Go linked it,
        // and extract it at install time so the instrumented tests can run it.
        // Consumers set useLegacyPackaging in their own app module; a library cannot.
        jniLibs.keepDebugSymbols += "**/liblyrebird.so"
        jniLibs.useLegacyPackaging = true
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(androidBuilds)
}

mavenPublishing {
    coordinates(Configuration.GROUP, "tor-lyrebird", Configuration.VERSION)
}
