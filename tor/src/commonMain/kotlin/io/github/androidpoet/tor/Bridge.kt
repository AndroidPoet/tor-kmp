package io.github.androidpoet.tor

import io.github.androidpoet.tor.internal.TorBridge
import io.github.androidpoet.tor.internal.windowsPaths

/**
 * A bridge: an unlisted Tor relay for networks that block the public ones. Users
 * get bridge lines from <https://bridges.torproject.org>, the Telegram bot
 * `@GetBridgesBot` or by emailing `bridges@torproject.org`.
 *
 * ```
 * Bridge.parse("obfs4 192.0.2.55:38114 316E…5F5F cert=YXJl… iat-mode=0")
 * Bridge.parse("Bridge 192.0.2.10:443 0123456789ABCDEF0123456789ABCDEF01234567")
 * ```
 *
 * A line that names a [transport] (`obfs4`, `webtunnel`, …) only works together
 * with a [PluggableTransport] that provides it; see [TorConfig].
 */
public class Bridge private constructor(
    /** The bridge line without the leading `Bridge` keyword. */
    public val line: String,
    /** The pluggable-transport name the line starts with, or null for a direct bridge. */
    public val transport: String?,
) {
    override fun equals(other: Any?): Boolean = other is Bridge && other.line == line

    override fun hashCode(): Int = line.hashCode()

    override fun toString(): String = "Bridge($line)"

    public companion object {
        /**
         * Parses one bridge line, with or without the leading `Bridge` keyword.
         *
         * @throws IllegalArgumentException with the reason if [line] is not a bridge line.
         */
        public fun parse(line: String): Bridge {
            val body = line.trim().let { if (it.startsWith(KEYWORD, ignoreCase = true)) it.drop(KEYWORD.length).trim() else it }
            TorBridge.bridgeCheck(body)?.let { reason -> throw IllegalArgumentException(reason) }
            // The first word is an address for a direct bridge, a transport name otherwise.
            val first = body.substringBefore(' ')
            val transport = first.takeUnless { ':' in it || it.startsWith('[') }
            return Bridge(body, transport)
        }

        private const val KEYWORD = "Bridge "
    }
}

/**
 * A pluggable-transport client that Arti launches as a child process and talks
 * to over the PT 1.0 protocol: obfs4, meek, webtunnel (lyrebird), snowflake, …
 *
 * ```
 * PluggableTransport(protocols = listOf("obfs4", "webtunnel"), executable = "/path/to/lyrebird")
 * ```
 *
 * The `tor-lyrebird` artifact bundles lyrebird for Android and the JVM. iOS does
 * not allow child processes, so [TorConfig] rejects transports there.
 *
 * @property protocols transport names the executable serves, each unique.
 * @property executable absolute path of the executable.
 * @property arguments extra command-line arguments.
 * @throws IllegalArgumentException if [protocols] is empty, repeats a name or
 * holds anything but `[A-Za-z0-9_-]+`, or if [executable] is not an absolute path.
 */
public class PluggableTransport(
    public val protocols: List<String>,
    public val executable: String,
    public val arguments: List<String> = emptyList(),
) {
    init {
        require(protocols.isNotEmpty()) { "a transport must name at least one protocol" }
        require(protocols.all { it.matches(PROTOCOL_NAME) }) { "transport names are [A-Za-z0-9_-]+, got $protocols" }
        require(protocols.toSet().size == protocols.size) { "transport names repeat: $protocols" }
        require(executable.isAbsolutePath()) { "executable must be an absolute path, got '$executable'" }
    }

    override fun equals(other: Any?): Boolean =
        other is PluggableTransport && other.protocols == protocols && other.executable == executable && other.arguments == arguments

    override fun hashCode(): Int = (protocols.hashCode() * 31 + executable.hashCode()) * 31 + arguments.hashCode()

    override fun toString(): String = "PluggableTransport($protocols via $executable)"

    private companion object {
        val PROTOCOL_NAME = Regex("[A-Za-z0-9_-]+")
        val WINDOWS_DRIVE = Regex("""^[A-Za-z]:[\\/]""")

        /**
         * A drive or UNC path on Windows, a `/`-rooted one elsewhere. Anything else,
         * including `/x` on Windows, depends on the working directory or current drive.
         */
        fun String.isAbsolutePath(): Boolean =
            if (windowsPaths) startsWith("\\\\") || WINDOWS_DRIVE.containsMatchIn(this) else startsWith('/')
    }
}
