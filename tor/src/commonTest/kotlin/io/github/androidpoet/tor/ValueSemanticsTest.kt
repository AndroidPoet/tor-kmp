package io.github.androidpoet.tor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
        val proxy = SocksProxy(host = "127.0.0.1", port = 9150)

        val url = proxy.url()

        assertEquals("socks5h://127.0.0.1:9150", url)
    }

    @Test
    fun test_socksProxy_url_withIsolation_putsKeyInUsername() {
        val proxy = SocksProxy(host = "127.0.0.1", port = 9150)

        val url = proxy.url(isolation = "tab-1")

        assertEquals("socks5h://tab-1:x@127.0.0.1:9150", url)
    }

    @Test
    fun test_socksProxy_sameHostAndPort_areEqual() {
        val first = SocksProxy(host = "127.0.0.1", port = 9150)
        val second = SocksProxy(host = "127.0.0.1", port = 9150)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
