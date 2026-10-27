package io.github.androidpoet.tor.lyrebird

import io.github.androidpoet.tor.Bridge
import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorConfig
import io.github.androidpoet.tor.TorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Live: bootstrap through Tor Browser's built-in obfs4 bridges with the bundled lyrebird. */
class LyrebirdLiveTest {
    @Test
    fun test_transport_unpacksAnExecutableBinary() {
        val transport = Lyrebird.transport()

        assertTrue(File(transport.executable).canExecute(), transport.executable)
        assertEquals(Lyrebird.protocols, transport.protocols)
    }

    @Test
    fun test_bootstrap_throughBuiltInObfs4Bridges_reachesTheTorNetwork() =
        runTest(timeout = 6.minutes) {
            withContext(Dispatchers.Default) {
                val config =
                    TorConfig(
                        dataDirectory = System.getProperty("java.io.tmpdir") + "/tor-kmp-test-lyrebird",
                        bridges = BUILTIN_OBFS4.map(Bridge::parse),
                        transports = listOf(Lyrebird.transport()),
                    )
                Tor(config).use { tor ->
                    tor.bootstrap()

                    assertEquals(TorState.Ready, tor.state.value)
                    val body =
                        tor.connectTls("check.torproject.org").use { stream ->
                            stream.write(
                                "GET /api/ip HTTP/1.1\r\nHost: check.torproject.org\r\nConnection: close\r\n\r\n".encodeToByteArray(),
                            )
                            val buffer = ByteArray(BUFFER_SIZE)
                            val response = StringBuilder()
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                response.append(buffer.decodeToString(0, count))
                            }
                            response.toString()
                        }
                    assertTrue(body.contains("\"IsTor\":true"), "not on Tor: $body")
                }
            }
        }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024

        /** From https://bridges.torproject.org/moat/circumvention/builtin, 2026-10-27. */
        val BUILTIN_OBFS4 =
            listOf(
                "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C " +
                    "cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0",
                "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 " +
                    "cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1",
                "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
                    "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
            )
    }
}
