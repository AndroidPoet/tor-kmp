pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tor-kmp"

include(":tor")
include(":tor-ktor")
include(":tor-okhttp")
include(":tor-lyrebird")
include(":samples:cli")
