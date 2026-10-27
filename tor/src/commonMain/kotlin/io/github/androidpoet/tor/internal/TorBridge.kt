package io.github.androidpoet.tor.internal

/**
 * One-to-one view of the Rust C ABI (native/include/torkmp.h). Handles are raw
 * pointers stored as [Long]. Every call blocks, and every failure throws
 * [io.github.androidpoet.tor.TorException].
 *
 * Callers must keep a handle alive for the duration of a call; [NativeHandle]
 * does that.
 */
internal expect object TorBridge {
    fun create(
        stateDir: String,
        cacheDir: String,
    ): Long

    fun bootstrap(tor: Long)

    fun cancelBootstrap(tor: Long)

    fun progress(tor: Long): Float

    fun blockedReason(tor: Long): String?

    /** 0 = not blocked; see BlockageKind.fromCode for the others. */
    fun blockedKind(tor: Long): Int

    fun newIdentity(tor: Long)

    fun connect(
        tor: Long,
        host: String,
        port: Int,
        isolation: String?,
    ): Long

    fun connectTls(
        tor: Long,
        host: String,
        port: Int,
        isolation: String?,
    ): Long

    /** Returns the byte count, or -1 at end of stream. */
    fun read(
        stream: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun write(
        stream: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    fun shutdownStream(stream: Long)

    fun freeStream(stream: Long)

    fun startSocks(
        tor: Long,
        port: Int,
    ): Int

    fun socksIpConnects(tor: Long): Long

    fun stopSocks(tor: Long)

    fun destroy(tor: Long)
}
