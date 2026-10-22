package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ConfirmationGate
import com.sheen.rokid.agent.ConfirmationOutcome
import com.sheen.rokid.agent.ConfirmationPort
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.phone.plugin.smarthelper.confirmation.SmartConfirmation
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolOutcome
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        var precheckCalls = 0
        var precheckFailure: RuntimeException? = null

        override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

        override fun execute(name: String, argsJson: String): ActionResult {
            executed += name to argsJson
            return ActionResult(ActionOutcome.Done, "executed")
        }

        override fun cancel() { cancels++ }
        override fun beginTurn() { begins++ }
        override fun precheck(name: String, argsJson: String): ActionPrecheck {
            precheckCalls += 1
            precheckFailure?.let { throw it }
            return precheck
        }
    }

    private class FakeConfirmation(
        var outcome: ConfirmationOutcome = ConfirmationOutcome(true, ConfirmationSource.PHONE),
    ) : ConfirmationPort {
        val requested = mutableListOf<Pair<String, String>>()
        /** 0.3 Phase 4B (B7): the action name the wrapper reported with each question. */
        val tools = mutableListOf<String>()
        val outcomes = ArrayDeque<ConfirmationOutcome>()
        var failure: RuntimeException? = null

        override fun request(requestId: String, summary: String): ConfirmationOutcome {
            failure?.let { throw it }
            requested += requestId to summary
            return outcomes.removeFirstOrNull() ?: outcome
        }

        override fun request(requestId: String, summary: String, tool: String): ConfirmationOutcome {
            tools += tool
            return request(requestId, summary)
        }
    }

    private fun wrapper(
        actions: FakeActions = FakeActions(),
        confirmation: FakeConfirmation = FakeConfirmation(),
        canAsk: Boolean = true,
        requestIds: MutableList<String> = mutableListOf("request-1"),
        noInterception: () -> Boolean = { false },
        diagnostics: (String) -> Unit = { },
    ): ConfirmingActions = ConfirmingActions(
        host = actions,
        confirmation = confirmation,
        canAsk = { canAsk },
        newRequestId = { requestIds.removeFirstOrNull() ?: "request-fallback" },
        diagnostics = diagnostics,
        noInterception = noInterception,
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
        assertEquals(listOf("shell"), confirmation.tools,
            "B7: the host needs the tool's wire name to count confirmations per tool")
    }

    @Test
    fun `no interception executes directly with neither precheck nor confirmation`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation, canAsk = false, noInterception = { true }).execute("shell", args)

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf("shell" to args), actions.executed)
        assertEquals(0, actions.precheckCalls, "the mode skips the host's classifier entirely")
        assertTrue(confirmation.requested.isEmpty(), "no-interception never raises a confirmation")
    }

    @Test
    fun `the no interception bypass is read live on every execute`() {
        var enabled = false
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.Allow))
        val subject = wrapper(actions, noInterception = { enabled })

        subject.execute("shell", args)
        enabled = true
        subject.execute("shell", args)

        assertEquals(1, actions.precheckCalls, "only the gate-off call consults the classifier")
        assertEquals(listOf("shell" to args, "shell" to args), actions.executed)
    }

    @Test
    fun `a phone or voice denial refuses without executing`() {
        listOf(ConfirmationSource.PHONE, ConfirmationSource.VOICE).forEach { source ->
            val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
            val confirmation = FakeConfirmation(ConfirmationOutcome(false, source))

            val result = wrapper(actions, confirmation).execute("shell", args)

            assertEquals(ActionOutcome.Refused, result.outcome)
            assertEquals("CONFIRMATION_DENIED", result.detail)
            assertRefusalFacts(result, "CONFIRMATION_DENIED", recoverable = false)
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
        assertRefusalFacts(result, "CONFIRMATION_TIMEOUT", recoverable = false)
        assertTrue(actions.executed.isEmpty(), "silence is not consent")
    }

    @Test
    fun `no confirmation channel is unavailable rather than unasked execution`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation, canAsk = false).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_UNAVAILABLE", result.detail)
        assertRefusalFacts(result, "CONFIRMATION_UNAVAILABLE", recoverable = true)
        assertTrue(actions.executed.isEmpty())
        assertTrue(confirmation.requested.isEmpty(), "a missing channel means there is nobody to ask")
    }

    /**
     * 0.3 Phase 4B (B7): the shell path's no-channel decision is counted too. Nobody was asked, so the line says
     * `unavailable`/`none`; it is not a denial by the wearer and it must not be read as one.
     */
    @Test
    fun `a missing confirmation channel emits one unavailable P4_CONFIRM line`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val lines = mutableListOf<String>()

        wrapper(actions, canAsk = false, diagnostics = { lines += it }).execute("shell", args)

        assertEquals(
            listOf("P4_CONFIRM tool=shell decision=unavailable channel=none"),
            lines.filter { it.startsWith("P4_CONFIRM") },
            lines.toString(),
        )
    }

    @Test
    fun `a presentation failure refuses as unavailable rather than denied`() {
        // No channel and a `present` that could not display the question are both "there was nobody to ask", which
        // R52 maps to CONFIRMATION_UNAVAILABLE; only a question the wearer actually saw and denied is DENIED.
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation(ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE))

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_UNAVAILABLE", result.detail)
        assertRefusalFacts(result, "CONFIRMATION_UNAVAILABLE", recoverable = true)
        assertTrue(actions.executed.isEmpty(), "an unpresentable question must never run the command")
        assertEquals(1, confirmation.requested.size, "the policy asked; presenting is what failed")
    }

    @Test
    fun `a broken channel refuses as unavailable instead of throwing into the loop`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val confirmation = FakeConfirmation().apply { failure = IllegalStateException("host gone") }

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_UNAVAILABLE", result.detail)
        assertRefusalFacts(result, "CONFIRMATION_UNAVAILABLE", recoverable = true)
        assertTrue(actions.executed.isEmpty())
    }

    @Test
    fun `a precheck that cannot answer refuses with recoverable facts and never executes`() {
        // A host that cannot classify the call is not an approval: it refuses rather than falling through to execute.
        val actions = FakeActions().apply { precheckFailure = IllegalStateException("classifier gone") }
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("SHELL_COMMAND_REJECTED", result.detail)
        assertRefusalFacts(result, "SHELL_COMMAND_REJECTED", recoverable = true)
        assertTrue(actions.executed.isEmpty())
        assertTrue(confirmation.requested.isEmpty(), "an unanswerable precheck must not become a question")
    }

    @Test
    fun `a rejected precheck never asks and never executes`() {
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.Reject, reason = "INVALID_ARGUMENTS"))
        val confirmation = FakeConfirmation()

        val result = wrapper(actions, confirmation).execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("SHELL_COMMAND_REJECTED", result.detail)
        assertRefusalFacts(result, "SHELL_COMMAND_REJECTED", recoverable = false)
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
    fun `a real question cancelled while it waits refuses the tool as denied`() {
        // The R128 seam at the layer that runs the tool: the plugin's own `SmartConfirmation.cancelAll()` settles the
        // wait as CANCEL, so the wrapper refuses the command instead of executing the approval that arrives later.
        val actions = FakeActions(ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "危险"))
        val clock = SmartFakeClock()
        val gate = ConfirmationGate(clock, SmartFakeScheduler(clock), timeoutNanos = 120_000_000_000L)
        lateinit var confirmation: SmartConfirmation
        val channel = object : ConfirmationChannel {
            override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
                // STOP lands while the question is on screen.
                confirmation.cancelAll()
                return object : CancellationHandle { override fun cancel() = Unit }
            }
        }
        confirmation = SmartConfirmation(channel = channel, gate = gate)
        val subject = ConfirmingActions(
            host = actions,
            confirmation = confirmation,
            canAsk = { true },
            diagnostics = { },
        )

        val result = subject.execute("shell", args)

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CONFIRMATION_DENIED", result.detail)
        assertTrue(actions.executed.isEmpty(), "a cancelled question must never run the command")
        assertEquals(0, confirmation.pendingCount, "the cancelled question must not stay armed")
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

    /**
     * edith-0.3 Phase 2.2 Task 6 review: a refusal this wrapper synthesizes must carry the structured facts the model
     * reads.
     *
     * Without them the code lived only in `detail` and the projected execution reported
     * `code`/`accepted`/`applied = null`, so "refused, nothing ran" was indistinguishable from "unknown".
     */
    private fun assertRefusalFacts(result: ActionResult, code: String, recoverable: Boolean) {
        val facts = assertNotNull(result.execution, "a wrapper refusal must carry execution facts ($code)")
        assertEquals(ToolOutcome.Refused, facts.outcome)
        assertEquals(code, facts.code)
        assertEquals(false, facts.accepted)
        assertEquals(false, facts.applied)
        assertNull(facts.verified)
        assertNull(facts.changed)
        assertEquals(recoverable, facts.recoverable)
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
