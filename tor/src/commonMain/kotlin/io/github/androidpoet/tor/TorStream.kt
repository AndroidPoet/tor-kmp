package io.github.androidpoet.tor

import io.github.androidpoet.tor.internal.NativeHandle
import io.github.androidpoet.tor.internal.TorBridge
import io.github.androidpoet.tor.internal.cancellableBlocking

/**
 * A TCP-like byte stream through Tor, from [Tor.connect]. No local port is
 * involved, so no other app on the device can use it.
 *
 * One reader and one writer may run concurrently. Cancelling a coroutine that is
 * blocked in [read] or [write] closes the stream.
 */
public class TorStream internal constructor(
    pointer: Long,
) : AutoCloseable {
    private val handle = NativeHandle(pointer, "TorStream", TorBridge::shutdownStream, TorBridge::freeStream)

    /**
     * Reads up to [length] bytes into [buffer] at [offset], suspending until at
     * least one byte arrives. Returns the count, or -1 when the remote side has
     * closed the stream.
     *
     * @throws TorException if the stream fails or is closed.
     */
    public suspend fun read(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): Int {
        checkRange(buffer, offset, length)
        if (length == 0) return 0
        return cancellableBlocking(onCancel = ::close) {
            handle.use { TorBridge.read(it, buffer, offset, length) }
        }
    }

    /**
     * Writes [length] bytes of [buffer] from [offset] and flushes them onto the
     * circuit, suspending until they are sent.
     *
     * @throws TorException if the stream fails or is closed.
     */
    public suspend fun write(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ) {
        checkRange(buffer, offset, length)
        if (length == 0) return
        cancellableBlocking(onCancel = ::close) {
            handle.use { TorBridge.write(it, buffer, offset, length) }
        }
    }

    /** Closes the stream and wakes any blocked [read] or [write]. Idempotent. */
    override fun close() {
        handle.close()
    }

    private fun checkRange(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "offset $offset and length $length out of bounds for buffer of size ${buffer.size}"
        }
    }
}
