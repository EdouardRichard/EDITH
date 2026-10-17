package com.sheen.rokid.glasses

import com.sheen.rokid.contracts.AssistantRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C (spec §6.6): which conversation the glasses show, and how the wearer changes it — decided off-device.
 *
 * The official sample (`50-system/01-按键与侧边触控.md`) documents the temple's side-touch pad as `KeyEvent`s to the
 * foreground page: a swipe is `KEYCODE_DPAD_RIGHT`/`KEYCODE_DPAD_LEFT` and a tap is `KEYCODE_ENTER`. What those keys
 * *mean* lives here so it can be tested without a device; the Activity keeps the key plumbing.
 */
class ConversationOrderTest {

    @Test
    fun aNewOrderLooksAtTheWakeConversationAndIsNotPaused() {
        val order = ConversationOrder()
        assertEquals(AssistantRoute.WAKE, order.selected())
        assertFalse(order.isPaused())
    }

    @Test
    fun aSwipeSwitchesToTheOtherRouteAndWrapsBack() {
        val order = ConversationOrder()
        assertEquals(AssistantRoute.PROACTIVE, order.swipe())
        assertEquals("two routes, so the same gesture must bring you back",
            AssistantRoute.WAKE, order.swipe())
    }

    @Test
    fun aTapStopsASlideAndASecondTapResumesIt() {
        val order = ConversationOrder()
        assertTrue("the first tap stops the slide", order.togglePaused())
        assertFalse("the second tap starts it again", order.togglePaused())
    }

    @Test
    fun anArrivingHintBringsItsOwnRouteToTheFront() {
        // Without this the wearer would never see a proactive hint while reading the wake conversation: it would be
        // filed into a list nobody is looking at.
        val order = ConversationOrder()
        order.swipe() // now showing PROACTIVE
        assertEquals(AssistantRoute.WAKE, order.show(AssistantRoute.WAKE))
        assertEquals(AssistantRoute.WAKE, order.selected())
    }

    @Test
    fun anArrivingHintResumesASlideTheWearerHadPaused() {
        // A stale pause would hold brand new content off the bottom of the window with nothing on screen saying why.
        val order = ConversationOrder()
        order.togglePaused()
        order.show(AssistantRoute.PROACTIVE)
        assertFalse(order.isPaused())
    }

    @Test
    fun aNewSessionOpensOnTheWakeConversationAgain() {
        val order = ConversationOrder()
        order.swipe()
        order.togglePaused()
        order.reset()
        assertEquals(AssistantRoute.WAKE, order.selected())
        assertFalse(order.isPaused())
    }
}

/**
 * The `SAID` ordering gate: the custom-command channel promises nothing about ordering, so a late utterance must not
 * be appended behind a later one.
 */
class SaidSequenceGateTest {

    @Test
    fun anUtteranceIsAcceptedOnlyInStrictlyIncreasingOrder() {
        val gate = SaidSequenceGate()
        assertTrue(gate.accept(AssistantRoute.WAKE, 1))
        assertFalse("a repeat must not be appended twice", gate.accept(AssistantRoute.WAKE, 1))
        assertFalse(gate.accept(AssistantRoute.WAKE, 0))
        assertTrue(gate.accept(AssistantRoute.WAKE, 2))
    }

    @Test
    fun eachRouteCountsOnItsOwn() {
        val gate = SaidSequenceGate()
        gate.accept(AssistantRoute.WAKE, 5)
        assertTrue("the proactive route has its own numbering", gate.accept(AssistantRoute.PROACTIVE, 1))
    }

    @Test
    fun resetForgetsEveryRoute() {
        val gate = SaidSequenceGate()
        gate.accept(AssistantRoute.WAKE, 5)
        gate.accept(AssistantRoute.PROACTIVE, 5)
        gate.reset()
        assertTrue(gate.accept(AssistantRoute.WAKE, 1))
        assertTrue(gate.accept(AssistantRoute.PROACTIVE, 1))
    }
}
