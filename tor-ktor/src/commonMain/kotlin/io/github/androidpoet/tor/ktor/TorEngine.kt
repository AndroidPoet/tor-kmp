package io.github.androidpoet.tor.ktor

import io.github.androidpoet.tor.Isolation
import io.github.androidpoet.tor.Tor
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * A Ktor client engine that sends every request through [Tor] directly, with no
 * proxy and no local port. Hostnames are resolved by the exit relay, so DNS
 * cannot leak, `.onion` URLs work, and TLS for `https` runs inside the Tor
 * library, identically on every platform.
 *
 * ```
 * val client = HttpClient(TorEngine) {
 *     engine {
 *         tor = myTor
 *         isolation = Isolation("account-1")   // optional: separate circuits per client
 *     }
 * }
 * client.get("https://check.torproject.org/api/ip")
 * ```
 *
 * Speaks HTTP/1.1 and keeps connections alive between requests to the same
 * host. Supports WebSockets through Ktor's `WebSockets` plugin; not HTTP/2.
 */
public object TorEngine : HttpClientEngineFactory<TorEngineConfig> {
    override fun create(block: TorEngineConfig.() -> Unit): HttpClientEngine = TorHttpEngine(TorEngineConfig().apply(block))
}

/** Settings for [TorEngine]. [tor] is required. */
public class TorEngineConfig : HttpClientEngineConfig() {
    /** The client to send requests through. It is not closed with the engine. */
    public lateinit var tor: Tor

    /**
     * Isolation key for every request from this engine, or null for the default
     * group. Engines with different keys never share Tor circuits.
     */
    public var isolation: Isolation? = null

    /**
     * How long an idle connection is kept for reuse before it is closed.
     * Opening a Tor stream costs seconds, so reuse matters more than with TCP.
     */
    public var keepAliveMillis: Long = DEFAULT_KEEP_ALIVE_MILLIS

    /** Idle connections kept per host. */
    public var maxIdlePerHost: Int = DEFAULT_MAX_IDLE_PER_HOST

    private companion object {
        const val DEFAULT_KEEP_ALIVE_MILLIS = 60_000L
        const val DEFAULT_MAX_IDLE_PER_HOST = 2
    }
}
