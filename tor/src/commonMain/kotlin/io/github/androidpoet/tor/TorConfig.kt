package io.github.androidpoet.tor

/**
 * Where a [Tor] client keeps its files.
 *
 * Reusing the same directory across launches makes later bootstraps much faster,
 * because the network directory is cached and the client keeps its guard relays.
 *
 * @property dataDirectory a private, app-owned directory. Two running [Tor]
 * instances must not share it.
 */
public class TorConfig(
    public val dataDirectory: String,
) {
    internal val stateDirectory: String get() = "$dataDirectory/state"
    internal val cacheDirectory: String get() = "$dataDirectory/cache"
}
