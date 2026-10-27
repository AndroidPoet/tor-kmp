package io.github.androidpoet.tor

internal actual fun testDataDirectory(): String = System.getProperty("java.io.tmpdir") + "/tor-kmp-test-jvm"
