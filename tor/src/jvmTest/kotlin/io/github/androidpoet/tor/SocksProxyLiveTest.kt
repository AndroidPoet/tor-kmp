package io.github.androidpoet.tor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
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
}
