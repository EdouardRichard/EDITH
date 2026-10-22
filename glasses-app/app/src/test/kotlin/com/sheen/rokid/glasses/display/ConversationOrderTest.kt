package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.23 P4 (spec §5.2.1, U8): the glasses show **one** conversation and offer no route switch.
 *
 * The official panel has no route label and no left/right route gesture; EDITH keeps its two logs, but the view is
 * whatever the last `SHOW` named. The panel's only view-level state left is the pin (the wearer's reading control),
 * and this class is the whole of both decisions so they can be tested without a device.
 */
class ConversationOrderTest {

    @Test
    fun aNewOrderShowsWakeAndIsUnpinned() {
        val order = ConversationOrder()
        assertEquals(AssistantRoute.WAKE, order.selected())
        assertFalse(order.isPinned())
    }

    @Test
    fun showBringsItsRouteToTheFrontAndKeepsThePin() {
        // The pin is a view state, not a route state: switching to the proactive answer must not silently unpin the
        // wearer's reading position.
        val order = ConversationOrder()
        order.pin()
        assertEquals(AssistantRoute.PROACTIVE, order.show(AssistantRoute.PROACTIVE))
        assertEquals(AssistantRoute.PROACTIVE, order.selected())
        assertTrue("pin is a view state, not a route state", order.isPinned())
    }

    @Test
    fun aTapPinsOnceAndASecondTapStaysPinned() {
        // 0.24 spec §3.3: the official hold is one-way. There is no unpin gesture; a new window resets it.
        val order = ConversationOrder()
        order.pin()
        assertTrue(order.isPinned())
        order.pin()
        assertTrue("a second tap must not unpin", order.isPinned())
    }

    @Test
    fun aNewSessionResetsRouteAndPin() {
        val order = ConversationOrder()
        order.show(AssistantRoute.PROACTIVE)
        order.pin()
        order.reset()
        assertEquals(AssistantRoute.WAKE, order.selected())
        assertFalse(order.isPinned())
    }

    @Test
    fun thereIsNoRouteSwitchLeft() {
        // U8: the wearer has no entry point. The only way the route changes is a SHOW, which is `show`.
        val order = ConversationOrder()
        order.show(AssistantRoute.PROACTIVE)
        assertEquals(AssistantRoute.PROACTIVE, order.selected())
        order.show(AssistantRoute.PROACTIVE)
        assertEquals(AssistantRoute.PROACTIVE, order.selected())
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
