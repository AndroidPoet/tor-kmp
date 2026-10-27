import io.github.androidpoet.tor.Configuration
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
}

// OkHttp runs on the JVM and Android only, so this module has those two targets.
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

    // Both targets share one implementation; there is no platform-specific code.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":tor"))
            api(libs.okhttp)
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
    namespace = "io.github.androidpoet.tor.okhttp"
    compileSdk = Configuration.COMPILE_SDK
    defaultConfig {
        minSdk = Configuration.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

mavenPublishing {
    coordinates(Configuration.GROUP, "tor-okhttp", Configuration.VERSION)
}
