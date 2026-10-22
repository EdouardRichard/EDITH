package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settle numbers are part of spec §4.4, so they are pinned here instead of being scattered
 * through the kernel.
 */
class SettlePolicyTest {

    private val policy = SettlePolicy()

    @Test
    fun `the default change waits are the spec budgets`() {
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.TAP))
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.LONG_PRESS))
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.DOUBLE_TAP))
        assertEquals(500L, policy.changeWaitMillis(ActKind.SWIPE))
        assertEquals(500L, policy.changeWaitMillis(ActKind.SCROLL))
        assertEquals(500L, policy.changeWaitMillis(ActKind.SCROLL_TO_TEXT))
        assertEquals(800L, policy.changeWaitMillis(ActKind.TYPE))
        // D1 §2.8 groups keys with taps: a HOME/back key can replace the whole screen.
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.PRESS_KEY))
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.HOME))
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.BACK))
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.RECENTS))
        assertEquals(2_000L, policy.changeWaitMillis(ActKind.NOTIFICATIONS))
        assertEquals(1_000L, policy.heldStillMillis)
        assertEquals(1_500L, SettlePolicy.OPEN_CHANGE_WAIT_MILLIS)
    }

    @Test
    fun `type reads at the first change while taps scrolls and keys hold still`() {
        assertFalse(policy.requiresHeldStill(ActKind.TYPE), "a typed value is read as soon as it lands")
        assertTrue(policy.requiresHeldStill(ActKind.TAP))
        assertTrue(policy.requiresHeldStill(ActKind.LONG_PRESS))
        assertTrue(policy.requiresHeldStill(ActKind.SCROLL))
        assertTrue(policy.requiresHeldStill(ActKind.PRESS_KEY))
    }
}
