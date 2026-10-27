package io.github.androidpoet.tor.internal

import io.github.androidpoet.tor.TorException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Extracts the bundled libtorkmp for this OS and CPU from the jar and loads it.
 * Resource layout: `tor-kmp/native/<os>-<arch>/<library file>`.
 */
internal actual fun loadTorLibrary() {
    val folder = platformFolder()
    val fileName = System.mapLibraryName("torkmp")
    val resource = "/tor-kmp/native/$folder/$fileName"
    val input =
        TorBridge::class.java.getResourceAsStream(resource)
            ?: throw TorException("tor-kmp has no native library for $folder (looked for $resource)")
    // A private directory per load, so concurrent processes never share a file.
    val directory = Files.createTempDirectory("tor-kmp").toFile()
    val library = File(directory, fileName)
    input.use { Files.copy(it, library.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    library.deleteOnExit()
    directory.deleteOnExit()
    System.load(library.absolutePath)
}

private fun platformFolder(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val osName =
        when {
            os.contains("mac") -> "darwin"
            os.contains("linux") -> "linux"
            os.contains("windows") -> "windows"
            else -> os.replace(' ', '-')
        }
    val archName =
        when (arch) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86-64"
            else -> arch
        }
    return "$osName-$archName"
}
