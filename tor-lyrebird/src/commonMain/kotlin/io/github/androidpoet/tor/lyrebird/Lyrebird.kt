package io.github.androidpoet.tor.lyrebird

import io.github.androidpoet.tor.PluggableTransport

/**
 * [Lyrebird](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird),
 * the Tor Project's pluggable-transport client, bundled for Android and the JVM.
 *
 * It speaks the transports Tor Browser ships: `obfs4`, `webtunnel`, `meek_lite`
 * and `snowflake`, plus the retired `obfs2`, `obfs3` and `scramblesuit`. Pass
 * `Lyrebird.transport(...)` (platform-specific) to [io.github.androidpoet.tor.TorConfig]
 * together with bridge lines that name one of those transports.
 *
 * The binary is built from the upstream source tag `lyrebird-` + [VERSION] when
 * this artifact is published; it is not downloaded at runtime.
 */
public object Lyrebird {
    /** Upstream lyrebird release bundled in this artifact. */
    public const val VERSION: String = "0.8.1"

    /** Transport names this lyrebird build serves, as a bridge line names them. */
    public val protocols: List<String> =
        listOf("obfs4", "webtunnel", "meek_lite", "snowflake", "obfs3", "obfs2", "scramblesuit")

    internal fun transportAt(executable: String): PluggableTransport = PluggableTransport(protocols, executable)
}
