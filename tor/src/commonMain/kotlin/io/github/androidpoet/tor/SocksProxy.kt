package io.github.androidpoet.tor

import io.github.androidpoet.tor.Tor.Companion.isSocksCredential

/**
 * A SOCKS5 proxy into Tor on the loopback interface, from [Tor.startSocksProxy].
 *
 * Point any proxy-aware client at it. Clients must send hostnames, not resolved
 * addresses (`socks5h://`), so DNS also goes through Tor; the README lists which
 * clients do.
 *
 * Any process on the device can connect to a loopback port. A [password] keeps
 * other processes out; prefer [Tor.connect] when you control the networking code.
 */
public class SocksProxy internal constructor(
    public val host: String,
    public val port: Int,
    /** The password every client must present, or null if the proxy is open to any local process. */
    public val password: String?,
    private val stop: () -> Unit,
) : AutoCloseable {
    /**
     * The proxy as a `socks5h://` URL, credentials included.
     *
     * Connections made with different [isolation] keys go through separate circuit
     * groups, so one key per browser tab or account keeps their Tor circuits apart.
     * The client must actually send the credentials for this to apply; a client
     * that connects without authenticating lands in the default group. Null uses
     * the default group. Keys are percent-encoded; SOCKS5 limits them to 255 bytes.
     */
    public fun url(isolation: String? = null): String {
        require(isolation == null || isolation.isSocksCredential()) { "isolation key must be 1..255 UTF-8 bytes without NUL" }
        val credentials =
            if (isolation == null && password == null) {
                ""
            } else {
                "${isolation.orEmpty().percentEncoded()}:${(password ?: PLACEHOLDER_PASSWORD).percentEncoded()}@"
            }
        return "socks5h://$credentials$host:$port"
    }

    /** Stops the proxy, ending its connections. Same as [Tor.stopSocksProxy]. */
    override fun close(): Unit = stop()

    override fun equals(other: Any?): Boolean =
        other is SocksProxy && other.host == host && other.port == port && other.password == password

    override fun hashCode(): Int = (31 * host.hashCode() + port) * 31 + password.hashCode()

    override fun toString(): String = "SocksProxy($host:$port${if (password != null) ", password" else ""})"

    private companion object {
        /** Clients send some password even when the proxy wants none; it only feeds the isolation key. */
        const val PLACEHOLDER_PASSWORD = "x"
    }
}

/** RFC 3986 userinfo encoding: unreserved characters stay, everything else becomes %XX. */
private fun String.percentEncoded(): String =
    buildString {
        for (byte in this@percentEncoded.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            if (char.isUnreserved()) append(char) else append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }

private fun Char.isUnreserved(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

private const val HEX = "0123456789ABCDEF"
