package io.github.androidpoet.tor

/**
 * A SOCKS5 proxy into Tor on the loopback interface, from [Tor.startSocksProxy].
 *
 * Point any proxy-aware client at it: Ktor, OkHttp, `java.net.Proxy`, or a WebView.
 * Clients must send hostnames, not resolved addresses (`socks5h://`), so DNS also
 * goes through Tor.
 *
 * Any app on the device can connect to a loopback port. Prefer [Tor.connect] when
 * you control the networking code.
 */
public class SocksProxy(
    public val host: String,
    public val port: Int,
) {
    /**
     * The proxy as a `socks5h://` URL.
     *
     * Connections made with different [isolation] keys never share circuits, so
     * one key per browser tab or account keeps them unlinkable. Keys go into the
     * URL unescaped, so stick to letters, digits, `-` and `_`. Null uses the
     * default group.
     */
    public fun url(isolation: String? = null): String =
        if (isolation == null) "socks5h://$host:$port" else "socks5h://$isolation:x@$host:$port"

    override fun equals(other: Any?): Boolean = other is SocksProxy && other.host == host && other.port == port

    override fun hashCode(): Int = 31 * host.hashCode() + port

    override fun toString(): String = "SocksProxy($host:$port)"
}
