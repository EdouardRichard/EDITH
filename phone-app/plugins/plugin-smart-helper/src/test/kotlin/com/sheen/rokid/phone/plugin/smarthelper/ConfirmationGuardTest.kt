package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ConfirmationOutcome
import com.sheen.rokid.agent.ConfirmationPort
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R52: the plugin gates host actions by *precheck*, not by the descriptor's static risk level.
 *
 * The `shell` descriptor is one tool, but its commands are not one risk: `pm clear` must ask the wearer while
 * `dumpsys window` must not. The wrapper is deliberately the only place that decides what an answer means, and it must
 * be impossible to execute the host action without going through it: Allow executes once, Require executes only after a
 * live approval, Reject never executes, and a missing/broken channel refuses instead of acting unasked.
 */
class ConfirmationGuardTest {

    private val args = """{"command":"pm clear com.x"}"""

    private class FakeActions(
        var precheck: ActionPrecheck = ActionPrecheck(PrecheckDecision.Allow),
    ) : ActionExecutor {
        val executed = mutableListOf<Pair<String, String>>()
        var cancels = 0
        var begins = 0

        override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

        override fun execute(name: String, argsJson: String): ActionResult {
            executed += name to argsJson
            return ActionResult(ActionOutcome.Done, "executed")
        }

        override fun cancel() { cancels++ }
        override fun beginTurn() { begins++ }
        override fun precheck(name: String, argsJson: String): ActionPrecheck = precheck
    }

    private class FakeConfirmation(
        var outcome: ConfirmationOutcome = ConfirmationOutcome(true, ConfirmationSource.PHONE),
    ) : ConfirmationPort {
        val requested = mutableListOf<Pair<String, String>>()
        val outcomes = ArrayDeque<ConfirmationOutcome>()
        var failure: RuntimeException? = null

        override fun request(requestId: String, summary: String): ConfirmationOutcome {
            failure?.let { throw it }
            requested += requestId to summary
            return outcomes.removeFirstOrNull() ?: outcome
        }
    }

    private fun wrapper(
        actions: FakeActions = FakeActions(),
        confirmation: FakeConfirmation = FakeConfirmation(),
        canAsk: Boolean = true,
        requestIds: MutableList<String> = mutableListOf("request-1"),
    ): ConfirmingActions = ConfirmingActions(
        host = actions,
        confirmation = confirmation,
        canAsk = { canAsk },
        newRequestId = { requestIds.removeFirstOrNull() ?: "request-fallback" },
        diagnostics = { },
    )

    @Test
    fun `an allowed action executes once without asking`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.Allow))
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf("shell" to args), actions.executed)
        assertTrue(confirmation.requested.isEmpty(), "an allowed command must execute exactly once and never ask")
    }

    @Test
    fun `a required action runs once after an approval and carries the precheck summary`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "不可逆终端命令"))
        val confirmation = FakeConfirmation(ConfirmationOutcome(true, ConfirmationSource.PHONE))

        // The production default mints the request id; this test pins that it is a UUID.
        val subject = ConfirmingActions(host = actions, confirmation = confirmation, canAsk = { true },
            diagnostics = { })

        val result = subject.execute("shell", args)

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf("shell" to args), actions.executed, "the command must run exactly once")
        val (requestId, summary) = confirmation.requested.single()
        assertNotNull(runCatching { UUID.fromString(requestId) }.getOrNull(),
            "R52 requires the confirmation to be addressed by a UUID, was `$requestId`")
        assertEquals("不可逆终端命令", summary)
    }

    @Test
    fun `a phone or voice denial refuses without executing`() {
        listOf(ConfirmationSource.PHONE, ConfirmationSource.VOICE).forEach { source ->
            val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
            val confirmation = FakeConfirmation(ConfirmationOutcome(false, source))

            val result = wrapper(actions, confirmation).execute("shell", args)

            assertEquals(ActionOutcome.Refused, result.outcome)
            assertEquals("CONFIRMATION_DENIED", result.detail)
            assertTrue(actions.executed.isEmpty(), "a denial must never run the command (source=$source)")
            assertEquals(1, confirmation.requested.size)
        }
    }

    @Test
    fun `a timeout refuses with its own code and does not execute`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT))

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_TIMEOUT", result.detail)
        assertTrue(actions.executed.isEmpty(), "silence is not consent")
    }

    @Test
    fun `no confirmation channel is unavailable rather than unasked execution`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation, canAsk = false).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_UNAVAILABLE", result.detail)
        assertTrue(actions.executed.isEmpty())
        assertTrue(confirmation.requested.isEmpty(), "a missing channel means there is nobody to ask")
    }

    @Test
    fun `a broken channel refuses as unavailable instead of throwing into the loop`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation().apply { failure = IllegalStateException("host gone") }

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_UNAVAILABLE", result.detail)
        assertTrue(actions.executed.isEmpty())
    }

    @Test
    fun `a rejected precheck never asks and never executes`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.Reject, reason = "INVALID_ARGUMENTS"))
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("SHELL_COMMAND_REJECTED", result.detail)
        assertTrue(actions.executed.isEmpty())
        assertTrue(confirmation.requested.isEmpty(), "a rejection is not a question")
    }

    @Test
    fun `a stop during the wait refuses and never executes`() {
        // 急停/STOP settles the live question through the plugin's own cancelAll path, which answers CANCEL.
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation(ConfirmationOutcome(false, ConfirmationSource.CANCEL))

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_DENIED", result.detail)
        assertTrue(actions.executed.isEmpty(), "a cancelled question must not execute the command")
    }

    @Test
    fun `one execute asks at most once and a denial leaves no approval behind`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation().apply {
            outcomes += ConfirmationOutcome(false, ConfirmationSource.PHONE)
            outcomes += ConfirmationOutcome(true, ConfirmationSource.PHONE)
        }
        val subject = wrapper(actions, confirmation)

        val denied = subject.execute("shell", args)
        val retried = subject.execute("shell", args)

        assertEquals("CONFIRMATION_DENIED", denied.detail)
        assertEquals(ActionOutcome.Done, retried.outcome)
        assertEquals(2, confirmation.requested.size,
            "a second call is a new action and asks its own question")
        assertEquals(1, actions.executed.size, "only the approved call runs the command")
    }

    @Test
    fun `descriptors cancel and beginTurn are passed through untouched`() {
        val actions = FakeActions()
        val subject = wrapper(actions)

        assertEquals(listOf(DESCRIPTOR), subject.descriptors())
        subject.cancel()
        subject.beginTurn()

        assertEquals(1, actions.cancels)
        assertEquals(1, actions.begins)
    }

    private companion object {
        val DESCRIPTOR = ActionDescriptor(
            name = "shell",
            title = "终端命令",
            parametersJson = """{"type":"object","properties":{"command":{"type":"string"}}}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "device.shell",
        )
    }
}
