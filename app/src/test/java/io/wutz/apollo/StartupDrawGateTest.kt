package io.wutz.apollo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupDrawGateTest {
    private var now = 10_000L
    private var ready = false
    private val gate = StartupDrawGate(isReady = { ready }, timeoutMs = 2_000, clock = { now })

    @Test
    fun `holds the first frame while not ready`() {
        now += 1_999

        assertFalse(gate.canDraw())
    }

    @Test
    fun `draws as soon as ready`() {
        ready = true

        assertTrue(gate.canDraw())
    }

    @Test
    fun `draws after the timeout even when never ready`() {
        now += 2_000

        assertTrue(gate.canDraw())
    }
}
