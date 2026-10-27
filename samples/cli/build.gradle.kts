plugins {
    kotlin("jvm")
    application
}

// A minimal consumer: bootstrap, show progress, fetch check.torproject.org three
// ways (stream, Ktor engine, OkHttp), optionally through obfs4 bridges.
// Run: ./gradlew :samples:cli:run --args="--bridges path/to/bridge-lines.txt"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":tor"))
    implementation(project(":tor-ktor"))
    implementation(project(":tor-okhttp"))
    implementation(project(":tor-lyrebird"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("io.github.androidpoet.tor.sample.MainKt")
}
