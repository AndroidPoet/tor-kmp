package io.github.androidpoet.tor

import io.github.androidpoet.tor.internal.supportsPluggableTransports

/**
 * How a [Tor] client is set up: where it keeps its files and, optionally, how it
 * reaches the network when the public relays are blocked.
 *
 * ```
 * TorConfig(dataDirectory = "$appFiles/tor")                       // the public Tor network
 * TorConfig(
 *     dataDirectory = "$appFiles/tor",
 *     bridges = userLines.map(Bridge::parse),                       // from bridges.torproject.org
 *     transports = listOf(Lyrebird.transport(context)),              // tor-lyrebird, for obfs4 lines
 * )
 * ```
 *
 * Reusing the same directory across launches makes later bootstraps much faster,
 * because the network directory is cached and the client keeps its guard relays.
 *
 * @property dataDirectory a private, app-owned directory, created owner-only
 * (`0700`) if missing; Arti refuses one that other users can write to. Two
 * running [Tor] instances must not share it. It holds the client's guard choice and keys,
 * which identify the client from one launch to the next, so keep it out of
 * cloud backups: on Android put it under `context.noBackupFilesDir`; on iOS and
 * macOS the library marks it excluded from backup itself.
 * @property bridges relays to connect through instead of the public ones. When
 * the list is not empty, only bridges are used.
 * @property transports pluggable-transport clients for the [bridges] that need
 * one. Every [Bridge.transport] named by a bridge must be provided here.
 * @throws IllegalArgumentException if a bridge names a transport no entry in
 * [transports] provides, if two entries provide the same transport, or if
 * [transports] is not empty on iOS, which cannot run a transport process.
 */
public class TorConfig(
    public val dataDirectory: String,
    public val bridges: List<Bridge> = emptyList(),
    public val transports: List<PluggableTransport> = emptyList(),
) {
    init {
        require(transports.isEmpty() || supportsPluggableTransports) {
            "pluggable transports run as a child process, which iOS does not allow; only direct bridges work there"
        }
        val all = transports.flatMap(PluggableTransport::protocols)
        val provided = all.toSet()
        require(provided.size == all.size) { "more than one PluggableTransport provides ${all.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        val missing = bridges.mapNotNull(Bridge::transport).filterNot(provided::contains).distinct()
        require(missing.isEmpty()) { "bridges need transport(s) $missing but no PluggableTransport provides them" }
    }

    internal val stateDirectory: String get() = "$dataDirectory/state"
    internal val cacheDirectory: String get() = "$dataDirectory/cache"

    /** The bridge settings as the native side reads them, or null for the public network. */
    internal fun settingsJson(): String? {
        if (bridges.isEmpty() && transports.isEmpty()) return null
        val bridgeLines = bridges.joinToString(",") { it.line.jsonQuoted() }
        val transportObjects =
            transports.joinToString(",") { transport ->
                val protocols = transport.protocols.joinToString(",") { it.jsonQuoted() }
                val arguments = transport.arguments.joinToString(",") { it.jsonQuoted() }
                """{"protocols":[$protocols],"path":${transport.executable.jsonQuoted()},"arguments":[$arguments]}"""
            }
        return """{"bridges":[$bridgeLines],"transports":[$transportObjects]}"""
    }

    override fun toString(): String = "TorConfig($dataDirectory, ${bridges.size} bridges, ${transports.size} transports)"
}

/** A JSON string literal for [this], escaping what RFC 8259 requires. */
private fun String.jsonQuoted(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@jsonQuoted) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
