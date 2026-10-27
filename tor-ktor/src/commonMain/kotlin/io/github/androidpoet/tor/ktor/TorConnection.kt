package io.github.androidpoet.tor.ktor

import io.github.androidpoet.tor.Isolation
import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorStream
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.reader
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One Tor stream exposed as Ktor channels, usable for several requests in turn. */
internal class TorConnection(
    val key: ConnectionKey,
    private val stream: TorStream,
    scope: CoroutineScope,
    /** [Tor.identityGeneration] when the stream was opened; a later value retires the connection. */
    val identityGeneration: Long,
) {
    /** Bytes from the server. The pump ends when the stream does. */
    val input: ByteReadChannel =
        scope
            .writer(autoFlush = true) {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    channel.writeFully(buffer, 0, count)
                    // Ktor 3 channels hand nothing to the reader until a flush or close.
                    channel.flush()
                }
            }.channel

    /** Bytes to the server. Closing it does not close the stream, so the connection can be reused. */
    val output: ByteWriteChannel =
        scope
            .reader(autoFlush = true) {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    if (count > 0) stream.write(buffer, 0, count)
                }
            }.channel

    val isAlive: Boolean get() = !input.isClosedForRead

    fun close() {
        stream.close()
        output.cancel(null)
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}

/** A host, port and scheme; connections are only reused within one key. */
internal data class ConnectionKey(
    val host: String,
    val port: Int,
    val tls: Boolean,
)

/** Idle connections kept for reuse, per [ConnectionKey]. */
internal class ConnectionPool(
    private val tor: Tor,
    private val isolation: Isolation?,
    private val keepAliveMillis: Long,
    private val maxIdlePerHost: Int,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val idle = mutableMapOf<ConnectionKey, ArrayDeque<Idle>>()

    private class Idle(
        val connection: TorConnection,
        val expiry: Job,
    )

    /** An idle connection for [key] if one is still usable, otherwise a new one. */
    suspend fun acquire(key: ConnectionKey): TorConnection {
        while (true) {
            val reused =
                lock.withLock { idle[key]?.removeFirstOrNull() }
                    ?: break
            reused.expiry.cancel()
            if (reused.connection.isUsable) return reused.connection
            reused.connection.close()
        }
        // Read before connecting: a newIdentity() during the connect must retire this one too.
        val generation = tor.identityGeneration
        val stream =
            if (key.tls) {
                tor.connectTls(key.host, key.port, isolation)
            } else {
                tor.connect(key.host, key.port, isolation)
            }
        return TorConnection(key, stream, scope, generation)
    }

    /** Keeps [connection] for a later request, or closes it when the pool for its host is full. */
    suspend fun release(connection: TorConnection) {
        if (!connection.isUsable) {
            connection.close()
            return
        }
        val entry =
            Idle(
                connection,
                expiry =
                    scope.launch {
                        delay(keepAliveMillis)
                        evict(connection)
                    },
            )
        val overflow =
            lock.withLock {
                val queue = idle.getOrPut(connection.key) { ArrayDeque() }
                queue.addLast(entry)
                if (queue.size > maxIdlePerHost) queue.removeFirst() else null
            }
        overflow?.let {
            it.expiry.cancel()
            it.connection.close()
        }
    }

    /** Alive, and opened since the last [Tor.newIdentity]. */
    private val TorConnection.isUsable: Boolean
        get() = isAlive && identityGeneration == tor.identityGeneration

    private suspend fun evict(connection: TorConnection) {
        val removed =
            lock.withLock {
                idle[connection.key]?.removeAll { it.connection === connection } == true
            }
        if (removed) connection.close()
    }

    suspend fun close() {
        val all = lock.withLock { idle.values.flatten().also { idle.clear() } }
        all.forEach {
            it.expiry.cancel()
            it.connection.close()
        }
    }
}
