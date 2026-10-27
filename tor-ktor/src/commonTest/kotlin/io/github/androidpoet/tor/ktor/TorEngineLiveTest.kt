package io.github.androidpoet.tor.ktor

import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Live tests against the Tor network through [TorEngine]: no proxy, no port. */
class TorEngineLiveTest {
    @Test
    fun test_get_httpsThroughEngine_reportsIsTor() =
        live { client, tor ->
            val body = client.checkTor(tor)

            assertTrue(body.contains("\"IsTor\":true"), "not on Tor: $body")
        }

    @Test
    fun test_get_onionService_returns200() =
        live { client ->
            val response = client.get("http://$TORPROJECT_ONION/")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Tor", ignoreCase = true))
        }

    @Test
    fun test_get_twiceToSameHost_reusesConnectionAndSucceeds() =
        live { client, tor ->
            val firstBody = client.checkTor(tor)
            val second = client.get("https://check.torproject.org/api/ip")

            assertEquals(HttpStatusCode.OK, second.status)
            assertTrue(firstBody.contains("\"IsTor\":true"))
            assertTrue(second.bodyAsText().contains("\"IsTor\":true"))
        }

    @Test
    fun test_get_afterNewIdentity_retiresPooledConnectionAndSucceeds() =
        live { client, tor ->
            val before = client.checkTor(tor)
            tor.newIdentity()

            val after = client.checkTor(tor)

            assertTrue(before.contains("\"IsTor\":true") && after.contains("\"IsTor\":true"), "$before / $after")
        }

    @Test
    fun test_post_withBody_isEchoedBack() =
        live { client ->
            val response =
                client.post("https://httpbin.org/post") {
                    setBody("hello from tor-kmp")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("hello from tor-kmp"))
        }

    @Test
    fun test_webSocket_echoServer_roundTripsAFrame() =
        live { client ->
            val message = "ping from tor-kmp"
            var echoed: String? = null
            client.webSocket("wss://$WS_ECHO_HOST/") {
                send(Frame.Text(message))
                // The echo service greets first ("Request served by …"), then echoes.
                repeat(MAX_FRAMES) {
                    val frame = incoming.receive() as? Frame.Text ?: return@repeat
                    if (frame.readText() == message) {
                        echoed = message
                        return@webSocket
                    }
                }
            }

            assertEquals(message, echoed)
        }

    private fun live(block: suspend (HttpClient) -> Unit) = live { client, _ -> block(client) }

    private fun live(block: suspend (HttpClient, Tor) -> Unit) =
        runTest(timeout = 4.minutes) {
            withContext(Dispatchers.Default) {
                Tor(TorConfig(testDataDirectory())).use { tor ->
                    HttpClient(TorEngine) {
                        engine { this.tor = tor }
                        install(WebSockets)
                    }.use { block(it, tor) }
                }
            }
        }

    /**
     * check.torproject.org sometimes accepts the TLS session and then closes it without
     * answering (it rate-limits busy exits). A new identity moves to another exit.
     */
    private suspend fun HttpClient.checkTor(tor: Tor): String {
        var lastFailure: Exception? = null
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (attempt > 0) tor.newIdentity()
            try {
                return get("https://check.torproject.org/api/ip").bodyAsText()
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw checkNotNull(lastFailure)
    }

    private companion object {
        const val TORPROJECT_ONION = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"
        const val WS_ECHO_HOST = "echo.websocket.org"
        const val MAX_FRAMES = 4
        const val PROBE_ATTEMPTS = 3
    }
}

internal expect fun testDataDirectory(): String
