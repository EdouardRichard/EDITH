package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.agent.PolicyDecision
import com.sheen.rokid.agent.ToolDescriptor
import com.sheen.rokid.agent.WhitelistActionPolicy
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43/-4.12 R-03: `dial` is an accepted-only action -?it never waits for a screen observation, it
 * never falls back to `ACTION_DIAL`, and it refuses without `CALL_PHONE`.
 */
class DialToolTest {

    @Test
    fun `without CALL_PHONE the dial is refused and the executor is never called`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { false }.execute("""{"number":"10086"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CALL_PERMISSION_MISSING", errorCode(result))
        assertEquals(false, requireNotNull(result.execution).accepted)
        assertEquals(false, result.execution!!.applied)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `a successful dial is accepted-only and carries no number`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { true }.execute("""{"number":"10086"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals("an intent hand-off never proves applied", null, execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        // Task 8: `data` is empty — the facts are the execution's and a number never appears anywhere.
        assertEquals("{}", result.detail)
        assertEquals(listOf("dial" to """{"number":"10086"}"""), executor.calls)
        assertFalse("the number must not be repeated into the result", result.detail.contains("10086"))
    }

    @Test
    fun `an invalid number is ARGUMENTS_INVALID`() {
        val executor = FakeExecutor(ActionResult(ActionOutcome.Refused, "ARGUMENTS_INVALID"))

        val result = DialTool(executor) { true }.execute("""{"number":"not-a-number"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", errorCode(result))
    }

    @Test
    fun `there is no ACTION_DIAL fallback`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { true }.execute("""{"number":"10086"}""")

        assertEquals(listOf("dial"), executor.calls.map { it.first })
        assertFalse(result.detail.contains("ACTION_DIAL"))
        assertFalse(executor.calls.any { (_, args) -> args.contains("DIAL") })
    }

    @Test
    fun `a missing number is refused without calling the executor`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { true }.execute("{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", errorCode(result))
        assertTrue(executor.calls.isEmpty())
    }

    // ---- M1: a post-dispatch throw is uncertain, never a pre-dispatch refusal ----

    @Test
    fun `a dispatched dial whose executor throws is uncertain and accepted`() {
        val executor = ThrowingExecutor()

        val result = DialTool(executor) { true }.execute("""{"number":"10086"}""")

        assertEquals("the legacy projection cannot express uncertainty", ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Uncertain, execution.outcome)
        assertEquals("DIAL_FAILED", execution.code)
        assertEquals("the call reached the executor before the throw", true, execution.accepted)
        assertTrue("a dispatched call may have applied", execution.uncertain)
        assertEquals("an uncertain call can be retried", true, execution.recoverable)
        assertNull(execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertEquals("{}", result.detail)
        assertFalse("the number must never be echoed", result.detail.contains("10086"))
        assertEquals(listOf("dial"), executor.calls.map { it.first })
    }

    @Test
    fun `a missing number stays a pre-dispatch refusal`() {
        val result = DialTool(FakeExecutor()) { true }.execute("{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("a malformed call never dispatched anything", false, execution.accepted)
        assertFalse(execution.uncertain)
        assertEquals("ARGUMENTS_INVALID", execution.code)
    }

    // ---- R129: dial never asks, including under the default L1 ceiling ----

    @Test
    fun `dial is L1 so the default ceiling allows it without confirmation`() {
        assertEquals(
            "R129: the user ruled that dial never asks; an L2 descriptor is refused or questioned by the default policy",
            RiskLevel.L1,
            DialTool.DESCRIPTOR.risk,
        )
        assertEquals(false, DialTool.DESCRIPTOR.requiresConfirmation)
        val descriptor = ToolDescriptor(
            name = DialTool.DESCRIPTOR.name,
            description = DialTool.DESCRIPTOR.title,
            parametersJson = DialTool.DESCRIPTOR.parametersJson,
            risk = DialTool.DESCRIPTOR.risk,
            requiresConfirmation = DialTool.DESCRIPTOR.requiresConfirmation,
            idempotent = DialTool.DESCRIPTOR.idempotent,
            reversible = DialTool.DESCRIPTOR.reversible,
            scope = DialTool.DESCRIPTOR.scope,
        )

        val decision = WhitelistActionPolicy(maxLevel = RiskLevel.L1, whitelist = emptyList()).decide(descriptor)

        assertEquals(
            "the default L1 ceiling must allow dial, never question or refuse it",
            PolicyDecision.Allow,
            decision,
        )
    }

    // ---- R98: the state-changing declaration ----

    @Test
    fun `dial results declare a state change`() {
        assertTrue(DialTool(FakeExecutor()) { true }.execute("""{"number":"10086"}""").stateChanging)
        // The declaration describes the call class, not whether CALL_PHONE allowed it to run.
        assertTrue(DialTool(FakeExecutor()) { false }.execute("""{"number":"10086"}""").stateChanging)

        // Fix review: an unknown action is not a dial call at all, so it must not declare a state change.
        val unknown = DialTool(FakeExecutor()) { true }.execute("device", "{}")
        assertEquals("UNKNOWN_ACTION", requireNotNull(unknown.execution).code)
        assertFalse("an unknown name is not a dial call", unknown.stateChanging)
    }

    private fun errorCode(result: ActionResult): String? = result.execution?.code

    private class FakeExecutor(
        private val result: ActionResult = ActionResult(ActionOutcome.Done, "dialing"),
    ) : ActionExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override fun descriptors(): List<ActionDescriptor> = emptyList()

        override fun execute(name: String, argsJson: String): ActionResult {
            calls += name to argsJson
            return result
        }

        override fun cancel() = Unit
    }

    /** M1: the executor blows up after the tool has dispatched the dial. */
    private class ThrowingExecutor : ActionExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override fun descriptors(): List<ActionDescriptor> = emptyList()

        override fun execute(name: String, argsJson: String): ActionResult {
            calls += name to argsJson
            error("dial executor died mid-call")
        }

        override fun cancel() = Unit
    }
}
