package io.github.androidpoet.tor.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** A writable scratch directory: `TMPDIR` on Unix, `TEMP`/`TMP` on Windows, `/tmp` as a last resort. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun testDataDirectory(): String {
    val base = listOf("TMPDIR", "TEMP", "TMP").firstNotNullOfOrNull { getenv(it)?.toKString() } ?: "/tmp"
    return base.trimEnd('/', '\\') + "/tor-kmp-test-ktor-native"
}
