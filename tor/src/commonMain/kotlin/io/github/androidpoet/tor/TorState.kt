package io.github.androidpoet.tor

/**
 * Lifecycle of a [Tor] client, observed through [Tor.state].
 *
 * States carrying data are plain classes, not data classes, so fields can be
 * added later without breaking binary compatibility.
 */
public sealed interface TorState {
    /** Created, not connected yet. [Tor.bootstrap] starts it. */
    public data object Idle : TorState

    /**
     * Connecting to the Tor network.
     *
     * @property progress rough completion from 0.0 to 1.0.
     * @property blockage why progress has stalled, or null while it is moving.
     */
    public class Bootstrapping(
        public val progress: Float,
        public val blockage: Blockage?,
    ) : TorState {
        override fun equals(other: Any?): Boolean =
            other is Bootstrapping && other.progress == progress && other.blockage == blockage

        override fun hashCode(): Int = 31 * progress.hashCode() + blockage.hashCode()

        override fun toString(): String = "Bootstrapping(progress=$progress, blockage=$blockage)"
    }

    /** Connected; streams and the SOCKS proxy carry traffic. */
    public data object Ready : TorState

    /** The last bootstrap failed. [Tor.bootstrap] can be called again. */
    public class Failed(
        public val error: TorException,
    ) : TorState {
        override fun equals(other: Any?): Boolean = other is Failed && other.error == error

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "Failed(error=${error.message})"
    }

    /** [Tor.close] was called; the client cannot be used again. */
    public data object Closed : TorState
}
