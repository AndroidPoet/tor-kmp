package io.github.androidpoet.tor.okhttp

import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * Routes an [OkHttpClient] through [Tor] with no proxy and no local port.
 *
 * ```
 * val client = OkHttpClient.Builder().tor(tor, isolation = "account-1").build()
 * ```
 *
 * OkHttp still does TLS itself (so certificate pinning and the platform trust
 * store keep working); only the TCP layer is replaced by a Tor stream. Hostnames
 * are handed to Tor unresolved, so no DNS query leaves the device and `.onion`
 * hosts work.
 */
public fun OkHttpClient.Builder.tor(
    tor: Tor,
    isolation: String? = null,
): OkHttpClient.Builder =
    dns(TorDns)
        .socketFactory(TorSocketFactory(tor, isolation))
        .proxy(null)

/**
 * A [SocketFactory] whose sockets are Tor streams, from [OkHttpClient.Builder.tor].
 * Pair it with [TorDns] so OkHttp never resolves hostnames locally.
 */
public class TorSocketFactory(
    private val tor: Tor,
    private val isolation: String? = null,
) : SocketFactory() {
    override fun createSocket(): Socket = TorSocket(tor, isolation)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = createSocket(host, port)

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = createSocket(host.hostName, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address.hostName, port)
}

/**
 * A [Dns] that resolves nothing: it returns a placeholder address that carries
 * the hostname, which [TorSocketFactory] hands to Tor. The exit relay resolves it.
 */
public object TorDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, PLACEHOLDER))

    private val PLACEHOLDER = byteArrayOf(0, 0, 0, 0)
}

/**
 * A [Socket] backed by a [TorStream]. Only the socket semantics OkHttp relies on
 * are implemented: connect and read timeouts, close, and the shutdown flags its
 * connection health check reads.
 */
private class TorSocket(
    private val tor: Tor,
    private val isolation: String?,
) : Socket() {
    private var stream: TorStream? = null
    private var endpoint: InetSocketAddress? = null
    private var input: TorInputStream? = null
    private var output: TorOutputStream? = null

    @Volatile private var soTimeoutMillis = 0

    @Volatile private var closed = false

    @Volatile private var inputShutdown = false

    @Volatile private var outputShutdown = false

    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        check(stream == null) { "already connected" }
        val address = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("unsupported address $endpoint")
        // getHostString returns the name we were given, never a resolved address.
        val host = address.hostString
        require(address.isUnresolved || address.address.isAnyLocalAddress) {
            "host was resolved locally to ${address.address}; use TorDns so the hostname reaches Tor"
        }
        stream =
            try {
                runBlocking {
                    // The connect timeout bounds opening the stream, like a TCP connect;
                    // a first-ever bootstrap takes longer than any sane connect timeout.
                    tor.bootstrap()
                    if (timeout > 0) {
                        withTimeout(timeout.toLong()) { tor.connect(host, address.port, isolation) }
                    } else {
                        tor.connect(host, address.port, isolation)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw SocketTimeoutException("Tor connection to $host:${address.port} timed out after $timeout ms")
            } catch (error: Exception) {
                throw IOException("Tor connection to $host:${address.port} failed", error)
            }
        this.endpoint = address
    }

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun isConnected(): Boolean = stream != null

    override fun isClosed(): Boolean = closed

    override fun isInputShutdown(): Boolean = inputShutdown

    override fun isOutputShutdown(): Boolean = outputShutdown

    override fun getInetAddress(): InetAddress? = endpoint?.let { InetAddress.getByAddress(it.hostString, byteArrayOf(0, 0, 0, 0)) }

    override fun getPort(): Int = endpoint?.port ?: 0

    @Synchronized
    override fun getInputStream(): InputStream = input ?: TorInputStream(open(), this).also { input = it }

    @Synchronized
    override fun getOutputStream(): OutputStream = output ?: TorOutputStream(open(), this).also { output = it }

    override fun setSoTimeout(timeout: Int) {
        require(timeout >= 0) { "timeout can't be negative" }
        soTimeoutMillis = timeout
    }

    override fun getSoTimeout(): Int = soTimeoutMillis

    // OkHttp sets these before connecting; a Tor stream has no equivalents.
    override fun setTcpNoDelay(on: Boolean) = Unit

    override fun setKeepAlive(on: Boolean) = Unit

    override fun setReceiveBufferSize(size: Int) = Unit

    override fun setSendBufferSize(size: Int) = Unit

    // A Tor stream has no half-close; the flags are enough for OkHttp to retire the connection.
    override fun shutdownInput() {
        inputShutdown = true
    }

    override fun shutdownOutput() {
        outputShutdown = true
    }

    override fun close() {
        if (closed) return
        closed = true
        input?.abandonPendingRead()
        stream?.close()
    }

    private fun open(): TorStream {
        if (closed) throw SocketException("Socket is closed")
        return stream ?: throw SocketException("Socket is not connected")
    }
}

/**
 * Reads through a private buffer so a read timeout can expire without losing
 * data: the native read keeps running, and the next call picks up its result.
 * OkHttp's connection health check depends on this (it reads with a 1 ms timeout).
 */
private class TorInputStream(
    private val stream: TorStream,
    private val socket: TorSocket,
) : InputStream() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chunk = ByteArray(CHUNK_SIZE)
    private var pending: Deferred<Int>? = null
    private var buffered = 0
    private var position = 0
    private var exhausted = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and BYTE_MASK
    }

    @Synchronized
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (position == buffered && !fill()) return -1
        val count = minOf(length, buffered - position)
        chunk.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    @Synchronized
    override fun available(): Int = buffered - position

    override fun close() = socket.close()

    fun abandonPendingRead() = scope.cancel()

    /** Fills [chunk]; false at end of stream. Throws [SocketTimeoutException] after the socket's read timeout. */
    private fun fill(): Boolean {
        if (exhausted) return false
        val read = pending ?: scope.async { stream.read(chunk) }.also { pending = it }
        val timeout = socket.soTimeout
        val count =
            try {
                runBlocking { if (timeout > 0) withTimeoutOrNull(timeout.toLong()) { read.await() } else read.await() }
            } catch (error: Exception) {
                pending = null
                throw IOException("Tor read failed", error)
            } ?: throw SocketTimeoutException("Read timed out")
        pending = null
        if (count < 0) {
            exhausted = true
            return false
        }
        buffered = count
        position = 0
        return true
    }

    private companion object {
        const val CHUNK_SIZE = 16 * 1024
        const val BYTE_MASK = 0xFF
    }
}

private class TorOutputStream(
    private val stream: TorStream,
    private val socket: TorSocket,
) : OutputStream() {
    override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        try {
            runBlocking { stream.write(buffer, offset, length) }
        } catch (error: Exception) {
            throw IOException("Tor write failed", error)
        }
    }

    override fun close() = socket.close()
}
