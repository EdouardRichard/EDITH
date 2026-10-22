package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C (spec §6.6 加载动画): whether the assistant is working, per route, and the bound that keeps a lost "finished"
 * from spinning the animation forever.
 *
 * [com.sheen.rokid.output.HintCommand.Busy] is a **level**, not an event: the phone sends it when a turn starts and when it
 * ends, and the later statement simply wins. That makes the failure mode obvious — one missing `idle` (a model
 * timeout, a `Stop`, a killed process) would leave the ring turning and the panel awake — so the level expires by
 * itself, and the test below is what proves it.
 */
class BusyStateTest {

    @Test
    fun aRouteIsBusyUntilItIsCleared() {
        val state = BusyState()
        state.set(AssistantRoute.PROACTIVE, busy = true, nowNanos = 0)
        assertTrue(state.any(0))
        assertEquals(AssistantRoute.PROACTIVE, state.route(0))
        state.set(AssistantRoute.PROACTIVE, busy = false, nowNanos = 1)
        assertFalse(state.any(1))
        assertNull(state.route(1))
    }

    @Test
    fun aLeakedBusyFlagExpiresAtTheBoundInsteadOfSpinningForever() {
        val state = BusyState(maxMillis = 10_000)
        state.set(AssistantRoute.WAKE, busy = true, nowNanos = 0)
        assertTrue("still inside the bound", state.any(9_999_999_999L))
        assertFalse("past the bound it is treated as never cleared, not as busy forever",
            state.any(10_000_000_001L))
        assertNull(state.route(10_000_000_001L))
    }

    @Test
    fun bothRoutesCanBeBusyAndClearingOneLeavesTheOther() {
        val state = BusyState()
        state.set(AssistantRoute.WAKE, true, 0)
        state.set(AssistantRoute.PROACTIVE, true, 0)
        assertTrue(state.any(0))
        state.set(AssistantRoute.WAKE, false, 1)
        assertEquals(AssistantRoute.PROACTIVE, state.route(1))
        assertTrue(state.any(1))
    }

    @Test
    fun theRouteReportedIsTheOneThatStartedMostRecently() {
        // The header marks which conversation is being worked on, and the newest statement is the true one.
        val state = BusyState()
        state.set(AssistantRoute.PROACTIVE, true, 0)
        state.set(AssistantRoute.WAKE, true, 5)
        assertEquals(AssistantRoute.WAKE, state.route(5))
    }

    @Test
    fun aRepeatOfTheSameLevelDoesNotRestartItsClock() {
        // The phone re-states the level; if every repeat reset the start instant the bound above could never fire.
        val state = BusyState(maxMillis = 10_000)
        state.set(AssistantRoute.WAKE, true, 0)
        state.set(AssistantRoute.WAKE, true, 9_000_000_000L)
        assertFalse("the bound counts from the first report of this level",
            state.any(10_000_000_001L))
    }

    @Test
    fun isBusyAnswersForOneRouteWithoutInventingTheOthers() {
        // 0.23 P3/P4: the panel shows the state of the conversation in front of the wearer, so a proactive turn must
        // not make the wake route look busy (or the reverse). `any` stays the answer for the BACK/tips decision.
        val state = BusyState()
        state.set(AssistantRoute.WAKE, true, 0)
        assertTrue(state.isBusy(AssistantRoute.WAKE, 0))
        assertFalse("the proactive route has not been told anything", state.isBusy(AssistantRoute.PROACTIVE, 0))
        state.set(AssistantRoute.PROACTIVE, true, 1)
        assertTrue(state.isBusy(AssistantRoute.PROACTIVE, 1))
        state.set(AssistantRoute.WAKE, false, 2)
        assertFalse(state.isBusy(AssistantRoute.WAKE, 2))
        assertTrue("clearing one route must not clear the other", state.isBusy(AssistantRoute.PROACTIVE, 2))
    }

    @Test
    fun isBusyExpiresAtTheSameBoundAsAny() {
        val state = BusyState(maxMillis = 10_000)
        state.set(AssistantRoute.WAKE, true, 0)
        assertTrue(state.isBusy(AssistantRoute.WAKE, 9_999_999_999L))
        assertFalse(state.isBusy(AssistantRoute.WAKE, 10_000_000_001L))
    }

    @Test
    fun resetClearsBothRoutes() {
        val state = BusyState()
        state.set(AssistantRoute.WAKE, true, 0)
        state.set(AssistantRoute.PROACTIVE, true, 0)
        state.reset()
        assertFalse(state.any(0))
    }

    @Test
    fun anImpossibleBoundIsRefusedRatherThanSilentlyIgnored() {
        assertFalse(runCatching { BusyState(maxMillis = 0) }.isSuccess)
    }
}

/**
 * The rotation itself, as a number: a pure function of elapsed time, so the animation's only untestable half is the
 * `View` that draws it.
 */
class LoadingIndicatorTest {

    @Test
    fun theAngleIsBoundedAndWrapsBackToZero() {
        assertEquals(0f, LoadingIndicator.angleFor(0), 0.001f)
        assertTrue(LoadingIndicator.angleFor(HALF_TURN_MILLIS - 1) < 360f)
        assertEquals(0f, LoadingIndicator.angleFor(HALF_TURN_MILLIS * 2), 0.001f)
    }

    @Test
    fun theAngleAdvancesInsideOneTurn() {
        assertTrue(LoadingIndicator.angleFor(HALF_TURN_MILLIS / 2) > LoadingIndicator.angleFor(HALF_TURN_MILLIS / 4))
    }

    @Test
    fun aNegativeElapsedTimeIsTreatedAsTheStartRatherThanWrappingBackwards() {
        assertEquals(0f, LoadingIndicator.angleFor(-5), 0.001f)
    }

    @Test
    fun theDotsAreEnoughToReadAsARingAndFewEnoughToDrawCheaply() {
        assertTrue("a ring needs more than a handful of dots", LoadingIndicator.DOTS >= 6)
        assertTrue("and this is a glasses panel, not a phone", LoadingIndicator.DOTS <= 16)
    }

    private companion object {
        /** One revolution per this many milliseconds; the constant lives in production and is read here. */
        const val HALF_TURN_MILLIS = LoadingIndicator.TURN_MILLIS / 2
    }
}
