package io.github.androidpoet.tor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Runs against the live Tor network, so it needs internet access. Fetching an
 * onion service proves traffic really goes through Tor: nothing else can reach it.
 */
class TorLiveTest {
    @Test
    fun test_connect_toOnionService_returnsHttp200() =
        live { tor ->
            val response = tor.connect(TORPROJECT_ONION, 80).use { it.httpGet(TORPROJECT_ONION) }

            assertTrue(response.startsWith("HTTP/1.1 200"), "unexpected response: ${response.take(200)}")
            assertEquals(TorState.Ready, tor.state.value)
        }

    @Test
    fun test_read_afterStreamClosed_throwsTorException() =
        live { tor ->
            val stream = tor.connect(TORPROJECT_ONION, 80)

            stream.close()

            assertFailsWith<TorException> { stream.read(ByteArray(16)) }
        }

    @Test
    fun test_connect_afterClose_throwsAndStateIsClosed() =
        live { tor ->
            tor.bootstrap()

            tor.close()

            assertFailsWith<TorException> { tor.connect(TORPROJECT_ONION, 80) }
            assertEquals(TorState.Closed, tor.state.value)
        }

    /** Real time (not runTest's virtual clock), with room for a cold bootstrap. */
    private fun live(block: suspend (Tor) -> Unit) =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                Tor(TorConfig(testDataDirectory())).use { block(it) }
            }
        }

    private companion object {
        const val TORPROJECT_ONION = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"
    }
}

/** Sends a minimal HTTP/1.1 GET and returns the whole response as text. */
internal suspend fun TorStream.httpGet(host: String): String {
    write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".encodeToByteArray())
    val response = StringBuilder()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        response.append(buffer.decodeToString(0, count))
    }
    return response.toString()
}

/**
 * A data directory reused across runs, so only the first run pays for a cold
 * bootstrap. Each platform gets its own because parallel test tasks must not share one.
 */
internal expect fun testDataDirectory(): String
