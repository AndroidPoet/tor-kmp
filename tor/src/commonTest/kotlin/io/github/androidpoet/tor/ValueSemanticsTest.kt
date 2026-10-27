package io.github.androidpoet.tor

import io.github.androidpoet.tor.internal.supportsPluggableTransports
import io.github.androidpoet.tor.internal.windowsPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValueSemanticsTest {
    @Test
    fun test_bootstrapping_sameValues_areEqualSoStateFlowSkipsDuplicates() {
        val first = TorState.Bootstrapping(progress = 0.5f, blockage = null)
        val second = TorState.Bootstrapping(progress = 0.5f, blockage = null)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun test_bootstrapping_differentProgress_areNotEqual() {
        val first = TorState.Bootstrapping(progress = 0.5f, blockage = null)
        val second = TorState.Bootstrapping(progress = 0.6f, blockage = null)

        assertNotEquals(first, second)
    }

    @Test
    fun test_socksProxy_url_withoutIsolation_isPlainSocks5h() {
        val proxy = socksProxy()

        val url = proxy.url()

        assertEquals("socks5h://127.0.0.1:9150", url)
    }

    @Test
    fun test_socksProxy_url_withIsolation_putsKeyInUsername() {
        val proxy = socksProxy()

        val url = proxy.url(isolation = Isolation("tab-1"))

        assertEquals("socks5h://tab-1:x@127.0.0.1:9150", url)
    }

    @Test
    fun test_socksProxy_sameHostAndPort_areEqual() {
        val first = socksProxy()
        val second = socksProxy()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun test_socksProxyUrl_percentEncodesIsolationAndPassword() {
        val proxy = SocksProxy(host = "127.0.0.1", port = 9150, password = "p@ss word/ä", stop = {})

        assertEquals("socks5h://tab%201%2F%C3%A4:p%40ss%20word%2F%C3%A4@127.0.0.1:9150", proxy.url(Isolation("tab 1/ä")))
        assertEquals("socks5h://:p%40ss%20word%2F%C3%A4@127.0.0.1:9150", proxy.url())
    }

    @Test
    fun test_socksProxyClose_runsTheStopAction() {
        var stopped = 0
        val proxy = SocksProxy(host = "127.0.0.1", port = 9150, password = null, stop = { stopped++ })

        proxy.close()

        assertEquals(1, stopped)
    }

    @Test
    fun test_isolation_rejectsKeysOutsideSocks5Limits() {
        assertFailsWith<IllegalArgumentException> { Isolation("") }
        assertFailsWith<IllegalArgumentException> { Isolation("a\u0000b") }
        assertFailsWith<IllegalArgumentException> { Isolation("ä".repeat(128)) } // 256 UTF-8 bytes

        assertEquals("ä".repeat(127), Isolation("ä".repeat(127)).key) // 254 bytes
    }

    @Test
    fun test_isolationRandom_isUniqueAndHex() {
        val first = Isolation.random()
        val second = Isolation.random()

        assertNotEquals(first, second)
        assertEquals(32, first.key.length)
        assertTrue(first.key.all { it in "0123456789abcdef" }, first.key)
    }

    @Test
    fun test_socksAuthPassword_validatesAndRedacts() {
        assertFailsWith<IllegalArgumentException> { SocksAuth.Password("") }

        val auth = SocksAuth.Password("hunter2")

        assertEquals(SocksAuth.Password("hunter2"), auth)
        assertEquals("SocksAuth.Password(…)", auth.toString())
    }

    @Test
    fun test_bridgeParse_acceptsDirectAndTransportLines_withOrWithoutKeyword() {
        val direct = Bridge.parse("Bridge $DIRECT_LINE")
        val obfs4 = Bridge.parse(OBFS4_LINE)

        assertEquals(DIRECT_LINE, direct.line)
        assertNull(direct.transport)
        assertEquals("obfs4", obfs4.transport)
        assertEquals(Bridge.parse(DIRECT_LINE), direct)
    }

    @Test
    fun test_bridgeParse_rejectsGarbage_withReason() {
        val error = assertFailsWith<IllegalArgumentException> { Bridge.parse("not a bridge line") }

        assertTrue(error.message.orEmpty().startsWith("invalid bridge line"), error.message)
    }

    @Test
    fun test_torConfig_rejectsTransportBridgeWithoutProvider() {
        val error = assertFailsWith<IllegalArgumentException> { TorConfig("/tmp/x", bridges = listOf(Bridge.parse(OBFS4_LINE))) }

        assertTrue("obfs4" in error.message.orEmpty(), error.message)
    }

    @Test
    fun test_torConfig_settingsJson_isNullForThePublicNetwork_andEscapesOtherwise() {
        assertNull(TorConfig("/tmp/x").settingsJson())

        val config = TorConfig("/tmp/x", bridges = listOf(Bridge.parse(DIRECT_LINE)))

        assertEquals("""{"bridges":["$DIRECT_LINE"],"transports":[]}""", config.settingsJson())
    }

    @Test
    fun test_torConfig_withTransport_isAcceptedWhereProcessesCanBeSpawned() {
        val executable = absolute("a \"b\"/lyrebird")
        val transport = PluggableTransport(protocols = listOf("obfs4"), executable = executable, arguments = listOf("-x"))
        val build = { TorConfig("/tmp/x", bridges = listOf(Bridge.parse(OBFS4_LINE)), transports = listOf(transport)) }

        if (!supportsPluggableTransports) {
            assertFailsWith<IllegalArgumentException> { build() }
            return
        }
        val json = build().settingsJson().orEmpty()

        val quotedPath = executable.replace("\\", "\\\\").replace("\"", "\\\"")
        assertTrue(json.contains("""{"protocols":["obfs4"],"path":"$quotedPath","arguments":["-x"]}"""), json)
    }

    @Test
    fun test_pluggableTransport_validatesItsParts() {
        assertFailsWith<IllegalArgumentException> { PluggableTransport(emptyList(), absolute("pt")) }
        assertFailsWith<IllegalArgumentException> { PluggableTransport(listOf("obfs 4"), absolute("pt")) }
        assertFailsWith<IllegalArgumentException> { PluggableTransport(listOf("obfs4", "obfs4"), absolute("pt")) }
        assertFailsWith<IllegalArgumentException> { PluggableTransport(listOf("obfs4"), "bin/pt") }

        if (windowsPaths) {
            PluggableTransport(listOf("obfs4"), "C:\\pt\\lyrebird.exe")
            PluggableTransport(listOf("obfs4"), "\\\\server\\share\\lyrebird.exe")
            assertFailsWith<IllegalArgumentException> { PluggableTransport(listOf("obfs4"), "/opt/lyrebird") }
        } else {
            PluggableTransport(listOf("obfs4"), "/opt/lyrebird")
            assertFailsWith<IllegalArgumentException> { PluggableTransport(listOf("obfs4"), "C:\\pt\\lyrebird.exe") }
        }
    }

    @Test
    fun test_torConfig_rejectsTwoProvidersForOneTransport() {
        if (!supportsPluggableTransports) return
        val first = PluggableTransport(listOf("obfs4"), absolute("a"))
        val second = PluggableTransport(listOf("obfs4", "snowflake"), absolute("b"))

        val error = assertFailsWith<IllegalArgumentException> { TorConfig("/tmp/x", transports = listOf(first, second)) }

        assertTrue("obfs4" in error.message.orEmpty(), error.message)
    }

    private fun socksProxy() = SocksProxy(host = "127.0.0.1", port = 9150, password = null, stop = {})

    private companion object {
        /** An absolute path on whichever platform runs the test. */
        fun absolute(name: String): String = if (windowsPaths) "C:\\pt\\$name" else "/opt/$name"

        const val DIRECT_LINE = "192.0.2.10:443 0123456789ABCDEF0123456789ABCDEF01234567"
        const val OBFS4_LINE =
            "obfs4 192.0.2.55:38114 316E643333645F6D79216558614D3931657A5F5F " +
                "cert=YXJlIGZyZXF1ZW50bHkgZnVsbCBvZiBsaXR0bGUgbWVzc2FnZXMgeW91IGNhbiBmaW5kLg iat-mode=0"
    }
}
