package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressGuardTest {

    @Test fun `three identical fingerprints trip the guard`() {
        val guard = ProgressGuard()

        guard.observeAfterAction("screen-a")
        assertFalse(guard.tripped)
        guard.observeAfterAction("screen-a")
        assertFalse(guard.tripped)
        guard.observeAfterAction("screen-a")
        assertTrue(guard.tripped)
    }

    @Test fun `a different fingerprint resets the consecutive count`() {
        val guard = ProgressGuard()

        guard.observeAfterAction("screen-a")
        guard.observeAfterAction("screen-a")
        guard.observeAfterAction("screen-b")
        assertFalse(guard.tripped)
        guard.observeAfterAction("screen-b")
        assertFalse(guard.tripped)
        guard.observeAfterAction("screen-b")
        assertTrue(guard.tripped)
    }

    @Test fun `the limit is parameterised`() {
        val guard = ProgressGuard(limit = 2)

        guard.observeAfterAction("same")
        assertFalse(guard.tripped)
        guard.observeAfterAction("same")
        assertTrue(guard.tripped)
    }

    @Test fun `a guard that never observed an action has not tripped`() {
        assertFalse(ProgressGuard().tripped)
        assertFalse(ProgressGuard(limit = 1).tripped)
    }

    @Test fun `a limit below one is rejected`() {
        assertFailsWith<IllegalArgumentException> { ProgressGuard(limit = 0) }
    }

    @Test fun `reset clears a tripped guard and starts a fresh consecutive streak`() {
        val guard = ProgressGuard(limit = 2)
        guard.observeAfterAction("same")
        guard.observeAfterAction("same")
        assertTrue(guard.tripped)

        guard.reset()

        assertFalse(guard.tripped)
        // The fingerprint reset matters too: the next observation is a first observation, not a continuation.
        guard.observeAfterAction("same")
        assertFalse(guard.tripped)
        guard.observeAfterAction("same")
        assertTrue(guard.tripped)
    }
}
