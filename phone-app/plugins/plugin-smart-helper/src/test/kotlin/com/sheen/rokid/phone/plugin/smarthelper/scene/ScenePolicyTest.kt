package com.sheen.rokid.phone.plugin.smarthelper.scene

import kotlin.test.*

/**
 * The scene cooldown, unit-tested on its own: a wake inside the window is refused, one after it is admitted, and the
 * decision is time-based rather than per-turn.
 */
class ScenePolicyTest {

    @Test fun `the first turn is always admitted`() {
        val policy = ScenePolicy(1_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "a"))
    }

    @Test fun `a second turn inside the cooldown is refused`() {
        val policy = ScenePolicy(1_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "a"))
        policy.noteTurn(nowNanos = 5_000)
        assertFalse(policy.admit(nowNanos = 5_500, turnId = "b"))
        assertFalse(policy.admit(nowNanos = 5_999, turnId = "c"))
    }

    @Test fun `a turn exactly at the cooldown boundary is admitted`() {
        val policy = ScenePolicy(1_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "a"))
        policy.noteTurn(nowNanos = 5_000)
        assertTrue(policy.admit(nowNanos = 6_000, turnId = "b"))
    }

    @Test fun `a turn after the cooldown is admitted`() {
        val policy = ScenePolicy(1_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "a"))
        policy.noteTurn(nowNanos = 5_000)
        assertTrue(policy.admit(nowNanos = 9_000, turnId = "b"))
    }

    @Test fun `the cooldown is time-based and does not care about the turn id`() {
        val policy = ScenePolicy(1_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "same"))
        policy.noteTurn(nowNanos = 5_000)
        assertFalse(policy.admit(nowNanos = 5_100, turnId = "same"), "a repeat of the same turn id is still refused")
        assertFalse(policy.admit(nowNanos = 5_100, turnId = "different"), "a different turn id is refused just the same")
    }

    @Test fun `a refused admit does not extend the window`() {
        val policy = ScenePolicy(1_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "a"))
        policy.noteTurn(nowNanos = 5_000)
        // A refused wake at 5_500 must not move the reference time to 5_500.
        assertFalse(policy.admit(nowNanos = 5_500, turnId = "b"))
        assertTrue(policy.admit(nowNanos = 6_000, turnId = "c"))
    }

    @Test fun `a zero cooldown admits every turn`() {
        val policy = ScenePolicy(0)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "a"))
        policy.noteTurn(nowNanos = 5_000)
        assertTrue(policy.admit(nowNanos = 5_000, turnId = "b"))
    }
}
