package io.github.androidpoet.tor.okhttp

import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorConfig
import io.github.androidpoet.tor.TorException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * No network. A JVM with `socksProxyHost` set (or an Android device with a
 * proxied Wi-Fi network) hands OkHttp a system proxy, and for a SOCKS proxy OkHttp
 * opens a plain `java.net.Socket` to it, never calling the socket factory. The
 * `tor()` builder must switch that off, or every request bypasses Tor.
 */
class SystemProxyTest {
    @Test
    fun test_tor_neverConnectsToTheSystemSocksProxy() {
        val decoy = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()
        val acceptor =
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        decoy.accept().close()
                        accepted.incrementAndGet()
                    }
                }
            }
        val previous = PROXY_PROPERTIES.associateWith(System::getProperty)
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", decoy.localPort.toString())
        try {
            // A data directory under a plain file cannot be created, so Tor fails
            // before it touches the network; the point is where OkHttp connected first.
            val blocker = Files.createTempFile("tor-kmp", ".file").toFile().also(File::deleteOnExit)
            Tor(TorConfig(dataDirectory = File(blocker, "tor").path)).use { tor ->
                val client = OkHttpClient.Builder().tor(tor).build()

                val failure =
                    assertFailsWith<IOException> {
                        client.newCall(Request.Builder().url("https://example.com/").build()).execute().close()
                    }

                // The request reached the Tor socket, which is where creating the client failed.
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is TorException }, "not a Tor failure: $failure")
            }
        } finally {
            previous.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
            decoy.close()
            acceptor.join()
        }

        assertEquals(0, accepted.get(), "OkHttp connected to the system SOCKS proxy instead of going through Tor")
    }

    private companion object {
        val PROXY_PROPERTIES = listOf("socksProxyHost", "socksProxyPort")
    }
}
