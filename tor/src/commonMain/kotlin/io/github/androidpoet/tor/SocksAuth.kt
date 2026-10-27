package io.github.androidpoet.tor

/**
 * How [Tor.startSocksProxy] authenticates its clients. Any process on the device
 * can connect to a loopback port, so the proxy asks for a password unless you
 * opt out for a client that cannot send one.
 */
public sealed interface SocksAuth {
    /**
     * A fresh random password every time the proxy starts, the default. Read it
     * from [SocksProxy.password] or let [SocksProxy.url] embed it.
     */
    public data object Generated : SocksAuth

    /**
     * A password you choose, when another component is configured with it
     * independently. SOCKS5 limits it to 1..255 UTF-8 bytes without NUL.
     *
     * @throws IllegalArgumentException if [value] is outside those limits.
     */
    public class Password(
        public val value: String,
    ) : SocksAuth {
        init {
            require(value.isSocksCredential()) { "password must be 1..255 UTF-8 bytes without NUL" }
        }

        override fun equals(other: Any?): Boolean = other is Password && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "SocksAuth.Password(…)"
    }

    /**
     * No authentication: every local process can send traffic through the proxy,
     * and no client can be isolated from another. Only for clients that cannot
     * send SOCKS credentials, such as a Chromium WebView.
     */
    public data object None : SocksAuth
}
