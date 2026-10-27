package io.github.androidpoet.tor.okhttp

import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live: OkHttp over Tor streams, TLS done by OkHttp on top of the Tor socket. */
class TorOkHttpLiveTest {
    @Test
    fun test_get_httpsThroughTorSocket_reportsIsTor() =
        live { client ->
            val body = client.newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { it.body.string() }

            assertTrue(body.contains("\"IsTor\":true"), "not on Tor: $body")
        }

    @Test
    fun test_get_onionService_returns200() =
        live { client ->
            val response = client.newCall(Request.Builder().url("http://$TORPROJECT_ONION/").build()).execute()

            response.use { assertEquals(200, it.code) }
        }

    @Test
    fun test_postAfterGet_onSameHost_survivesConnectionHealthCheck() =
        live { client ->
            // OkHttp checks a pooled connection before a POST by reading with a 1 ms
            // timeout; the socket must time out without dropping the Tor stream.
            client.newCall(Request.Builder().url("https://httpbin.org/get").build()).execute().use { assertEquals(200, it.code) }
            val request =
                Request
                    .Builder()
                    .url("https://httpbin.org/post")
                    .post("hello from tor-kmp".toRequestBody())
                    .build()

            val response = client.newCall(request).execute()

            response.use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().contains("hello from tor-kmp"))
            }
        }

    private fun live(block: (OkHttpClient) -> Unit) {
        Tor(TorConfig(System.getProperty("java.io.tmpdir") + "/tor-kmp-test-okhttp")).use { tor ->
            // OkHttp's 10 s default is tight for a first onion connection on a cold client.
            val client =
                OkHttpClient
                    .Builder()
                    .tor(tor)
                    .connectTimeout(1, TimeUnit.MINUTES)
                    .build()
            try {
                block(client)
            } finally {
                client.connectionPool.evictAll()
            }
        }
    }

    private companion object {
        const val TORPROJECT_ONION = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"
    }
}
