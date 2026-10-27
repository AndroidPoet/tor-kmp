package io.github.androidpoet.tor.internal

import cnames.structs.TorKmp
import cnames.structs.TorKmpStream
import io.github.androidpoet.tor.TorException
import io.github.androidpoet.tor.cinterop.torkmp_blocked_kind
import io.github.androidpoet.tor.cinterop.torkmp_blocked_reason
import io.github.androidpoet.tor.cinterop.torkmp_bootstrap
import io.github.androidpoet.tor.cinterop.torkmp_cancel_bootstrap
import io.github.androidpoet.tor.cinterop.torkmp_connect
import io.github.androidpoet.tor.cinterop.torkmp_connect_tls
import io.github.androidpoet.tor.cinterop.torkmp_create
import io.github.androidpoet.tor.cinterop.torkmp_destroy
import io.github.androidpoet.tor.cinterop.torkmp_last_error
import io.github.androidpoet.tor.cinterop.torkmp_new_identity
import io.github.androidpoet.tor.cinterop.torkmp_socks_ip_connects
import io.github.androidpoet.tor.cinterop.torkmp_socks_start
import io.github.androidpoet.tor.cinterop.torkmp_socks_stop
import io.github.androidpoet.tor.cinterop.torkmp_status
import io.github.androidpoet.tor.cinterop.torkmp_stream_free
import io.github.androidpoet.tor.cinterop.torkmp_stream_read
import io.github.androidpoet.tor.cinterop.torkmp_stream_shutdown
import io.github.androidpoet.tor.cinterop.torkmp_stream_write
import io.github.androidpoet.tor.cinterop.torkmp_string_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/** Direct calls into the statically linked Rust library. */
@OptIn(ExperimentalForeignApi::class)
internal actual object TorBridge {
    actual fun create(
        stateDir: String,
        cacheDir: String,
    ): Long = torkmp_create(stateDir, cacheDir).orThrow().toLong()

    actual fun bootstrap(tor: Long) {
        if (torkmp_bootstrap(tor.client) != 0) throwLastError()
    }

    actual fun cancelBootstrap(tor: Long) = torkmp_cancel_bootstrap(tor.client)

    actual fun progress(tor: Long): Float =
        memScoped {
            val fraction = alloc<FloatVar>()
            val ready = alloc<IntVar>()
            torkmp_status(tor.client, fraction.ptr, ready.ptr)
            fraction.value
        }

    actual fun blockedReason(tor: Long): String? = torkmp_blocked_reason(tor.client)?.consume()

    actual fun blockedKind(tor: Long): Int = torkmp_blocked_kind(tor.client)

    actual fun newIdentity(tor: Long) = torkmp_new_identity(tor.client)

    actual fun connect(
        tor: Long,
        host: String,
        port: Int,
        isolation: String?,
    ): Long = torkmp_connect(tor.client, host, port.convert(), isolation).orThrow().toLong()

    actual fun connectTls(
        tor: Long,
        host: String,
        port: Int,
        isolation: String?,
    ): Long = torkmp_connect_tls(tor.client, host, port.convert(), isolation).orThrow().toLong()

    actual fun read(
        stream: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count =
            buffer.usePinned { pinned ->
                torkmp_stream_read(stream.stream, pinned.addressOf(offset).reinterpret(), length.convert())
            }
        if (count < 0) throwLastError()
        return if (count == 0L) -1 else count.toInt()
    }

    actual fun write(
        stream: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val result =
            buffer.usePinned { pinned ->
                torkmp_stream_write(stream.stream, pinned.addressOf(offset).reinterpret(), length.convert())
            }
        if (result != 0) throwLastError()
    }

    actual fun shutdownStream(stream: Long) = torkmp_stream_shutdown(stream.stream)

    actual fun freeStream(stream: Long) = torkmp_stream_free(stream.stream)

    actual fun startSocks(
        tor: Long,
        port: Int,
        password: String?,
    ): Int = torkmp_socks_start(tor.client, port.convert(), password).also { if (it < 0) throwLastError() }

    actual fun socksIpConnects(tor: Long): Long = torkmp_socks_ip_connects(tor.client).toLong()

    actual fun stopSocks(tor: Long) = torkmp_socks_stop(tor.client)

    actual fun destroy(tor: Long) = torkmp_destroy(tor.client)

    private val Long.client: CPointer<TorKmp> get() = toCPointer()!!
    private val Long.stream: CPointer<TorKmpStream> get() = toCPointer()!!

    private fun <T : CPointer<*>> T?.orThrow(): T = this ?: throwLastError()

    private fun throwLastError(): Nothing = throw TorException(torkmp_last_error()?.consume() ?: "unknown native error")

    /** Copies a string returned by Rust and frees the original. */
    private fun CPointer<ByteVar>.consume(): String = toKString().also { torkmp_string_free(this) }
}
