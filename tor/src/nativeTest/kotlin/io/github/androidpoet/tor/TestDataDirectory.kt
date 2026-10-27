package io.github.androidpoet.tor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun testDataDirectory(): String = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/') + "/tor-kmp-test-native"
