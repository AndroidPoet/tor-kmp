package io.github.androidpoet.tor

/**
 * Why bootstrapping cannot make progress, from [TorState.Bootstrapping.blockage].
 *
 * @property kind a stable category to branch on.
 * @property message Arti's explanation, for logs and support screens.
 */
public class Blockage(
    public val kind: BlockageKind,
    public val message: String,
) {
    override fun equals(other: Any?): Boolean = other is Blockage && other.kind == kind && other.message == message

    override fun hashCode(): Int = 31 * kind.hashCode() + message.hashCode()

    override fun toString(): String = "Blockage($kind: $message)"
}

/** Categories of [Blockage], mirroring Arti's `BlockageKind`. */
public enum class BlockageKind {
    /** The client was told not to connect (dormant). */
    DISABLED,

    /** No network connectivity at all. */
    OFFLINE,

    /** Connections are made but Tor traffic seems filtered; a bridge may help. */
    FILTERING,

    /** Cannot reach the Tor network, reason unclear. */
    CANT_REACH_TOR,

    /** The device clock is wrong, so directory signatures do not validate. */
    CLOCK_SKEWED,

    /** The network directory cannot be fetched or is unusable. */
    CANT_BOOTSTRAP,

    /** A kind this version does not know; read [Blockage.message]. */
    OTHER,
    ;

    internal companion object {
        /** Maps the native code from `torkmp_blocked_kind`; 0 means not blocked. */
        fun fromCode(code: Int): BlockageKind? = if (code == 0) null else entries.getOrNull(code - 1) ?: OTHER
    }
}
