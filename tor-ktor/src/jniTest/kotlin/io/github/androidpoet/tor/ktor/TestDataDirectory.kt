package io.github.androidpoet.tor.ktor

internal actual fun testDataDirectory(): String = System.getProperty("java.io.tmpdir") + "/tor-kmp-test-ktor"
