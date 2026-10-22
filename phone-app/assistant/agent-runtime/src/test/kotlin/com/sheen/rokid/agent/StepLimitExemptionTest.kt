package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The step-limit exemption (Phase 6, design D-9 / AC-18).
 *
 * `maxSteps` is a hang guard, not a budget: a tool that only *reads* reference material has no side effect and is
 * idempotent, so it must not consume the run's step allowance. The exemption is **declared on the descriptor** — the
 * runtime may not single out a tool by name — and this test drives the loop through the declaration, not through a
 * production tool's identity.
 *
 * Three properties are load bearing here, and each has a case below:
 * - the gate counts **declared** calls only (an exempt tool may be pulled past `maxSteps`);
 * - the gate is still a gate for everyone else (a non-exempt tool, and an unknown name that declared nothing, still
 *   stop at `maxSteps` in the same loop configuration) — the exemption is not the removal of the guard;
 * - `steps` stays the **full record** of tool calls, with contiguous indices, including exempt ones: two production
 *   consumers (`P3_RUN … steps=` and the eval trigger's `toolCalls`) read `steps.size` as "how many tools the model
 *   called", so recording is a different concern from gating. `spentSteps` is the gate's own number.
 */
class StepLimitExemptionTest {

    private fun request() = AgentRequest(
        turnId = "turn-step-limit",
        utterance = "把相关的 SKILL 都取来",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = Long.MAX_VALUE,
    )

    private fun newLoop(gateway: ModelGateway, registry: ToolRegistry, maxSteps: Int) =
        AgentLoop(
            gateway,
            registry,
            ScriptedPolicy(),
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            maxEmptyTurns = 3,
            maxSteps = maxSteps,
        )

    /**
     * A tool that declares the exemption and answers with an advancing counter receipt, the same shape the real
     * `load_skill` uses: a constant payload would make three consecutive pulls look like `NO_PROGRESS` and end the run
     * before the step gate could be tested at all.
     */
    private fun exemptExecutor(name: String = EXEMPT_TOOL): RecordingActionExecutor {
        val executor = RecordingActionExecutor(listOf(actionDescriptor(name, countsTowardStepLimit = false)))
        var pulls = 0
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "loaded:${++pulls}") }
        return executor
    }

    @Test fun `an exempt tool may be called more times than maxSteps without STEP_LIMIT`() {
        val gateway = ScriptedGateway(
            List(6) { reply(calls = listOf(call("c$it", EXEMPT_TOOL, """{"skill":"s$it"}"""))) } + reply(text = "资料齐了"),
        )

        val run = newLoop(gateway, ToolRegistry(listOf(exemptExecutor())), maxSteps = 2).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertNull(run.safeCode, "an exempt tool must never reach the step limit")
        assertEquals(6, run.steps.size, "every exempt call happened")
        assertEquals(7, gateway.requests.size, "all six pulls ran; the seventh turn is the terminal text")
    }

    @Test fun `a non-exempt tool is still stopped at maxSteps in the same loop configuration`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor(COUNTED_TOOL)))
        val gateway = ScriptedGateway(List(4) { reply(calls = listOf(call("c$it", COUNTED_TOOL))) })

        val run = newLoop(gateway, ToolRegistry(listOf(executor)), maxSteps = 2).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("STEP_LIMIT", run.safeCode)
        assertEquals(2, run.steps.size, "the call past the limit must not run or be recorded")
        assertEquals(2, executor.calls.size)
    }

    @Test fun `an exempt call still appears in run steps with a contiguous index`() {
        val gateway = ScriptedGateway(
            List(6) { reply(calls = listOf(call("c$it", EXEMPT_TOOL, """{"skill":"s$it"}"""))) } + reply(text = "资料齐了"),
        )

        val run = newLoop(gateway, ToolRegistry(listOf(exemptExecutor())), maxSteps = 2).run(request())

        assertEquals(listOf(1, 2, 3, 4, 5, 6), run.steps.map { it.index })
        assertEquals(List(6) { EXEMPT_TOOL }, run.steps.map { it.action })
    }

    @Test fun `spentSteps counts only the declared calls`() {
        val exemptRun = newLoop(
            ScriptedGateway(List(6) { reply(calls = listOf(call("c$it", EXEMPT_TOOL, """{"skill":"s$it"}"""))) } + reply(text = "资料齐了")),
            ToolRegistry(listOf(exemptExecutor())),
            maxSteps = 2,
        ).run(request())

        assertEquals(0, exemptRun.spentSteps, "no declared call was made, so the gate saw no step")

        val countedRun = newLoop(
            ScriptedGateway(List(4) { reply(calls = listOf(call("c$it", COUNTED_TOOL))) }),
            ToolRegistry(listOf(RecordingActionExecutor(listOf(actionDescriptor(COUNTED_TOOL))))),
            maxSteps = 2,
        ).run(request())

        assertEquals(2, countedRun.spentSteps, "a non-exempt call spends one step and the run stops at the limit")
    }

    @Test fun `an unknown tool name still counts against the limit`() {
        // Nothing declared the name exempt — an unknown name is a step fed back to the model, and it spends a step.
        val gateway = ScriptedGateway(List(4) { reply(calls = listOf(call("c$it", "no_such_tool"))) })

        val run = newLoop(gateway, ToolRegistry(emptyList()), maxSteps = 2).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("STEP_LIMIT", run.safeCode)
        assertEquals(2, run.steps.size)
        assertEquals(2, run.spentSteps)
    }

    @Test fun `the descriptor default keeps counting toward the limit`() {
        // The default is "today's behaviour": a descriptor that says nothing spends its step.
        val descriptor = ActionDescriptor(
            name = "unspecified",
            title = "Unspecified",
            parametersJson = """{"type":"object"}""",
            risk = RiskLevel.L0,
            requiresConfirmation = false,
            idempotent = true,
            reversible = true,
            scope = "test",
        )

        assertTrue(descriptor.countsTowardStepLimit, "an undeclared action counts; the default must reproduce today")
    }

    private companion object {
        const val EXEMPT_TOOL = "read_reference"
        const val COUNTED_TOOL = "device.set_volume"
    }
}
