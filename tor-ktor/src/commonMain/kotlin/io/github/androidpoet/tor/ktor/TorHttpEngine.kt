package io.github.androidpoet.tor.ktor

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.plugins.websocket.WebSocketExtensionsCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.isSecure
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The engine behind [TorEngine]; one [ConnectionPool] for its lifetime. */
@OptIn(InternalAPI::class)
internal class TorHttpEngine(
    override val config: TorEngineConfig,
) : HttpClientEngineBase("tor-kmp") {
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeoutCapability, WebSocketCapability, WebSocketExtensionsCapability)

    private val pool by lazy {
        ConnectionPool(
            tor = config.tor,
            isolation = config.isolation,
            keepAliveMillis = config.keepAliveMillis,
            maxIdlePerHost = config.maxIdlePerHost,
            scope = this,
        )
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val key = ConnectionKey(host = data.url.host, port = data.url.port, tls = data.url.protocol.isSecure())
        val connection = connect(key, data)
        val requestTime = GMTDate()
        val callScope = CoroutineScope(callContext)
        try {
            writeRequest(data, connection.output, callScope)
            return readResponse(requestTime, data, connection.input, connection.output, callScope) { reusable ->
                // Runs once the body has been fully read, the call failed, or a WebSocket
                // session ended. The call's scope may already be cancelled by then, so this
                // runs on the engine's.
                launch { if (reusable) pool.release(connection) else connection.close() }
            }
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private suspend fun connect(
        key: ConnectionKey,
        data: HttpRequestData,
    ): TorConnection {
        val timeout = data.getCapabilityOrNull(HttpTimeoutCapability)?.connectTimeoutMillis
        if (timeout == null || timeout == HttpTimeoutConfig.INFINITE_TIMEOUT_MS) return pool.acquire(key)
        // The connect timeout bounds opening the stream, not a first-ever bootstrap.
        config.tor.bootstrap()
        return try {
            withTimeout(timeout) { pool.acquire(key) }
        } catch (_: TimeoutCancellationException) {
            throw ConnectTimeoutException(data)
        }
    }

    override fun close() {
        super.close()
        // Our scope is completing; close idle connections on their own job.
        val job = coroutineContext[Job]
        CoroutineScope(dispatcher).launch { pool.close() }.also { job?.invokeOnCompletion { _ -> it.cancel() } }
    }
}
