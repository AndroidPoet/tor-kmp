package io.github.androidpoet.tor

import io.github.androidpoet.tor.internal.TorBridge
import kotlin.jvm.JvmInline

/**
 * A circuit-isolation key. Streams opened with different keys never share a Tor
 * circuit; streams with the same key may. Use one key per tab, account or
 * conversation to keep their activity unlinkable on the network.
 *
 * ```
 * tor.connect("example.com", 443, Isolation("tab-1"))
 * HttpClient(TorEngine) { engine { tor = myTor; isolation = Isolation.random() } }
 * ```
 *
 * A key is not a secret, but it travels as the SOCKS username when a proxy client
 * sends it, so SOCKS5's limits apply: 1 to 255 UTF-8 bytes, no NUL.
 *
 * @throws IllegalArgumentException if [key] is outside those limits.
 */
@JvmInline
public value class Isolation(
    public val key: String,
) {
    init {
        require(key.isSocksCredential()) { "isolation key must be 1..255 UTF-8 bytes without NUL" }
    }

    override fun toString(): String = "Isolation($key)"

    public companion object {
        /** A key nobody else will produce: 128 bits from the OS random source, as hex. */
        public fun random(): Isolation = Isolation(TorBridge.randomBytes(RANDOM_BYTES).toHex())

        private const val RANDOM_BYTES = 16
    }
}

/** RFC 1929 carries credentials with a one-byte length, and the native side takes a C string. */
internal fun String.isSocksCredential(): Boolean = encodeToByteArray().size in 1..SOCKS_CREDENTIAL_MAX_BYTES && '\u0000' !in this

internal fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and BYTE_MASK
            append(HEX_DIGITS[value shr HEX_SHIFT]).append(HEX_DIGITS[value and HEX_NIBBLE])
        }
    }

private const val SOCKS_CREDENTIAL_MAX_BYTES = 255
private const val HEX_DIGITS = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val HEX_NIBBLE = 0xF
private const val HEX_SHIFT = 4
