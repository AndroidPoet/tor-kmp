package io.github.androidpoet.tor

/** Lifecycle of a [Tor] client, observed through [Tor.state]. */
public sealed interface TorState {
    /** Created, not connected yet. [Tor.bootstrap] starts it. */
    public data object Idle : TorState

    /**
     * Connecting to the Tor network.
     *
     * @property progress rough completion from 0.0 to 1.0.
     * @property blockedReason why progress has stalled (for example, the network is
     * unreachable), or null while it is moving.
     */
    public data class Bootstrapping(
        val progress: Float,
        val blockedReason: String?,
    ) : TorState

    /** Connected; streams and the SOCKS proxy carry traffic. */
    public data object Ready : TorState

    /** The last bootstrap failed. [Tor.bootstrap] can be called again. */
    public data class Failed(
        val error: TorException,
    ) : TorState

    /** [Tor.close] was called; the client cannot be used again. */
    public data object Closed : TorState
}
