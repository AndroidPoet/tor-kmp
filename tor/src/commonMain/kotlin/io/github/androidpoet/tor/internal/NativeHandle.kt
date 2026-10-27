package io.github.androidpoet.tor.internal

import io.github.androidpoet.tor.TorException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Owns a native pointer so it is freed exactly once and never while a call is
 * using it.
 *
 * [close] runs [shutdown] at once (to wake blocked calls) and defers [free] until
 * the last in-flight [use] returns. Calls after [close] throw instead of touching
 * the pointer.
 */
internal class NativeHandle(
    private val pointer: Long,
    private val name: String,
    private val shutdown: (Long) -> Unit,
    private val free: (Long) -> Unit,
) {
    private val users = AtomicInt(0)
    private val closed = AtomicBoolean(false)
    private val freed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.load()

    fun <T> use(block: (Long) -> T): T {
        users.incrementAndFetch()
        try {
            if (closed.load()) throw TorException("$name is closed")
            return block(pointer)
        } finally {
            release()
        }
    }

    fun close() {
        // Count ourselves as a user so the pointer cannot be freed under shutdown().
        users.incrementAndFetch()
        try {
            if (closed.compareAndSet(expectedValue = false, newValue = true)) shutdown(pointer)
        } finally {
            release()
        }
    }

    private fun release() {
        if (users.decrementAndFetch() == 0 && closed.load() && freed.compareAndSet(expectedValue = false, newValue = true)) {
            free(pointer)
        }
    }
}
