package io.github.androidpoet.tor.internal

/** JNI bindings; implemented in Rust by native/src/java.rs. */
internal actual object TorBridge {
    init {
        loadTorLibrary()
    }

    @JvmStatic actual external fun create(
        stateDir: String,
        cacheDir: String,
    ): Long

    @JvmStatic actual external fun bootstrap(tor: Long)

    @JvmStatic actual external fun cancelBootstrap(tor: Long)

    @JvmStatic actual external fun progress(tor: Long): Float

    @JvmStatic actual external fun blockedReason(tor: Long): String?

    @JvmStatic actual external fun blockedKind(tor: Long): Int

    @JvmStatic actual external fun newIdentity(tor: Long)

    @JvmStatic actual external fun connect(
        tor: Long,
        host: String,
        port: Int,
        isolation: String?,
    ): Long

    @JvmStatic actual external fun connectTls(
        tor: Long,
        host: String,
        port: Int,
        isolation: String?,
    ): Long

    @JvmStatic actual external fun read(
        stream: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    @JvmStatic actual external fun write(
        stream: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    @JvmStatic actual external fun shutdownStream(stream: Long)

    @JvmStatic actual external fun freeStream(stream: Long)

    @JvmStatic actual external fun startSocks(
        tor: Long,
        port: Int,
        password: String?,
    ): Int

    @JvmStatic actual external fun socksIpConnects(tor: Long): Long

    @JvmStatic actual external fun stopSocks(tor: Long)

    @JvmStatic actual external fun destroy(tor: Long)
}

/** Loads libtorkmp: from the jar on desktop, from the APK's jniLibs on Android. */
internal expect fun loadTorLibrary()
