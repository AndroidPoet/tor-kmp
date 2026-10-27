package io.github.androidpoet.tor.internal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * Runs a blocking native call on [Dispatchers.IO]. If the coroutine is cancelled
 * while [block] is still running, [onCancel] is invoked so the native side can
 * return early (native code cannot be interrupted like a Java thread).
 */
internal suspend fun <T> cancellableBlocking(
    onCancel: () -> Unit,
    block: () -> T,
): T =
    coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.load()) onCancel()
                }
            }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            finished.store(true)
            watcher.cancel()
        }
    }
