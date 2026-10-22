package com.sheen.rokid.agent

import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [`ConfirmationGate`] is the state machine behind spec §7.3/§7.4's confirmation rule: **the timeout default is deny**,
 * every request settles exactly once, and 急停 (`cancelAll`) withdraws every pending request as denied so a stop can
 * never leave an approval waiting to fire.
 *
 * The clock and scheduler are the `:test-support` fakes, so these tests advance time instead of sleeping.
 */
class ConfirmationGateTest {

    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)

    private fun gate(timeoutNanos: Long = 10_000_000_000L) = ConfirmationGate(clock, scheduler, timeoutNanos)

    @Test fun `a request that times out is denied exactly once`() {
        val gate = gate()
        val decisions = mutableListOf<Boolean>()
        gate.request("r1", "允许发送消息?") { decisions += it }

        clock.advance(9_999_999_999L)
        scheduler.runDue()
        assertTrue(decisions.isEmpty(), "the timeout must not fire before its deadline")

        clock.advance(1L)
        scheduler.runDue()

        assertEquals(listOf(false), decisions)
        assertFalse(gate.resolve("r1", approved = true), "an expired request is already settled")
        assertEquals(listOf(false), decisions, "a late approval must not deliver a second decision")
    }

    @Test fun `the default timeout is ten seconds`() {
        // The default is a contract, not a local choice: spec §7.4 fixes the silent default at deny after 10 s.
        val gate = ConfirmationGate(clock, scheduler)
        val decisions = mutableListOf<Boolean>()
        gate.request("r1", "允许发送消息?") { decisions += it }

        clock.advance(10_000_000_000L)
        scheduler.runDue()

        assertEquals(listOf(false), decisions)
    }

    @Test fun `resolving before the timeout delivers the decision once and cancels the timer`() {
        val gate = gate()
        val decisions = mutableListOf<Boolean>()
        gate.request("r1", "允许发送消息?") { decisions += it }

        assertTrue(gate.resolve("r1", approved = true))
        assertEquals(listOf(true), decisions)

        // The timer is gone: advancing past the deadline must not append a second, contradicting decision.
        clock.advance(60_000_000_000L)
        scheduler.runDue()
        assertEquals(listOf(true), decisions)
    }

    @Test fun `a second resolve for the same id returns false`() {
        val gate = gate()
        gate.request("r1", "允许发送消息?") { }

        assertTrue(gate.resolve("r1", approved = true))
        assertFalse(gate.resolve("r1", approved = false))
    }

    @Test fun `resolve returns false for an unknown id`() {
        assertFalse(gate().resolve("never-requested", approved = true))
    }

    @Test fun `cancelAll settles every pending request as denied`() {
        val gate = gate()
        val decisions = mutableListOf<Pair<String, Boolean>>()
        gate.request("r1", "一") { decisions += "r1" to it }
        gate.request("r2", "二") { decisions += "r2" to it }

        gate.cancelAll()

        assertEquals(listOf("r1" to false, "r2" to false), decisions)
        assertFalse(gate.resolve("r1", approved = true))
        assertFalse(gate.resolve("r2", approved = true))
        clock.advance(60_000_000_000L)
        scheduler.runDue()
        assertEquals(listOf("r1" to false, "r2" to false), decisions, "cleared timers must never fire")
    }

    @Test fun `cancelAll leaves an already resolved request alone`() {
        val gate = gate()
        val decisions = mutableListOf<Boolean>()
        gate.request("r1", "一") { decisions += it }
        gate.request("r2", "二") { decisions += it }
        assertTrue(gate.resolve("r1", approved = true))

        gate.cancelAll()

        assertEquals(listOf(true, false), decisions)
    }

    @Test fun `the handle returned by request withdraws the request as denied`() {
        val gate = gate()
        val decisions = mutableListOf<Boolean>()
        val handle = gate.request("r1", "一") { decisions += it }

        handle.cancel()

        assertEquals(listOf(false), decisions, "withdrawing is a denial, never an approval")
        assertFalse(gate.resolve("r1", approved = true))
        clock.advance(60_000_000_000L)
        scheduler.runDue()
        assertEquals(listOf(false), decisions)
    }

    @Test fun `a listener that re-enters the gate cannot double-deliver`() {
        // The failure mode: the request must be marked settled *before* its listener runs, so a listener that resolves
        // its own id does not produce a second decision for the same request.
        val gate = gate()
        val decisions = mutableListOf<Boolean>()
        var selfResolve: Boolean? = null
        gate.request("r1", "一") { approved ->
            decisions += approved
            selfResolve = gate.resolve("r1", approved = true)
        }

        assertTrue(gate.resolve("r1", approved = false))

        assertEquals(listOf(false), decisions)
        assertFalse(selfResolve ?: true)
    }

    @Test fun `a duplicate pending id is rejected and leaves the first request pending`() {
        val gate = gate()
        val decisions = mutableListOf<Boolean>()
        gate.request("r1", "一") { decisions += it }

        assertFailsWith<IllegalArgumentException> {
            gate.request("r1", "一 (again)") { approved -> decisions += approved }
        }

        assertTrue(decisions.isEmpty(), "the rejected duplicate must not have settled the first request")
        assertTrue(gate.resolve("r1", approved = true))
        assertEquals(listOf(true), decisions)
    }

    @Test fun `a non-positive timeout is rejected`() {
        assertFailsWith<IllegalArgumentException> { gate(timeoutNanos = 0) }
        assertFailsWith<IllegalArgumentException> { gate(timeoutNanos = -1) }
    }

    @Test fun `a blank request id is rejected`() {
        assertFailsWith<IllegalArgumentException> { gate().request(" ", "一") { } }
    }
}
