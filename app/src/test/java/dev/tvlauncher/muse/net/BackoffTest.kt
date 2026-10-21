package dev.tvlauncher.muse.net

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    private fun seconds(vararg s: Int) = s.map { it * 1_000L }

    @Test
    fun `doubles to a ceiling and honours the floor`() {
        val backoff = Backoff()
        assertEquals(seconds(2, 4, 8, 16, 32, 60, 60), List(7) { backoff.nextDelayMs() })
        backoff.reset()
        backoff.floorMs = 15_000
        assertEquals(15_000L, backoff.nextDelayMs())
    }

    @Test
    fun `stays at the ceiling through a long outage`() {
        val backoff = Backoff()
        var delay = 0L
        repeat(1_100) { delay = backoff.nextDelayMs() }
        assertEquals(60_000L, delay)
    }

    @Test
    fun `a healthy session resets the sequence and a refused bearer raises the floor`() {
        val backoff = Backoff()
        repeat(4) { backoff.nextDelayMs() } // 2, 4, 8, 16
        assertEquals(32_000L, backoff.afterSession(registeredForMs = 29_999, authRejected = false))
        assertEquals(2_000L, backoff.afterSession(registeredForMs = 30_000, authRejected = false))
        assertEquals(15_000L, backoff.afterSession(registeredForMs = 0, authRejected = true)) // 4 s, lifted
        assertEquals(15_000L, backoff.afterSession(registeredForMs = 0, authRejected = false)) // floor stays
        assertEquals(16_000L, backoff.afterSession(registeredForMs = 0, authRejected = false))
        assertEquals(2_000L, backoff.afterSession(registeredForMs = 60_000, authRejected = false)) // floor cleared
    }
}
