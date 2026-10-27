package io.github.androidpoet.tor

// java.io.tmpdir is the app's cache directory on Android.
internal actual fun testDataDirectory(): String = System.getProperty("java.io.tmpdir") + "/tor-kmp-test-jni"
