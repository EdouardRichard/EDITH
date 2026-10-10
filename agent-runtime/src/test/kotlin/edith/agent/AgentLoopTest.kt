package edith.agent

import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.MessageRole
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.MonotonicClock
import edith.plugin.RiskLevel
import edith.plugin.ToolSpec
import edith.testsupport.FakeClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLoopTest {

    private fun request(deadlineNanos: Long = Long.MAX_VALUE) = AgentRequest(
        turnId = "turn-7",
        utterance = "把音量调小一点",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = deadlineNanos,
    )

    private fun newLoop(
        gateway: ModelGateway,
        registry: ToolRegistry = ToolRegistry(emptyList()),
        policy: ActionPolicy = ScriptedPolicy(),
        guard: ProgressGuard = ProgressGuard(),
        audit: AuditSink = RecordingAuditSink(),
        clock: MonotonicClock = FakeClock(),
        maxEmptyTurns: Int = 3,
        maxSteps: Int = 24,
        confirmation: ConfirmationPort? = null,
    ) = AgentLoop(gateway, registry, policy, guard, audit, clock, maxEmptyTurns, maxSteps, confirmation)

    // ---------------------------------------------------------------- turn state

    @Test fun `an incomplete turn is retried and counted until insufficient`() {
        val gateway = ScriptedGateway(List(3) { reply(text = "partial", terminal = ModelEnd.INCOMPLETE) })

        val run = newLoop(gateway).run(request())

        assertEquals(AgentOutcome.INSUFFICIENT, run.outcome)
        assertEquals("INCOMPLETE_TURNS", run.safeCode)
        assertNull(run.finalText)
        assertEquals(3, gateway.requests.size)
        val retried = gateway.requests[1].history
        assertTrue(ModelHistoryItem.Text(MessageRole.ASSISTANT, "partial") in retried)
        val retryInstruction = assertIs<ModelHistoryItem.Text>(retried.last())
        assertEquals(MessageRole.USER, retryInstruction.role)
        assertTrue(retryInstruction.text.isNotBlank())
    }

    @Test fun `an incomplete turn keeps the gateway safe code as the insufficient reason`() {
        val gateway = ScriptedGateway(
            List(3) { reply(text = "cut off", terminal = ModelEnd.INCOMPLETE, safeCode = "max_output_tokens") },
        )

        val run = newLoop(gateway).run(request())

        assertEquals(AgentOutcome.INSUFFICIENT, run.outcome)
        assertEquals("max_output_tokens", run.safeCode)
    }

    @Test fun `a failed terminal ends the run immediately with the gateway safe code`() {
        val gateway = ScriptedGateway(listOf(reply(text = "x", terminal = ModelEnd.FAILED, safeCode = "provider_error")))

        val run = newLoop(gateway).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("provider_error", run.safeCode)
        assertNull(run.finalText)
        assertEquals(1, gateway.requests.size)
    }

    @Test fun `a deadline or cancel terminal ends the run with that outcome`() {
        listOf(ModelEnd.DEADLINE to AgentOutcome.DEADLINE, ModelEnd.CANCELLED to AgentOutcome.CANCELLED)
            .forEach { (terminal, expected) ->
                val gateway = ScriptedGateway(listOf(reply(terminal = terminal, safeCode = "wire_${terminal.name}")))

                val run = newLoop(gateway).run(request())

                assertEquals(expected, run.outcome, "terminal=$terminal")
                assertEquals("wire_${terminal.name}", run.safeCode)
            }
    }

    @Test fun `an empty turn is retried and counted until insufficient`() {
        val gateway = ScriptedGateway(List(3) { reply(text = "") })

        val run = newLoop(gateway).run(request())

        assertEquals(AgentOutcome.INSUFFICIENT, run.outcome)
        assertEquals("EMPTY_TURN", run.safeCode)
        assertEquals(3, gateway.requests.size)
        val retried = gateway.requests[1].history
        assertTrue(ModelHistoryItem.Text(MessageRole.ASSISTANT, "(empty turn)") in retried)
        assertEquals(MessageRole.USER, assertIs<ModelHistoryItem.Text>(retried.last()).role)
    }

    @Test fun `a retry after an empty turn appends the assistant placeholder then the retry instruction`() {
        val gateway = ScriptedGateway(listOf(reply(text = ""), reply(text = "answer")))

        val run = newLoop(gateway).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val history = gateway.requests[1].history
        assertEquals(3, history.size)
        assertEquals(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点"), history[0])
        assertEquals(ModelHistoryItem.Text(MessageRole.ASSISTANT, "(empty turn)"), history[1])
        val retry = assertIs<ModelHistoryItem.Text>(history[2])
        assertEquals(MessageRole.USER, retry.role)
        assertTrue(retry.text.isNotBlank())
    }

    @Test fun `a retry never leaves the transcript on the pending user turn`() {
        val gateway = ScriptedGateway(listOf(reply(text = ""), reply(text = "answer")))
        val loop = newLoop(gateway)

        val run = loop.run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val last = assertIs<ModelHistoryItem.Text>(loop.lastTranscript.last())
        assertEquals(MessageRole.ASSISTANT, last.role)
        assertEquals("answer", last.text)
    }

    @Test fun `an insufficient run never leaves the transcript on the pending user turn`() {
        val gateway = ScriptedGateway(List(3) { reply(text = "") })
        val loop = newLoop(gateway)

        val run = loop.run(request())

        assertEquals(AgentOutcome.INSUFFICIENT, run.outcome)
        val last = assertIs<ModelHistoryItem.Text>(loop.lastTranscript.last())
        assertEquals(MessageRole.ASSISTANT, last.role)
    }

    @Test fun `maxEmptyTurns is honoured`() {
        val gateway = ScriptedGateway(List(2) { reply(text = "") })

        val run = newLoop(gateway, maxEmptyTurns = 2).run(request())

        assertEquals(AgentOutcome.INSUFFICIENT, run.outcome)
        assertEquals("EMPTY_TURN", run.safeCode)
        assertEquals(2, gateway.requests.size)
    }

    @Test fun `a productive tool turn resets the empty counter`() {
        // Two empty turns, a productive tool turn, then two more empty turns. Without the reset the third empty turn
        // would have been the third consecutive unproductive turn and the run would be INSUFFICIENT after 3 turns.
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(text = ""),
                reply(text = ""),
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = ""),
                reply(text = ""),
                reply(text = "answer"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("answer", run.finalText)
        assertEquals(6, gateway.requests.size)
    }

    @Test fun `a productive tool turn resets the incomplete counter`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "cut", terminal = ModelEnd.INCOMPLETE),
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = "cut", terminal = ModelEnd.INCOMPLETE),
                reply(text = "cut", terminal = ModelEnd.INCOMPLETE),
                reply(text = "answer"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("answer", run.finalText)
        assertEquals(5, gateway.requests.size)
    }

    @Test fun `an incomplete turn with tool calls executes its steps and is not counted as an unproductive turn`() {
        // A cut-off turn can still carry a real call: its steps are real work, so they run and the turn is not the
        // "empty turn" the retry counter counts.
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "cut off", terminal = ModelEnd.INCOMPLETE, calls = listOf(call("c1", "lookup"))),
                reply(text = "answer"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("answer", run.finalText)
        assertEquals(listOf(AgentStep(1, "lookup", "ok")), run.steps)
        assertEquals(listOf("lookup" to "{}"), executor.calls)
        assertEquals(2, gateway.requests.size)
    }

    // ---------------------------------------------------------------- request shape

    @Test fun `the first turn carries the utterance as the opening user turn`() {
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))

        newLoop(gateway).run(request())

        assertEquals(
            listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点")),
            gateway.requests.single().history,
        )
    }

    @Test fun `the gateway receives the instructions, tools, turn id and deadline of the request`() {
        val executor = executorOf("device.set_volume")
        val gateway = ScriptedGateway(listOf(reply(text = "ok")))

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(deadlineNanos = 4_242))

        val turn = gateway.requests.single()
        assertEquals("turn-7", turn.turnId)
        assertEquals("deepseek-default", turn.profileId)
        assertEquals("You are Edith.", turn.instructions)
        assertEquals(4_242, turn.deadlineNanos)
        assertEquals(
            listOf(ToolSpec("device.set_volume", "Title of device.set_volume", """{"type":"object"}""")),
            turn.tools,
        )
    }

    @Test fun `a text turn with no tool calls completes with that text as the final answer`() {
        val gateway = ScriptedGateway(listOf(reply(text = "现在音量是 5")))

        val run = newLoop(gateway).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("现在音量是 5", run.finalText)
        assertNull(run.safeCode)
        assertTrue(run.steps.isEmpty())
    }

    // ---------------------------------------------------------------- tool steps

    @Test fun `an allowed tool call is one ok step and its output is fed back after the call`() {
        val executor = executorOf("device.get_volume")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "volume=5") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("call-1", "device.get_volume"))), reply(text = "音量是 5")),
        )
        val audit = RecordingAuditSink()

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), audit = audit).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("音量是 5", run.finalText)
        assertEquals(listOf(AgentStep(1, "device.get_volume", "ok")), run.steps)
        assertEquals(listOf("device.get_volume" to "{}"), executor.calls)
        val second = gateway.requests[1].history
        // The assistant text opens the tool exchange: a call run must never start a history group on its own.
        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "(tool call)"),
                ModelHistoryItem.ToolCall("call-1", "device.get_volume", "{}"),
                ModelHistoryItem.ToolOutput("call-1", "volume=5"),
            ),
            second,
        )
        assertEquals(RecordedAuditEntry("turn-7", "device.get_volume", "executed", "ok"), audit.entries.single())
    }

    @Test fun `every call of a turn is echoed before any of its outputs, in call order`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("a"), actionDescriptor("b")))
        executor.outcome = { name, _ -> ActionResult(ActionOutcome.Done, "payload-$name") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "a"), call("c2", "b"))), reply(text = "done")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "(tool call)"),
                ModelHistoryItem.ToolCall("c1", "a", "{}"),
                ModelHistoryItem.ToolCall("c2", "b", "{}"),
                ModelHistoryItem.ToolOutput("c1", "payload-a"),
                ModelHistoryItem.ToolOutput("c2", "payload-b"),
            ),
            gateway.requests[1].history,
        )
        assertEquals(listOf(AgentStep(1, "a", "ok"), AgentStep(2, "b", "ok")), run.steps)
    }

    @Test fun `a two round tool sequence renders as user assistant call output assistant call output`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("a"), actionDescriptor("b")))
        executor.outcome = { name, _ -> ActionResult(ActionOutcome.Done, "payload-$name") }
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "first", calls = listOf(call("c1", "a"))),
                reply(text = "second", calls = listOf(call("c2", "b"))),
                reply(text = "done"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("done", run.finalText)
        // Two assistant tool rounds: each round's assistant text precedes that round's call, and the second round is a
        // new group rather than a continuation of the first.
        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "first"),
                ModelHistoryItem.ToolCall("c1", "a", "{}"),
                ModelHistoryItem.ToolOutput("c1", "payload-a"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "second"),
                ModelHistoryItem.ToolCall("c2", "b", "{}"),
                ModelHistoryItem.ToolOutput("c2", "payload-b"),
            ),
            gateway.requests[2].history,
        )
        assertEquals(listOf(AgentStep(1, "a", "ok"), AgentStep(2, "b", "ok")), run.steps)
    }

    @Test fun `a gateway supplied item id reaches the echoed history call`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("call-1", "lookup", itemId = "fc_1"))), reply(text = "done")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        val echoed = gateway.requests[1].history.filterIsInstance<ModelHistoryItem.ToolCall>().single()
        assertEquals("call-1", echoed.callId)
        assertEquals("fc_1", echoed.itemId)
        // A caller that does not know the endpoint's item id keeps the 3-argument form and the call id default.
        assertEquals("call-1", ToolCall("call-1", "lookup", "{}").itemId)
    }

    @Test fun `step indices count every call of the run from one`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"), call("c2", "lookup"))),
                reply(calls = listOf(call("c3", "lookup"))),
                reply(text = "done"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(listOf(1, 2, 3), run.steps.map { it.index })
    }

    @Test fun `an unknown tool is a step that never reaches the policy`() {
        val policy = ScriptedPolicy()
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "nope"))), reply(text = "改用别的工具")),
        )

        val run = newLoop(gateway, policy = policy, audit = audit).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(listOf(AgentStep(1, "nope", "UNKNOWN_TOOL")), run.steps)
        assertTrue(policy.seen.isEmpty(), "an unknown name must never reach the policy")
        assertEquals(ModelHistoryItem.ToolOutput("c1", "UNKNOWN_TOOL"), gateway.requests[1].history.last())
        // An unresolved name was never executed, so the audit summary must not reuse the execution label asserted by
        // `an allowed tool call is one ok step...`; the outcome keeps the stable UNKNOWN_TOOL safe code.
        assertEquals(RecordedAuditEntry("turn-7", "nope", "UnknownTool", "UNKNOWN_TOOL"), audit.entries.single())
    }

    @Test fun `a policy refusal is a step that never reaches the executor`() {
        val executor = executorOf("device.set_volume")
        val policy = ScriptedPolicy(mapOf("device.set_volume" to PolicyDecision.Refuse))
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "算了")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), policy = policy, audit = audit).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(listOf(AgentStep(1, "device.set_volume", "REFUSED_BY_POLICY")), run.steps)
        assertTrue(executor.calls.isEmpty())
        assertEquals(RecordedAuditEntry("turn-7", "device.set_volume", "policy", "Refused"), audit.entries.single())
        assertEquals(ModelHistoryItem.ToolOutput("c1", "REFUSED_BY_POLICY"), gateway.requests[1].history.last())
    }

    @Test fun `a confirmation-gated action is refused as unavailable and the loop continues`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("device.set_volume", requiresConfirmation = true)))
        val policy = ScriptedPolicy(mapOf("device.set_volume" to PolicyDecision.RequireConfirmation("调低音量?")))
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device.set_volume"))),
                reply(calls = listOf(call("c2", "device.set_volume"))),
                reply(text = "没有确认通道，先不动"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), policy = policy, audit = audit).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(
            listOf(
                AgentStep(1, "device.set_volume", "CONFIRMATION_UNAVAILABLE"),
                AgentStep(2, "device.set_volume", "CONFIRMATION_UNAVAILABLE"),
            ),
            run.steps,
        )
        assertTrue(executor.calls.isEmpty(), "an unconfirmed action must never execute")
        assertEquals(
            listOf(
                RecordedAuditEntry("turn-7", "device.set_volume", "policy", "ConfirmationUnavailable"),
                RecordedAuditEntry("turn-7", "device.set_volume", "policy", "ConfirmationUnavailable"),
            ),
            audit.entries,
        )
    }

    @Test fun `an approved confirmation executes the action exactly once`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("device.set_volume")))
        val confirmation = ScriptedConfirmation(ConfirmationOutcome(true, ConfirmationSource.PHONE))
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "调低了")),
        )

        val run = newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("device.set_volume" to PolicyDecision.RequireConfirmation("调低音量?"))),
            confirmation = confirmation,
        ).run(request())

        // The port is addressed by the tool call id, not the tool name: the answer event has to be addressable back to
        // the exact in-flight call.
        assertEquals(listOf("c1" to "调低音量?"), confirmation.asked)
        assertEquals(1, executor.calls.size)
        assertEquals(listOf(AgentStep(1, "device.set_volume", "ok")), run.steps)
    }

    @Test fun `a denied confirmation never executes and feeds back CONFIRMATION_DENIED`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("app.uninstall",
            risk = RiskLevel.L3, scope = "app", auditTargetArgument = "package")))
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "app.uninstall", """{"package":"edith.phone.a11yprobe"}"""))),
                reply(text = "那就不动")),
        )

        val run = newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("卸载?"))),
            audit = audit,
            confirmation = ScriptedConfirmation(ConfirmationOutcome(false, ConfirmationSource.PHONE)),
        ).run(request())

        assertTrue(executor.calls.isEmpty(), "a denied action must never execute")
        assertEquals("CONFIRMATION_DENIED", run.steps.single().result)
        assertEquals(
            RecordedAuditEntry("turn-7", "app.uninstall", "CONFIRMATION_DENIED", "CONFIRMATION_DENIED"),
            audit.entries.single(),
        )
        assertEquals(ConfirmationSource.PHONE, audit.structured.single().confirmationSource)
    }

    @Test fun `a timed out confirmation never executes and feeds back CONFIRMATION_TIMEOUT`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("app.uninstall")))
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "app.uninstall"))), reply(text = "超时了")),
        )

        val run = newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("卸载?"))),
            confirmation = ScriptedConfirmation(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT)),
        ).run(request())

        assertTrue(executor.calls.isEmpty(), "an expired question must never execute")
        assertEquals("CONFIRMATION_TIMEOUT", run.steps.single().result)
    }

    @Test fun `the audit line carries the risk scope decision source and the declared audit target`() {
        val executor = RecordingActionExecutor(
            listOf(
                actionDescriptor(
                    "app.uninstall",
                    risk = RiskLevel.L3,
                    scope = "app",
                    auditTargetArgument = "package",
                    parametersJson = """{"type":"object","properties":{"package":{"type":"string"}}}""",
                ),
            ),
        )
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "app.uninstall", """{"package":"edith.phone.a11yprobe"}"""))),
                reply(text = "卸载了")),
        )

        newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("卸载?"))),
            audit = audit,
            confirmation = ScriptedConfirmation(ConfirmationOutcome(true, ConfirmationSource.VOICE)),
        ).run(request())

        assertEquals(
            RecordedAuditEntry("turn-7", "app.uninstall", "package=edith.phone.a11yprobe", "ok"),
            audit.entries.last(),
        )
        assertEquals(listOf("L3"), audit.structured.map { it.risk })
        assertEquals(listOf("app"), audit.structured.map { it.scope })
        assertEquals(listOf("require_confirmation"), audit.structured.map { it.decision })
        assertEquals(listOf(ConfirmationSource.VOICE), audit.structured.map { it.confirmationSource })
    }

    @Test fun `an action that declares no audit target records no argument at all`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("dial")))
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "dial", """{"number":"12345678901"}"""))), reply(text = "拨了")),
        )

        newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("dial" to PolicyDecision.RequireConfirmation("拨号?"))),
            audit = audit,
            confirmation = ScriptedConfirmation(ConfirmationOutcome(true, ConfirmationSource.PHONE)),
        ).run(request())

        val summary = audit.entries.last().summary
        assertFalse("12345678901" in summary, "a declared-safe argument is the only thing an audit line may carry")
        assertEquals("", summary)
    }

    @Test fun `a throwing tool is a step that keeps the loop going`() {
        val executor = executorOf("lookup")
        executor.failure = RuntimeException("boom")
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "recovered")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("recovered", run.finalText)
        assertEquals(listOf(AgentStep(1, "lookup", "TOOL_THREW")), run.steps)
    }

    // ---------------------------------------------------------------- terminal tools

    @Test fun `a successful non-terminal tool keeps the loop going`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "done")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("done", run.finalText)
        assertEquals(2, gateway.requests.size)
        assertEquals(1, run.steps.size)
    }

    @Test fun `only a successful terminal tool ends the run with its payload as the final text`() {
        val executor = terminalExecutor("report_done")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "最终答案") }
        val gateway = ScriptedGateway(listOf(reply(text = "thinking", calls = listOf(call("c1", "report_done")))))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("最终答案", run.finalText)
        assertEquals(listOf(AgentStep(1, "report_done", "ok")), run.steps)
        assertEquals(1, gateway.requests.size)
    }

    @Test fun `a terminal tool with invalid arguments is a step that does not end the run`() {
        val executor = terminalExecutor("report_done")
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "report_done", "{ not json"))),
                reply(calls = listOf(call("c2", "report_done", "{}"))),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("done", run.finalText)
        assertEquals(
            listOf(AgentStep(1, "report_done", "ARGUMENTS_INVALID"), AgentStep(2, "report_done", "ok")),
            run.steps,
        )
        assertEquals(listOf("report_done" to "{}"), executor.calls, "the invalid call must not reach the executor")
    }

    @Test fun `a terminal tool that fails is a step that does not end the run`() {
        val executor = terminalExecutor("report_done")
        var attempt = 0
        executor.outcome = { _, _ ->
            attempt++
            if (attempt == 1) ActionResult(ActionOutcome.Failed, "missing field") else ActionResult(ActionOutcome.Done, "answer")
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "report_done"))),
                reply(calls = listOf(call("c2", "report_done"))),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("answer", run.finalText)
        assertEquals(
            listOf(AgentStep(1, "report_done", "Failed: missing field"), AgentStep(2, "report_done", "ok")),
            run.steps,
        )
    }

    // ---------------------------------------------------------------- no-progress guard

    @Test fun `three identical tool payloads end the run as no progress`() {
        val executor = executorOf("lookup")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "same-screen") }
        val gateway = ScriptedGateway(List(4) { reply(calls = listOf(call("c$it", "lookup"))) })

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("NO_PROGRESS", run.safeCode)
        assertEquals(3, run.steps.size)
        assertEquals(3, gateway.requests.size)
    }

    @Test fun `a changed tool payload resets the no progress streak`() {
        val executor = executorOf("lookup")
        var attempt = 0
        executor.outcome = { _, _ ->
            attempt++
            ActionResult(ActionOutcome.Done, if (attempt == 3) "changed" else "same")
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(calls = listOf(call("c2", "lookup"))),
                reply(calls = listOf(call("c3", "lookup"))),
                reply(calls = listOf(call("c4", "lookup"))),
                reply(text = "finished"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("finished", run.finalText)
        assertEquals(4, run.steps.size)
    }

    @Test fun `a run with no tool calls never observes the guard`() {
        // A guard with limit 1 trips on any observation: a text-only run must never reach it, because the guard may
        // only observe state that exists after an action.
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))

        val run = newLoop(gateway, guard = ProgressGuard(limit = 1)).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
    }

    @Test fun `a guard carrying two identical observations does not trip a run whose first observation differs`() {
        val guard = ProgressGuard()
        guard.observeAfterAction("stale-screen")
        guard.observeAfterAction("stale-screen")
        val executor = executorOf("lookup")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "fresh-screen") }
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "done")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), guard = guard).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("done", run.finalText)
        assertFalse(guard.tripped)
    }

    @Test fun `a guard carrying the previous run's streak does not trip this run's first observation`() {
        val guard = ProgressGuard()
        guard.observeAfterAction("same-screen")
        guard.observeAfterAction("same-screen")
        // Without a per-run reset this first observation is the third consecutive identical fingerprint and the run
        // ends NO_PROGRESS before it gets anywhere.
        val executor = executorOf("lookup")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "same-screen") }
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "done")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), guard = guard).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("done", run.finalText)
        assertEquals(listOf(AgentStep(1, "lookup", "ok")), run.steps)
        assertFalse(guard.tripped)
    }

    // ---------------------------------------------------------------- run result and reuse

    @Test @Suppress("UNCHECKED_CAST")
    fun `a run hands out a defensive copy of its steps`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "done")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(listOf(AgentStep(1, "lookup", "ok")), run.steps)
        // The returned list must be a copy, not the loop's own mutable list: a caller may not write through it.
        assertFailsWith<UnsupportedOperationException> {
            (run.steps as MutableList<AgentStep>).add(AgentStep(2, "forged", "ok"))
        }
        assertEquals(listOf(AgentStep(1, "lookup", "ok")), run.steps)
    }

    @Test fun `a second run on the same loop instance is rejected`() {
        val gateway = ScriptedGateway(listOf(reply(text = "first"), reply(text = "second")))
        val loop = newLoop(gateway)

        assertEquals(AgentOutcome.COMPLETED, loop.run(request()).outcome)

        val failure = assertFailsWith<IllegalStateException> { loop.run(request()) }

        assertTrue(
            "one loop instance serves one run" in failure.message.orEmpty(),
            "message: ${failure.message}",
        )
        assertEquals(1, gateway.requests.size, "the rejected run must not reach the gateway")
    }

    // ---------------------------------------------------------------- deadline, cancel, step limit

    @Test fun `a deadline already passed ends the run before the first turn`() {
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))

        val run = newLoop(gateway, clock = FakeClock(nowNanos = 100)).run(request(deadlineNanos = 100))

        assertEquals(AgentOutcome.DEADLINE, run.outcome)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test fun `a deadline reached during a step ends the run as deadline`() {
        val executor = executorOf("lookup")
        val clock = FakeClock(nowNanos = 0)
        executor.outcome = { _, _ ->
            clock.advance(10)
            ActionResult(ActionOutcome.Done, "payload")
        }
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "never")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), clock = clock)
            .run(request(deadlineNanos = 5))

        assertEquals(AgentOutcome.DEADLINE, run.outcome)
        assertEquals(1, run.steps.size)
        assertEquals(1, gateway.requests.size)
    }

    @Test fun `cancel during a step ends the run as cancelled at the next check`() {
        val executor = executorOf("lookup")
        lateinit var agent: AgentLoop
        executor.outcome = { _, _ ->
            agent.cancel()
            ActionResult(ActionOutcome.Done, "payload")
        }
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "never")))
        agent = newLoop(gateway, registry = ToolRegistry(listOf(executor)))

        val run = agent.run(request())

        assertEquals(AgentOutcome.CANCELLED, run.outcome)
        assertEquals(1, run.steps.size)
        assertEquals(1, gateway.requests.size)
    }

    @Test fun `cancel while a gateway turn is blocked ends the run as cancelled`() {
        val insideTurn = CountDownLatch(1)
        lateinit var agent: AgentLoop
        val gateway = object : ModelGateway {
            override fun turn(request: GatewayTurn): GatewayReply {
                insideTurn.countDown()
                val giveUpAt = System.nanoTime() + 10_000_000_000L
                // Blocks until it observes the loop's own cancel flag — never until the test releases it.
                while (!agent.cancelPending && System.nanoTime() < giveUpAt) Thread.onSpinWait()
                return reply(text = "late answer")
            }
        }
        agent = newLoop(gateway)

        val finished = CountDownLatch(1)
        var outcome: AgentRun? = null
        val runner = thread(name = "agent-loop-test") {
            outcome = agent.run(request())
            finished.countDown()
        }

        assertTrue(insideTurn.await(5, TimeUnit.SECONDS), "the gateway turn must be entered")
        assertFalse(finished.await(150, TimeUnit.MILLISECONDS), "the run must still be blocked inside the gateway turn")
        agent.cancel()
        assertTrue(finished.await(5, TimeUnit.SECONDS), "cancel must not wait for the blocked gateway to finish on its own")
        assertEquals(AgentOutcome.CANCELLED, outcome?.outcome)
        runner.join(5_000)
    }

    @Test fun `more tool calls than maxSteps fails with the step limit`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(List(3) { reply(calls = listOf(call("c$it", "lookup"))) })

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), maxSteps = 2).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("STEP_LIMIT", run.safeCode)
        assertEquals(2, run.steps.size)
        assertEquals(2, executor.calls.size)
    }

    @Test fun `a run that reaches exactly the step limit is not failed`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(calls = listOf(call("c2", "lookup"))),
                reply(text = "done"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), maxSteps = 2).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("done", run.finalText)
        assertEquals(2, run.steps.size)
    }

    // ------------------------------------------------- the confirmation wait is not the turn's budget (D49)

    /** A confirmation channel that spends the **user's** time: the clock moves while it waits. */
    private class WaitingConfirmation(
        private val clock: FakeClock,
        private val waitNanos: Long,
        private val outcome: ConfirmationOutcome = ConfirmationOutcome(true, ConfirmationSource.PHONE),
        private val onWait: () -> Unit = {},
    ) : ConfirmationPort {
        override fun request(requestId: String, summary: String): ConfirmationOutcome {
            clock.nowNanos += waitNanos
            onWait()
            return outcome
        }
    }

    /** A gateway that spends **machine** time on every turn: that time is the loop's own budget and must still count. */
    private class ClockMovingGateway(
        private val clock: FakeClock,
        private val nanosPerTurn: Long,
        replies: List<GatewayReply>,
    ) : ModelGateway {
        private val inner = ScriptedGateway(replies)
        val requests: List<GatewayTurn> get() = inner.requests
        override fun turn(request: GatewayTurn): GatewayReply {
            clock.nowNanos += nanosPerTurn
            return inner.turn(request)
        }
    }

    private fun gatedExecutor() =
        RecordingActionExecutor(listOf(actionDescriptor("device.set_volume", requiresConfirmation = true)))

    private fun gatedPolicy() =
        ScriptedPolicy(mapOf("device.set_volume" to PolicyDecision.RequireConfirmation("调低音量?")))

    @Test fun `a confirmation that waits past the deadline does not end the turn`() {
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "已经调低了")),
        )

        val run = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock,
            confirmation = WaitingConfirmation(clock, waitNanos = 5_000_000_000L),
        ).run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "等人的时间不该算进回合预算")
        assertEquals(1, executor.calls.size, "批准过的动作必须真的执行")
        assertEquals(2, gateway.requests.size, "顺延之后模型还要能被再问一次")
    }

    @Test fun `a full-window confirmation still lets the turn finish`() {
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "已经调低了")),
        )

        val run = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock,
            confirmation = WaitingConfirmation(clock, waitNanos = 120_000_000_000L),
        ).run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "把 120 s 窗口用满也不该让回合变成超时")
        assertEquals(2, gateway.requests.size)
    }

    @Test fun `a cancel during the wait ends the turn as CANCELLED`() {
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "不该到达")),
        )
        lateinit var loop: AgentLoop
        val confirmation = WaitingConfirmation(clock, waitNanos = 5_000_000_000L, onWait = { loop.cancel() })
        loop = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock,
            confirmation = confirmation,
        )

        val run = loop.run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.CANCELLED, run.outcome, "等待期间被取消就是取消，顺延不得把它变成完成")
        assertEquals(1, gateway.requests.size, "取消之后不得再问模型")
    }

    @Test fun `machine time still spends the budget`() {
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ClockMovingGateway(
            clock, nanosPerTurn = 2_000_000_000L,
            replies = listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "不该到达")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock)
            .run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.DEADLINE, run.outcome, "只有等人的时间被排除，机器的时间仍要计入预算")
        assertEquals(1, gateway.requests.size)
    }
}
