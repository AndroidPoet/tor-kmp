package io.github.androidpoet.tor.lyrebird

import io.github.androidpoet.tor.PluggableTransport
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Unpacks the bundled lyrebird for this operating system and CPU into
 * [directory] and returns it as a [PluggableTransport].
 *
 * The default directory is per user and per lyrebird version
 * (`~/.cache/tor-kmp/lyrebird-<version>`). On POSIX systems it is created with
 * owner-only permissions; on Windows it inherits the access list of your user
 * profile, so a custom [directory] there should be one only you can write to.
 * Safe to call on every launch: an existing binary is reused only if its
 * SHA-256 matches the bundled one, so a tampered or stale file is replaced.
 * Supported: macOS and Linux on x86_64 and arm64, Windows on x86_64.
 *
 * @throws IllegalStateException if this platform has no bundled binary, the
 * directory is not safe to use (a symlink, owned by someone else, or its owner
 * cannot be read), or the file cannot be written or marked executable.
 */
public fun Lyrebird.transport(directory: File = defaultDirectory()): PluggableTransport {
    val platform = jvmPlatform()
    val extension = if (platform.startsWith("windows")) ".exe" else ""
    val resource = "lyrebird/$platform/lyrebird$extension"
    val target = File(directory, "lyrebird-$VERSION$extension")
    prepareDirectory(directory.toPath())
    val bundledDigest = openResource(resource).use(::sha256)
    if (!target.isFile || Files.isSymbolicLink(target.toPath()) || target.inputStream().use(::sha256) != bundledDigest) {
        // Exclusive creation under a random name, then an atomic move: a concurrent
        // launcher never sees a half-written file, and nobody can pre-place one.
        val staging = Files.createTempFile(directory.toPath(), "lyrebird-", ".part")
        try {
            openResource(resource).use { input -> Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(staging, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }
    check(target.setExecutable(true, false) || target.canExecute()) { "cannot mark ${target.absolutePath} executable" }
    return transportAt(target.absolutePath)
}

private fun defaultDirectory(): File = File(System.getProperty("user.home"), ".cache/tor-kmp/lyrebird-${Lyrebird.VERSION}")

private fun openResource(resource: String): InputStream =
    Lyrebird::class.java.classLoader.getResourceAsStream(resource)
        ?: error("tor-lyrebird has no binary for this platform (resource $resource)")

/**
 * Creates [directory] owner-only and refuses one that is a symlink, belongs to
 * another user, or whose owner cannot be established at all.
 */
private fun prepareDirectory(directory: Path) {
    Files.createDirectories(directory)
    check(!Files.isSymbolicLink(directory)) { "$directory is a symbolic link; refusing to unpack an executable there" }
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(
            directory,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        )
    }
    val owner =
        runCatching { Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).name }.getOrNull()
            ?: error("cannot read who owns $directory; refusing to unpack an executable there")
    // Windows reports "MACHINE\name" or "DOMAIN\name"; POSIX reports the bare name.
    val me = System.getProperty("user.name")
    check(owner == me || owner.endsWith("\\$me")) {
        "$directory is owned by $owner, not $me; refusing to unpack an executable there"
    }
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).use { stream ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (stream.read(buffer) >= 0) {
            // reading drives the digest
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** `<os>-<arch>` as the resources are laid out, mirroring Go's GOOS/GOARCH names. */
private fun jvmPlatform(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val goOs =
        when {
            "mac" in os || "darwin" in os -> "darwin"
            "win" in os -> "windows"
            "linux" in os -> "linux"
            else -> error("tor-lyrebird does not bundle a binary for $os")
        }
    val goArch =
        when (arch) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64" -> "amd64"
            else -> error("tor-lyrebird does not bundle a binary for $arch")
        }
    return "$goOs-$goArch"
}

private const val BUFFER_SIZE = 64 * 1024
