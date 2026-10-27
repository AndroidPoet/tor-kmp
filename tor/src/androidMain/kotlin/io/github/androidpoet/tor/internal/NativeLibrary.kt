package io.github.androidpoet.tor.internal

/** The AAR ships libtorkmp.so per ABI in jniLibs; the platform loader finds it. */
internal actual fun loadTorLibrary() {
    System.loadLibrary("torkmp")
}
