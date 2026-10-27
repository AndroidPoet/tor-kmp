import io.github.androidpoet.tor.Configuration
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
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
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // jniTest: the JVM and Android tests share their test-directory actual.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":tor"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.http.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
        }
    }
}

android {
    namespace = "io.github.androidpoet.tor.ktor"
    compileSdk = Configuration.COMPILE_SDK
    defaultConfig {
        minSdk = Configuration.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("tor.simulator").orElse("iPhone 17"))
}

mavenPublishing {
    coordinates(Configuration.GROUP, "tor-ktor", Configuration.VERSION)
}
