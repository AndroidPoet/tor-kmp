package io.github.androidpoet.tor

import io.github.androidpoet.tor.internal.NativeHandle
import io.github.androidpoet.tor.internal.TorBridge
import io.github.androidpoet.tor.internal.cancellableBlocking
import io.github.androidpoet.tor.internal.excludeFromBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * An embedded Tor client, running Arti (the Tor Project's Rust implementation)
 * inside your process. No separate Tor app or binary is needed.
 *
 * ```
 * val tor = Tor(TorConfig(dataDirectory = "$appFiles/tor"))
 * val stream = tor.connect("example.com", 80) // bootstraps on first use
 * ```
 *
 * Create one instance per data directory and keep it for as long as you need
 * Tor; bootstrapping is the slow part. [close] it when done.
 */
public class Tor(
    private val config: TorConfig,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<TorState>(TorState.Idle)

    /** Where the client is in its lifecycle, including bootstrap progress. */
    public val state: StateFlow<TorState> = mutableState.asStateFlow()

    private val handle = AtomicReference<NativeHandle?>(null)
    private val closed = AtomicBoolean(false)
    private val bootstrapLock = Mutex()
    private val identityCounter = AtomicLong(0)
    private val socksProxy = AtomicReference<SocksProxy?>(null)
    private val socksLock = Mutex()
    private val socksStops = AtomicLong(0)

    /**
     * Increases by one on every [newIdentity]. Connection pools record it when a
     * connection is made and retire idle connections that no longer match, so a
     * request issued after [newIdentity] does not pick up a pre-rotation
     * connection from the pool. `tor-ktor` and `tor-okhttp` do this for you; a
     * request that had already taken a connection when [newIdentity] ran finishes
     * on it.
     */
    public val identityGeneration: Long
        get() = identityCounter.load()

    /**
     * Connects to the Tor network, suspending until it is ready for traffic.
     * Returns at once if already connected. Progress is published on [state].
     *
     * Cancelling the caller abandons the attempt and returns [state] to
     * [TorState.Idle].
     *
     * @throws TorException if bootstrapping fails or the client is closed.
     */
    public suspend fun bootstrap() {
        if (mutableState.value == TorState.Ready) return
        bootstrapLock.withLock {
            if (mutableState.value == TorState.Ready) return
            val tor = handleOrCreate()
            mutableState.value = TorState.Bootstrapping(progress = 0f, blockage = null)
            try {
                coroutineScope {
                    val progress = launch { publishProgress(tor) }
                    try {
                        cancellableBlocking(onCancel = { tor.use(TorBridge::cancelBootstrap) }) {
                            tor.use(TorBridge::bootstrap)
                        }
                    } finally {
                        progress.cancel()
                    }
                }
                mutableState.value = TorState.Ready
            } catch (cancelled: CancellationException) {
                if (!closed.load()) mutableState.value = TorState.Idle
                throw cancelled
            } catch (error: TorException) {
                throw bootstrapFailure(error)
            } finally {
                // The progress poller may have published after close() set Closed.
                if (closed.load()) mutableState.value = TorState.Closed
            }
        }
    }

    /**
     * Opens a stream to [host]:[port] through Tor, bootstrapping first if needed.
     *
     * [host] is resolved by the exit relay, so no DNS query leaves the device.
     * `.onion` addresses work. Streams with different [isolation] keys never share
     * circuits; use one key per tab or account to keep them unlinkable.
     *
     * The stream outlives [close] of this client; close it yourself.
     *
     * @throws TorException if the connection fails or the client is closed.
     */
    public suspend fun connect(
        host: String,
        port: Int,
        isolation: Isolation? = null,
    ): TorStream = open(host, port, isolation, tls = false)

    @Deprecated("Isolation keys are typed now.", ReplaceWith("connect(host, port, Isolation(isolation))"))
    public suspend fun connect(
        host: String,
        port: Int,
        isolation: String,
    ): TorStream = connect(host, port, Isolation(isolation))

    /**
     * Like [connect], then performs a TLS handshake with [host] as the server name.
     * The certificate is checked against Mozilla's root store; the returned stream
     * carries plaintext and TLS happens inside the library, identically on every
     * platform. A peer that closes without a TLS `close_notify` makes [TorStream.read]
     * throw rather than return -1, so a truncated response cannot pass for a complete
     * one. Read by your protocol's framing (`Content-Length`, chunking) and it only
     * happens for a response the server ends by closing the connection.
     *
     * @throws TorException if the connection or the handshake fails, or the client is closed.
     */
    public suspend fun connectTls(
        host: String,
        port: Int = HTTPS_PORT,
        isolation: Isolation? = null,
    ): TorStream = open(host, port, isolation, tls = true)

    @Deprecated("Isolation keys are typed now.", ReplaceWith("connectTls(host, port, Isolation(isolation))"))
    public suspend fun connectTls(
        host: String,
        port: Int,
        isolation: String,
    ): TorStream = connectTls(host, port, Isolation(isolation))

    private suspend fun open(
        host: String,
        port: Int,
        isolation: Isolation?,
        tls: Boolean,
    ): TorStream {
        require(port in PORT_RANGE) { "port $port is not in $PORT_RANGE" }
        bootstrap()
        val tor = openHandle()
        // Not cancellable mid-flight: a stream that opens after cancellation must
        // still be freed, so wait for it and close it below.
        val stream =
            withContext(Dispatchers.IO + NonCancellable) {
                val key = isolation?.key
                val pointer =
                    tor.use {
                        if (tls) TorBridge.connectTls(it, host, port, key) else TorBridge.connect(it, host, port, key)
                    }
                TorStream(pointer)
            }
        if (!currentCoroutineContext().isActive) {
            stream.close()
            throw CancellationException("connect cancelled")
        }
        return stream
    }

    /**
     * Starts a SOCKS5 proxy on 127.0.0.1 for clients that can only use a proxy,
     * bootstrapping first if needed. [port] 0 picks a free port. Calling it again
     * returns the running proxy, whatever the arguments.
     *
     * Any process on the device can reach a loopback port, so by default the proxy
     * demands a password it generates ([SocksAuth.Generated]); [SocksProxy.url]
     * includes it. Pass [SocksAuth.None] only for a client that cannot send SOCKS
     * credentials, such as a Chromium WebView.
     *
     * @throws TorException if the port cannot be bound or the client is closed.
     */
    public suspend fun startSocksProxy(
        port: Int = 0,
        auth: SocksAuth = SocksAuth.Generated,
    ): SocksProxy {
        require(port == 0 || port in PORT_RANGE) { "port $port is not 0 or in $PORT_RANGE" }
        bootstrap()
        // Drawn before taking the lock, so a slow or failing OS random source cannot
        // hold up every other proxy start and stop.
        val password =
            when (auth) {
                SocksAuth.Generated -> withContext(Dispatchers.IO) { TorBridge.randomBytes(GENERATED_PASSWORD_BYTES).toHex() }
                is SocksAuth.Password -> auth.value
                SocksAuth.None -> null
            }
        // Serialised, so two callers cannot start servers with different passwords and
        // each believe theirs won; the native side only ever keeps the first.
        socksLock.withLock {
            while (true) {
                socksProxy.load()?.let { return it }
                val stopsBefore = socksStops.load()
                val tor = openHandle()
                val bound = withContext(Dispatchers.IO) { tor.use { TorBridge.startSocks(it, port, password) } }
                val proxy = SocksProxy(host = "127.0.0.1", port = bound, password = password, stop = ::stopSocksProxy)
                // A stop that landed between the native start and here killed that server; start again.
                if (socksStops.load() == stopsBefore) {
                    socksProxy.store(proxy)
                    return proxy
                }
            }
        }
    }

    @Deprecated(
        "Authentication is typed now; null meant no authentication.",
        ReplaceWith("startSocksProxy(port, password?.let(SocksAuth::Password) ?: SocksAuth.None)"),
    )
    public suspend fun startSocksProxy(
        port: Int,
        password: String?,
    ): SocksProxy = startSocksProxy(port, password?.let(SocksAuth::Password) ?: SocksAuth.None)

    /**
     * How many connections to the SOCKS proxy named an IP address instead of a
     * hostname. A proxy client that resolves DNS itself leaks every lookup outside
     * Tor; if this number grows while you use such a client, switch clients.
     */
    public val socksIpLiteralConnections: Long
        get() = handle.load()?.takeUnless { it.isClosed }?.use(TorBridge::socksIpConnects) ?: 0L

    /** Stops the SOCKS proxy, ending its connections. No-op if none is running. */
    public fun stopSocksProxy() {
        socksProxy.store(null)
        socksStops.incrementAndFetch()
        handle.load()?.takeUnless { it.isClosed }?.use(TorBridge::stopSocks)
    }

    /**
     * Routes every stream opened from now on over fresh circuits, unlinkable to
     * earlier ones, and forgets all isolation keys. Open streams are left alone;
     * pooled HTTP connections are retired through [identityGeneration].
     *
     * This is circuit rotation only. It does not touch cookies, caches, sessions
     * or anything else your app or a WebView remembers; clear those yourself.
     */
    public fun newIdentity() {
        handle.load()?.takeUnless { it.isClosed }?.use(TorBridge::newIdentity)
        identityCounter.incrementAndFetch()
    }

    /**
     * Shuts the client down: cancels a running bootstrap and stops the SOCKS
     * proxy. Idempotent. Open [TorStream]s keep working until closed.
     */
    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        socksProxy.store(null)
        handle.load()?.close()
        mutableState.value = TorState.Closed
    }

    /** Only called under [bootstrapLock], so two first callers cannot create two clients on one directory. */
    private suspend fun handleOrCreate(): NativeHandle {
        handle.load()?.let { return it }
        if (closed.load()) throw TorException("Tor is closed")
        val created =
            withContext(Dispatchers.IO) {
                val pointer = TorBridge.create(config.stateDirectory, config.cacheDirectory, config.settingsJson())
                // Arti has just created the directory; its guards and keys live there from now on.
                excludeFromBackup(config.dataDirectory)
                NativeHandle(
                    pointer = pointer,
                    name = "Tor",
                    shutdown = { tor ->
                        TorBridge.cancelBootstrap(tor)
                        TorBridge.stopSocks(tor)
                    },
                    free = TorBridge::destroy,
                )
            }
        handle.store(created)
        // close() may have run before the store; it then saw no handle to close.
        if (closed.load()) {
            created.close()
            throw TorException("Tor is closed")
        }
        return created
    }

    /** Records why bootstrap stopped and returns what [bootstrap] should throw. */
    private suspend fun bootstrapFailure(error: TorException): Exception =
        when {
            closed.load() -> error
            // Cancelling the caller makes Arti return an error; report it as cancellation.
            !currentCoroutineContext().isActive -> {
                mutableState.value = TorState.Idle
                CancellationException("bootstrap cancelled", error)
            }
            else -> {
                mutableState.value = TorState.Failed(error)
                error
            }
        }

    private fun openHandle(): NativeHandle = handle.load() ?: throw TorException("Tor is closed")

    private suspend fun publishProgress(tor: NativeHandle) {
        while (true) {
            val kind = BlockageKind.fromCode(tor.use(TorBridge::blockedKind))
            mutableState.value =
                TorState.Bootstrapping(
                    progress = tor.use(TorBridge::progress),
                    blockage = kind?.let { Blockage(it, tor.use(TorBridge::blockedReason).orEmpty()) },
                )
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    internal companion object {
        /** 192 bits of OS randomness, hex-encoded: well inside SOCKS5's 255-byte limit. */
        private const val GENERATED_PASSWORD_BYTES = 24

        val PORT_RANGE = 1..65_535
        const val HTTPS_PORT = 443
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
