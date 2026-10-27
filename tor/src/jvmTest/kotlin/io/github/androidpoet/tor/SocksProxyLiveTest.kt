package io.github.androidpoet.tor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Live: a stock JDK HTTPS client through the SOCKS proxy is seen by the Tor Project as Tor.
 *
 * This proves the traffic exits through Tor, not that DNS stays inside it: desktop
 * `HttpURLConnection` resolves hostnames locally before talking to the proxy, so it is
 * not a recommended client (see the README).
 */
class SocksProxyLiveTest {
    @Test
    fun test_startSocksProxy_httpsThroughProxy_reportsIsTor() =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                Tor(TorConfig(testDataDirectory() + "-socks")).use { tor ->
                    val socks = tor.startSocksProxy()
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socks.host, socks.port))

                    val body =
                        withContext(Dispatchers.IO) {
                            URI("https://check.torproject.org/api/ip").toURL().openConnection(proxy).run {
                                connectTimeout = 60_000
                                readTimeout = 60_000
                                getInputStream().bufferedReader().use { it.readText() }
                            }
                        }

                    assertTrue(body.contains("\"IsTor\":true"), "not on Tor: $body")
                }
            }
        }

    @Test
    fun test_startSocksProxy_withPassword_refusesAnonymousAndWrongPasswordClients() =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                Tor(TorConfig(testDataDirectory() + "-socks-auth")).use { tor ->
                    val socks = tor.startSocksProxy(password = "hunter2")

                    withContext(Dispatchers.IO) {
                        // Greeting offering only "no authentication": refused outright.
                        assertContentEquals(byteArrayOf(5, 0xFF.toByte()), socksExchange(socks, byteArrayOf(5, 1, 0), reply = 2))
                        // Username/password with the wrong password: method accepted, credentials rejected.
                        assertContentEquals(byteArrayOf(5, 2, 1, 1), socksExchange(socks, authRequest("tab-1", "wrong"), reply = 4))
                        // The right password gets in.
                        assertContentEquals(byteArrayOf(5, 2, 1, 0), socksExchange(socks, authRequest("tab-1", "hunter2"), reply = 4))
                    }
                }
            }
        }

    /** SOCKS5 greeting offering username/password, followed by the RFC 1929 credentials. */
    private fun authRequest(
        username: String,
        password: String,
    ): ByteArray =
        byteArrayOf(5, 1, 2, 1, username.length.toByte()) + username.encodeToByteArray() +
            byteArrayOf(password.length.toByte()) + password.encodeToByteArray()

    private fun socksExchange(
        socks: SocksProxy,
        request: ByteArray,
        reply: Int,
    ): ByteArray =
        Socket(socks.host, socks.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().run {
                write(request)
                flush()
            }
            socket.getInputStream().readNBytes(reply)
        }
}
