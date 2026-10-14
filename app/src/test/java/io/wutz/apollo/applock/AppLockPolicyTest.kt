package io.wutz.apollo.applock

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppLockPolicyTest {
    private var now = 1_000L
    private val policy = AppLockPolicy { now }

    @Test
    fun `disabled lock never locks`() {
        policy.initialize(enabled = false, timeoutMs = 0)
        policy.onBackground()
        now += 3_600_000
        policy.onForeground()

        assertFalse(policy.locked)
    }

    @Test
    fun `enabled lock starts locked and only once per process`() {
        policy.initialize(enabled = true, timeoutMs = 60_000)
        assertTrue(policy.locked)

        policy.unlock()
        policy.initialize(enabled = true, timeoutMs = 60_000)

        assertFalse(policy.locked)
    }

    @Test
    fun `short background stays unlocked, long background locks`() {
        policy.initialize(enabled = true, timeoutMs = 60_000)
        policy.unlock()

        policy.onBackground()
        now += 59_999
        policy.onForeground()
        assertFalse(policy.locked)

        policy.onBackground()
        now += 60_000
        policy.onForeground()
        assertTrue(policy.locked)
    }

    @Test
    fun `foreground without a recorded background does not lock`() {
        policy.initialize(enabled = true, timeoutMs = 0)
        policy.unlock()
        now += 3_600_000

        policy.onForeground()

        assertFalse(policy.locked)
    }

    @Test
    fun `leaving while locked, as the screen-lock confirmation does, keeps it locked without a relock loop`() {
        policy.initialize(enabled = true, timeoutMs = 30_000)
        assertTrue(policy.consumeAutoPrompt())

        policy.onBackground()
        now += 120_000
        policy.onForeground()
        assertTrue(policy.locked)
        // The same lock: no second automatic prompt.
        assertFalse(policy.consumeAutoPrompt())

        policy.unlock()
        policy.onForeground()
        assertFalse(policy.locked)
    }

    @Test
    fun `turning the lock on keeps the session open, turning it off unlocks`() {
        policy.initialize(enabled = false, timeoutMs = 60_000)

        policy.setEnabled(true)
        assertFalse(policy.locked)

        policy.onBackground()
        now += 60_000
        policy.onForeground()
        assertTrue(policy.locked)

        policy.setEnabled(false)
        assertFalse(policy.locked)
    }

    @Test
    fun `auto prompt is offered once per lock`() {
        policy.initialize(enabled = true, timeoutMs = 1_000)
        assertTrue(policy.consumeAutoPrompt())
        assertFalse(policy.consumeAutoPrompt())

        policy.unlock()
        assertFalse(policy.consumeAutoPrompt())

        policy.onBackground()
        now += 1_000
        policy.onForeground()
        assertTrue(policy.consumeAutoPrompt())
    }

    @Test
    fun `a shorter timeout applies to the next background`() {
        policy.initialize(enabled = true, timeoutMs = 900_000)
        policy.unlock()
        policy.setTimeout(30_000)

        policy.onBackground()
        now += 30_000
        policy.onForeground()

        assertTrue(policy.locked)
    }
}
