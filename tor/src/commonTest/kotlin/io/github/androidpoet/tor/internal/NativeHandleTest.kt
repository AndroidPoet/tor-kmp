package io.github.androidpoet.tor.internal

import io.github.androidpoet.tor.TorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeHandleTest {
    private val shutdowns = mutableListOf<Long>()
    private val frees = mutableListOf<Long>()
    private val handle = NativeHandle(POINTER, "Test", shutdowns::add, frees::add)

    @Test
    fun test_use_whileOpen_passesPointerAndReturnsResult() {
        val result = handle.use { it * 2 }

        assertEquals(POINTER * 2, result)
    }

    @Test
    fun test_use_afterClose_throwsTorException() {
        handle.close()

        val error = assertFailsWith<TorException> { handle.use { it } }

        assertEquals("Test is closed", error.message)
    }

    @Test
    fun test_close_whileInUse_shutsDownAtOnceAndFreesWhenUseReturns() {
        var freedDuringUse: Boolean? = null

        handle.use {
            handle.close()
            assertEquals(listOf(POINTER), shutdowns)
            freedDuringUse = frees.isNotEmpty()
        }

        assertEquals(false, freedDuringUse)
        assertEquals(listOf(POINTER), frees)
    }

    @Test
    fun test_close_calledTwice_shutsDownAndFreesOnce() {
        handle.close()
        handle.close()

        assertEquals(listOf(POINTER), shutdowns)
        assertEquals(listOf(POINTER), frees)
    }

    @Test
    fun test_use_whenBlockThrows_releasesSoCloseStillFrees() {
        assertFailsWith<IllegalStateException> { handle.use { error("boom") } }

        handle.close()

        assertEquals(listOf(POINTER), frees)
    }

    private companion object {
        const val POINTER = 42L
    }
}
