package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolSpec
import com.sheen.rokid.plugin.ToolVision
import com.sheen.rokid.testsupport.FakeClock
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

    private fun request(
        deadlineNanos: Long = Long.MAX_VALUE,
        requestTimeoutNanos: Long = 0L,
        onTextDelta: ((String) -> Unit)? = null,
        onToolStatus: ((String, ToolPhase) -> Unit)? = null,
        history: List<ModelHistoryItem> = emptyList(),
        observationSource: ToolObservationSource? = null,
    ) = AgentRequest(
        turnId = "turn-7",
        utterance = "把音量调小一点",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = deadlineNanos,
        requestTimeoutNanos = requestTimeoutNanos,
        onTextDelta = onTextDelta,
        onToolStatus = onToolStatus,
        history = history,
        observationSource = observationSource,
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
        metricLine: (String) -> Unit = {},
    ) = AgentLoop(gateway, registry, policy, guard, audit, clock, maxEmptyTurns, maxSteps, confirmation, metricLine = metricLine)

    /**
     * The exact envelope the loop feeds back for a tool result. The envelope shape itself is pinned in
     * `ToolResultEnvelopeTest`/`ToolRegistryTest`; the loop tests only need the byte-identical string a result carries.
     */
    private fun envelope(execution: ToolExecution, data: String): String =
        ToolResultEnvelope.encode(execution, observation = null, data = data)

    private fun doneEnvelope(data: String): String = envelope(ToolExecution(outcome = ToolOutcome.Done), data)

    private fun errorEnvelope(code: String): String = envelope(
        ToolExecution(
            outcome = ToolOutcome.Failed,
            code = code,
            accepted = false,
            applied = false,
        ),
        data = "",
    )

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

    @Test fun `a custom retry instruction is the one the model receives`() {
        // 0.3 Phase 4A A7: the retry prose is injected, and production passes `PromptCatalog.loop("retry")` here.
        val gateway = ScriptedGateway(listOf(reply(text = ""), reply(text = "answer")))
        val loop = AgentLoop(
            gateway,
            ToolRegistry(emptyList()),
            ScriptedPolicy(),
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            retryInstruction = "资源重试指令",
        )

        val run = loop.run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val retried = gateway.requests[1].history
        assertEquals("资源重试指令", assertIs<ModelHistoryItem.Text>(retried.last()).text)
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

    @Test fun `a non-persistent tool result reaches the model but never the stored transcript`() {
        // 0.3 Phase 2 final review C2: perception content may live only in the in-run model request. The stored
        // transcript (what a ConversationStore persists) keeps the call/output pair attributable but must not carry the
        // content or its image reference.
        val sentinel = "SYNTHETIC-PERCEPTION-CONTENT-9182"
        val image = ImageRef("artifact-perception-1", "image/png", 768, 1664, "sha-perception-1")
        val executor = executorOf("device").apply {
            outcome = { _, _ ->
                ActionResult(ActionOutcome.Done, sentinel, nonPersistent = true, images = listOf(image))
            }
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device"))),
                reply(text = "看完了。"),
            ),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val inRun = gateway.requests[1].history.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertTrue(inRun.output.contains(sentinel), "the model must see the content during the run: ${inRun.output}")
        assertEquals(listOf(image), inRun.images, "the in-run request keeps the image reference")
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertFalse(
            stored.output.contains(sentinel),
            "the stored transcript must not carry perception content: ${stored.output}",
        )
        assertTrue(stored.output.isNotBlank(), "a safe marker keeps the call/output pair attributable")
        assertEquals(emptyList(), stored.images, "the stored transcript carries no image reference")
    }

    @Test fun `a reused call id redacts only this run's output and keeps an earlier turn verbatim`() {
        // 0.3 Phase 2 final review C2 (re-review): a run may reuse a call id that a previous turn already used (ids only
        // need to be non-blank). Redacting the whole history by id turned the earlier, persistent output into the marker
        // too, which made the stored transcript stop being a prefix of the store and dropped the current tool round.
        val earlier = ModelHistoryItem.ToolOutput("c1", "EARLIER-LEGACY-OUTPUT-5521")
        val sentinel = "SYNTHETIC-PERCEPTION-CONTENT-7719"
        val executor = executorOf("device").apply {
            outcome = { _, _ -> ActionResult(ActionOutcome.Done, sentinel, nonPersistent = true) }
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device"))),
                reply(text = "看完了。"),
            ),
        )
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "之前的屏幕"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "稍等"),
            ModelHistoryItem.ToolCall("c1", "device", """{"action":"observe"}""", "item-1"),
            earlier,
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(history = history))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().filter { it.callId == "c1" }
        assertEquals(2, stored.size, "both the earlier and the current output must stay attributable: ${run.transcript}")
        assertEquals(earlier.output, stored[0].output, "an earlier turn's output must survive verbatim")
        assertFalse(
            stored[1].output.contains(sentinel),
            "only this run's output is perception content: ${stored[1].output}",
        )
        assertTrue(stored[1].output.isNotBlank(), "the current call/output pair keeps a safe marker")
    }

    @Test fun `a loop enriched failure observation reaches the model but never the stored transcript`() {
        // 0.3 Phase 2.2 Task 8: the loop is a second producer of perception content, not only the tools. A failure result
        // that came back with no observation (the tool declared none, or the framework synthesized it) is enriched by
        // `observedFailure`; that copy must be marked non-persistent even though the tool-returned result was not.
        val sentinel = "SYNTHETIC-LOOP-OBSERVATION-3381"
        val image = ImageRef("artifact-failure-1", "image/png", 768, 1664, "sha-failure-1")
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED", images = listOf(image))
        }
        val observation = ToolObservation(
            foregroundPackage = "com.example.launcher",
            screen = sentinel,
            vision = ToolVision.NotRequested,
        )
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "看到了")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)))
            .run(request(observationSource = { observation }))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val inRun = gateway.requests[1].history.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertTrue(inRun.output.contains(sentinel), "the model must see the loop's observation: ${inRun.output}")
        assertEquals(listOf(image), inRun.images, "the in-run request keeps the failure's picture reference")
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertFalse(
            stored.output.contains(sentinel),
            "the loop-attached observation may not outlive the request: ${stored.output}",
        )
        assertTrue(stored.output.isNotBlank(), "the stored call/output pair stays attributable")
        assertEquals(emptyList(), stored.images, "the stored transcript carries no failure image")
    }

    @Test fun `a failure image is never persisted even when the tool marked the result persistent`() {
        // Defence in depth for the last gate: a tool that returns a picture but forgets `nonPersistent` (or a future
        // image-bearing result with no observation) still may not leave the picture in the stored transcript.
        val image = ImageRef("artifact-failure-2", "image/png", 768, 1664, "sha-failure-2")
        val executor = executorOf("lookup")
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED", images = listOf(image))
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "知道了")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val inRun = gateway.requests[1].history.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals(listOf(image), inRun.images, "the model still sees the picture during the run")
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals(emptyList(), stored.images, "no image may be persisted: ${stored.output}")
    }

    @Test fun `a successful image without the tool flag is still redacted from the stored transcript`() {
        // Task 8 fix review: the last gate must not exempt a Done image. Production's image results already set
        // `nonPersistent`, but a tool that forgets it may not put a picture into the stored transcript; the in-run
        // request still carries it for the model.
        val image = ImageRef("artifact-done-1", "image/png", 768, 1664, "sha-done-1")
        val executor = executorOf("lookup")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "captured", images = listOf(image)) }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "看完了")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val inRun = gateway.requests[1].history.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals(listOf(image), inRun.images, "the model sees the successful picture during the run")
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals(emptyList(), stored.images, "no image may be persisted, whatever the outcome: ${stored.output}")
    }

    @Test fun `pure execution facts of a failure stay persistent when the loop attaches no observation`() {
        // The complement of the C2 rule: persistence is only withdrawn for content the loop itself attaches. A pure tool
        // result (no screen observation) is execution facts, so its stored output keeps the tool detail.
        val executor = executorOf("notify_user")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "DELIVERY_FAILED") }
        val source = ToolObservationSource {
            error("a pure tool must never be observed")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "notify_user"))), reply(text = "知道了")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertTrue(
            stored.output.contains("DELIVERY_FAILED"),
            "a pure execution fact must remain persistable: ${stored.output}",
        )
        assertFalse(stored.output.contains("perception content not persisted"), stored.output)
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
                ModelHistoryItem.ToolOutput("call-1", doneEnvelope("volume=5")),
            ),
            second,
        )
        assertEquals(RecordedAuditEntry("turn-7", "device.get_volume", "executed", "ok"), audit.entries.single())
    }

    @Test fun `an unmigrated failure keeps the legacy detail's stable code in the step and the audit`() {
        // The registry projects an ActionResult outcome but fabricates no execution code, and the legacy detail still
        // names one. The step label and the audit line are content-free, so they keep that code (the same identity
        // ErrorTracker uses) instead of collapsing it to the outcome token.
        val executor = executorOf("open_app")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "APP_NOT_VISIBLE") }
        val audit = RecordingAuditSink()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("call-1", "open_app"))), reply(text = "没能打开")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), audit = audit).run(request())

        assertEquals(listOf(AgentStep(1, "open_app", "APP_NOT_VISIBLE")), run.steps)
        assertEquals(
            RecordedAuditEntry("turn-7", "open_app", "executed", "APP_NOT_VISIBLE"),
            audit.entries.single(),
        )
    }

    // ---------------------------------------------------------------- tool status callbacks (0.24 S4 Task 17)

    @Test fun `a tool call reports START before it executes and END after it returns`() {
        val events = mutableListOf<String>()
        val executor = executorOf("device.get_volume")
        executor.outcome = { _, _ ->
            events += "execute"
            ActionResult(ActionOutcome.Done, "volume=5")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("call-1", "device.get_volume"))), reply(text = "音量是 5")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(onToolStatus = { action, phase -> events += "${phase.name}:$action" }),
        )

        assertEquals(
            listOf("START:device.get_volume", "execute", "END:device.get_volume"),
            events,
            "the row must exist while the action runs, and the end must follow the result",
        )
    }

    @Test fun `every call of one round is paired START END in call order`() {
        val events = mutableListOf<String>()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("a"), actionDescriptor("b")))
        executor.outcome = { name, _ ->
            events += "execute:$name"
            ActionResult(ActionOutcome.Done, "payload-$name")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "a"), call("c2", "b"))), reply(text = "done")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(onToolStatus = { action, phase -> events += "${phase.name}:$action" }),
        )

        assertEquals(
            listOf(
                "START:a", "execute:a", "END:a",
                "START:b", "execute:b", "END:b",
            ),
            events,
        )
    }

    @Test fun `a refused call still reports a paired START and END`() {
        val events = mutableListOf<String>()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "a"))), reply(text = "done")),
        )
        val policy = ScriptedPolicy(fallback = PolicyDecision.Refuse)

        newLoop(gateway, registry = ToolRegistry(listOf(executorOf("a"))), policy = policy).run(
            request(onToolStatus = { action, phase -> events += "${phase.name}:$action" }),
        )

        assertEquals(listOf("START:a", "END:a"), events)
    }

    @Test fun `an unknown tool reports its raw name and never reaches the policy`() {
        val events = mutableListOf<String>()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "mystery_tool"))), reply(text = "done")),
        )

        newLoop(gateway).run(request(onToolStatus = { action, phase -> events += "${phase.name}:$action" }))

        assertEquals(listOf("START:mystery_tool", "END:mystery_tool"), events)
    }

    @Test fun `a throwing tool still reports END`() {
        val events = mutableListOf<String>()
        val executor = executorOf("a").apply { failure = RuntimeException("boom") }
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "a"))), reply(text = "done")))

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(onToolStatus = { action, phase -> events += "${phase.name}:$action" }),
        )

        assertEquals(listOf("START:a", "END:a"), events)
    }

    @Test fun `a text-only run reports no tool status at all`() {
        val events = mutableListOf<String>()
        val gateway = ScriptedGateway(listOf(reply(text = "直接回答")))

        newLoop(gateway).run(request(onToolStatus = { action, phase -> events += "${phase.name}:$action" }))

        assertTrue(events.isEmpty())
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
                ModelHistoryItem.ToolOutput("c1", doneEnvelope("payload-a")),
                ModelHistoryItem.ToolOutput("c2", doneEnvelope("payload-b")),
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
                ModelHistoryItem.ToolOutput("c1", doneEnvelope("payload-a")),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "second"),
                ModelHistoryItem.ToolCall("c2", "b", "{}"),
                ModelHistoryItem.ToolOutput("c2", doneEnvelope("payload-b")),
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
        assertEquals(ModelHistoryItem.ToolOutput("c1", errorEnvelope("UNKNOWN_TOOL")), gateway.requests[1].history.last())
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
        assertEquals(ModelHistoryItem.ToolOutput("c1", errorEnvelope("REFUSED_BY_POLICY")), gateway.requests[1].history.last())
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

    @Test fun `a defensive failure after dispatch does not claim the call changed nothing`() {
        // An `Error` escapes `ToolRegistry.execute`'s `Exception` guard and surfaces through the future as an
        // `ExecutionException`. The call was dispatched, so the result is Uncertain with no tri-state fact; the loop
        // still treats it as one recoverable failed step.
        val executor = executorOf("lookup").apply { failure = AssertionError("boom") }
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "recovered")))

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(listOf(AgentStep(1, "lookup", "TOOL_THREW")), run.steps)
        assertEquals(
            ModelHistoryItem.ToolOutput(
                "c1",
                envelope(ToolExecution(outcome = ToolOutcome.Uncertain, code = "TOOL_THREW", uncertain = true), ""),
            ),
            gateway.requests[1].history.last(),
        )
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

    @Test fun `only a successful terminal tool ends the run with its tool detail as the final text`() {
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
            listOf(AgentStep(1, "report_done", "Failed"), AgentStep(2, "report_done", "ok")),
            run.steps,
        )
    }

    // ---------------------------------------------------------------- no-progress guard

    @Test fun `three identical write fingerprints end the run as no progress`() {
        // R62 (Task 12): the guard is fed the **observation fingerprint** of a write, never the payload. The three
        // calls have different arguments on purpose: same-tool+args repeats are `generic_repeat`'s job now, so a test
        // that kept identical payloads would pass for the wrong detector (and not prove the guard is fed at all).
        // R98: the fake declares the call state-changing, as the tool layer does for a real write.
        val executor = executorOf("lookup")
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "same-screen", fingerprint = "same-screen", stateChanging = true)
        }
        val gateway = ScriptedGateway(
            List(4) { reply(calls = listOf(call("c$it", "lookup", """{"step":$it}"""))) },
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("NO_PROGRESS", run.safeCode)
        assertEquals(3, run.steps.size)
        assertEquals(3, gateway.requests.size)
    }

    @Test fun `a changed write fingerprint resets the no progress streak`() {
        val executor = executorOf("lookup")
        var attempt = 0
        executor.outcome = { _, _ ->
            attempt++
            ActionResult(
                ActionOutcome.Done,
                "payload",
                fingerprint = if (attempt == 3) "changed" else "same",
                stateChanging = true,
            )
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "lookup", """{"step":2}"""))),
                reply(calls = listOf(call("c3", "lookup", """{"step":3}"""))),
                reply(calls = listOf(call("c4", "lookup", """{"step":4}"""))),
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
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "payload", fingerprint = "fresh-screen", stateChanging = true)
        }
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
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "payload", fingerprint = "same-screen", stateChanging = true)
        }
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

    // ---------------------------------------------------------------- failure observation (0.3 Phase 2.2 Task 3)

    /**
     * The envelope part of one model-facing tool output. The loop appends its stable `LOOP_HINT` **after** the envelope,
     * so a test that pins the result contract compares the envelope alone; the hints have their own tests.
     */
    private fun envelopePart(output: ModelHistoryItem.ToolOutput): String =
        output.output.substringBefore("\n\nLOOP_HINT:")

    @Test fun `a policy refusal of a screen tool reports an unavailable observation when no source exists`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("device.set_volume", screenObservation = true)))
        val policy = ScriptedPolicy(mapOf("device.set_volume" to PolicyDecision.Refuse))
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "那就不动")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor)), policy = policy).run(request())

        assertTrue(executor.calls.isEmpty(), "a refused action never reaches the executor")
        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(
                    outcome = ToolOutcome.Failed,
                    code = "REFUSED_BY_POLICY",
                    accepted = false,
                    applied = false,
                ),
                ToolObservation(vision = ToolVision.Unavailable, visionReason = "no_source"),
                data = "",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a failed screen tool gets the source observation with the short failure deadline`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val observation = ToolObservation(
            foregroundPackage = "com.example.launcher",
            screen = "Home",
            vision = ToolVision.NotRequested,
        )
        val deadlines = mutableListOf<Long>()
        val source = ToolObservationSource { deadlineMillis ->
            deadlines += deadlineMillis
            observation
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "看到了")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(listOf(250L), deadlines, "a failure observation gets its own short deadline")
        assertEquals(
            ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Failed), observation, "STATE_UNCHANGED"),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a pure tool failure never asks the observation source`() {
        val executor = executorOf("notify_user")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "DELIVERY_FAILED") }
        var observed = 0
        val source = ToolObservationSource {
            observed++
            ToolObservation(screen = "Home")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "notify_user"))), reply(text = "知道了")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(0, observed, "a pure tool must keep observation=null instead of inventing a screen")
        assertEquals(
            ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Failed), null, "DELIVERY_FAILED"),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a throwing screen tool is uncertain and carries the best-effort observation`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("shell", screenObservation = true)))
        executor.failure = IllegalStateException("boom")
        val observation = ToolObservation(screen = "Home", vision = ToolVision.NotRequested)
        val deadlines = mutableListOf<Long>()
        var sourceCalls = 0
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "shell"))), reply(text = "先不动")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(observationSource = { deadlineMillis ->
                sourceCalls++
                deadlines += deadlineMillis
                observation
            }),
        )

        assertEquals("TOOL_THREW", run.steps.single().result)
        assertEquals(1, sourceCalls, "a thrown screen call asks the source exactly once")
        assertEquals(listOf(250L), deadlines, "a thrown screen call gets the short failure deadline")
        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(outcome = ToolOutcome.Uncertain, code = "TOOL_THREW", uncertain = true),
                observation,
                data = "",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a throwing screen tool without a source still reports an unavailable observation`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("shell", screenObservation = true)))
        executor.failure = IllegalStateException("boom")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "shell"))), reply(text = "先不动")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request())

        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(outcome = ToolOutcome.Uncertain, code = "TOOL_THREW", uncertain = true),
                ToolObservation(vision = ToolVision.Unavailable, visionReason = "no_source"),
                data = "",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `an observation source that returns null yields observation timeout`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "没看到")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { null }),
        )

        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(outcome = ToolOutcome.Failed),
                ToolObservation(vision = ToolVision.Unavailable, visionReason = "observation_timeout"),
                data = "STATE_UNCHANGED",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a throwing observation source yields observation timeout and never fails the run`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val deadlines = mutableListOf<Long>()
        var sourceCalls = 0
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "没看到")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { deadlineMillis ->
                sourceCalls++
                deadlines += deadlineMillis
                throw IllegalStateException("capture timed out")
            }),
        )

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "an observation failure is never a run failure")
        assertEquals(1, sourceCalls, "a throwing source is asked exactly once")
        assertEquals(listOf(250L), deadlines, "a throwing source gets the short failure deadline")
        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(outcome = ToolOutcome.Failed),
                ToolObservation(vision = ToolVision.Unavailable, visionReason = "observation_timeout"),
                data = "STATE_UNCHANGED",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `an interrupted observation source yields observation timeout and restores the interrupt`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "没看到")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { throw InterruptedException("stop observing") }),
        )

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertTrue(Thread.interrupted(), "the loop must restore an interrupt it swallowed")
        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(outcome = ToolOutcome.Failed),
                ToolObservation(vision = ToolVision.Unavailable, visionReason = "observation_timeout"),
                data = "STATE_UNCHANGED",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `an error from the observation source is not swallowed`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "没看到")),
        )

        assertFailsWith<AssertionError> {
            newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(
                request(observationSource = ToolObservationSource { throw AssertionError("fatal") }),
            )
        }
    }

    @Test fun `a tool supplied observation is authoritative and is never replaced`() {
        val toolObservation = ToolObservation(screen = "tool screen", vision = ToolVision.NotRequested)
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open", screenObservation = true)))
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED", observation = toolObservation)
        }
        var observed = 0
        val source = ToolObservationSource {
            observed++
            ToolObservation(screen = "source screen", vision = ToolVision.NotRequested)
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "看到了")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(0, observed, "the loop must not overwrite an observation the tool already supplied")
        assertEquals(
            ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Failed), toolObservation, "STATE_UNCHANGED"),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a successful screen tool is not observed by the loop`() {
        val executor = RecordingActionExecutor(listOf(actionDescriptor("device", screenObservation = true)))
        var observed = 0
        val source = ToolObservationSource {
            observed++
            ToolObservation(screen = "Home")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device"))), reply(text = "好了")),
        )

        newLoop(gateway, registry = ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(0, observed, "a success observation belongs to the tool (Tasks 4-6), not the failure observer")
        assertEquals(
            ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Done), null, "done"),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
    }

    @Test fun `a denied confirmation of a screen tool still gets a best-effort observation`() {
        val executor = RecordingActionExecutor(
            listOf(actionDescriptor("app.uninstall", requiresConfirmation = true, screenObservation = true)),
        )
        val observation = ToolObservation(screen = "Home", vision = ToolVision.NotRequested)
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "app.uninstall"))), reply(text = "那就不动")),
        )

        val run = newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("卸载?"))),
            confirmation = ScriptedConfirmation(ConfirmationOutcome(false, ConfirmationSource.PHONE)),
        ).run(request(observationSource = { observation }))

        assertTrue(executor.calls.isEmpty(), "a denied action must never execute")
        assertEquals("CONFIRMATION_DENIED", run.steps.single().result)
        assertEquals(
            ToolResultEnvelope.encode(
                ToolExecution(
                    outcome = ToolOutcome.Failed,
                    code = "CONFIRMATION_DENIED",
                    accepted = false,
                    applied = false,
                ),
                observation,
                data = "",
            ),
            envelopePart(assertIs(gateway.requests[1].history.last())),
        )
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

    /** 0.3 Phase 4B (B4): a host that says nobody can be asked right now. Asking it at all is the defect. */
    private class UnavailableConfirmation : ConfirmationPort {
        var asked = 0

        override fun available(): Boolean = false

        override fun request(requestId: String, summary: String): ConfirmationOutcome {
            asked++
            error("an unavailable confirmation channel must never be asked")
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

    @Test fun `a run reports the wall clock it spent waiting for a person`() {
        // Review I-1: the caller owns windows derived from the turn deadline (the plugin's hint window is
        // `deadline + grace`), so the loop has to hand back what it excluded - otherwise a caller can only guess.
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "已经调低了")),
        )

        val run = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock,
            confirmation = WaitingConfirmation(clock, waitNanos = 5_000_000_000L),
        ).run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(5_000_000_000L, run.humanWaitNanos, "等人的墙钟必须随 run 交回调用方")
    }

    @Test fun `a shell wait is added to the policy confirmation wait without double counting`() {
        // Review I7: the policy confirmation path already adds its own measured wait. A gated tool that also waits for a
        // person inside `execute` (`mayAskHuman`) contributes its bounded deadline shift on top - once, not twice.
        val clock = FakeClock(0)
        val executor = RecordingActionExecutor(
            listOf(
                actionDescriptor(
                    "device.set_volume", requiresConfirmation = true, timeoutMillis = 50L, mayAskHuman = true,
                ),
            ),
        )
        executor.outcome = { _, _ ->
            Thread.sleep(400L)
            ActionResult(ActionOutcome.Done, "done")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "已经调低了")),
        )

        val run = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock,
            confirmation = WaitingConfirmation(clock, waitNanos = 5_000_000_000L),
        ).run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "两段等待都应被排除在预算之外")
        assertEquals(1, executor.calls.size, "批准过的动作必须真的执行")
        // 5 s policy wait + ~350 ms shell wait (400 ms sleep over the 50 ms machine cap). The lower bound proves the
        // shift reached the run; the upper bound proves the 5 s policy wait was not added a second time.
        assertTrue(run.humanWaitNanos >= 5_250_000_000L, "shell 等待也要计入人时：${run.humanWaitNanos}")
        assertTrue(run.humanWaitNanos < 9_000_000_000L, "策略确认等待不得被重复累加：${run.humanWaitNanos}")
    }

    @Test fun `machine time is not reported as a human wait`() {
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ClockMovingGateway(
            clock, nanosPerTurn = 2_000_000_000L,
            replies = listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "不该到达")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock)
            .run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(0L, run.humanWaitNanos, "只有等人算人时；机器时间不得被报成人时")
    }

    @Test fun `an absent confirmation channel behaves exactly as before`() {
        // Review M-1: the plan promised this one and it was missing. The null channel must keep P3.1's shape even with a
        // finite turn deadline and a non-zero per-request cap - the `finally` around the confirmation call shifts by the
        // measured wait, which is zero here, so nothing about the old path may move.
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "没有通道就先不动")),
        )

        val run = newLoop(gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock)
            .run(request(deadlineNanos = 60_000_000_000L, requestTimeoutNanos = 500_000_000L))

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "没有确认通道时回合结果必须与旧版一致")
        assertEquals(AgentStep(1, "device.set_volume", "CONFIRMATION_UNAVAILABLE"), run.steps.single())
        assertTrue(executor.calls.isEmpty(), "没有通道的确认动作永不执行")
        assertEquals(500_000_000L, gateway.requests.first().deadlineNanos, "单次请求上限照常生效")
    }

    @Test fun `an unavailable confirmation channel is refused without entering the wait`() {
        // 0.3 Phase 4B (B4), review focus 3: a channel whose surface is gone must not be asked and must not make the
        // loop block out D47's 120 s window before returning the same refusal.
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val audit = RecordingAuditSink()
        val confirmation = UnavailableConfirmation()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "没有确认通道，先不动")),
        )

        val run = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), audit = audit, clock = clock,
            confirmation = confirmation,
        ).run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(AgentStep(1, "device.set_volume", "CONFIRMATION_UNAVAILABLE"), run.steps.single())
        assertEquals(0, confirmation.asked, "the loop must ask availability before blocking on the port")
        assertEquals(0L, run.humanWaitNanos, "nobody waited, so the turn has no human wait to report")
        assertEquals(0L, clock.nowNanos, "the loop did not spend the 120 s window")
        assertEquals(
            RecordedAuditEntry("turn-7", "device.set_volume", "policy", "ConfirmationUnavailable"),
            audit.entries.single(),
        )
        assertTrue(executor.calls.isEmpty(), "an unconfirmed action must never execute")
    }

    @Test fun `a cancel during the wait ends the turn as CANCELLED`() {
        val clock = FakeClock(0)
        val executor = gatedExecutor()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "device.set_volume"))), reply(text = "不该到达")),
        )
        lateinit var loop: AgentLoop
        // A real 急停: the gate settles as a refusal *and* the turn is cancelled - never "approved and cancelled", which
        // no production path produces (review M-2).
        val confirmation = WaitingConfirmation(
            clock,
            waitNanos = 5_000_000_000L,
            outcome = ConfirmationOutcome(false, ConfirmationSource.CANCEL),
            onWait = { loop.cancel() },
        )
        loop = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), policy = gatedPolicy(), clock = clock,
            confirmation = confirmation,
        )

        val run = loop.run(request(deadlineNanos = 1_000_000_000L))

        assertEquals(AgentOutcome.CANCELLED, run.outcome, "等待期间被取消就是取消，顺延不得把它变成完成")
        assertEquals(1, gateway.requests.size, "取消之后不得再问模型")
        assertTrue(executor.calls.isEmpty(), "被取消的确认不得因为顺延而多走一步")
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

    // --------------------------------------------- streaming text deltas (0.24 S4 Task 16)

    @Test fun `every gateway turn of one run carries the request's text delta observer`() {
        // A run asks the model more than once (a tool round does), and the answer may be assembled from the last
        // request. The observer therefore belongs to the whole run, not just the opening turn.
        val seen = mutableListOf<String>()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "答案")),
        )

        newLoop(gateway).run(request(onTextDelta = { seen += it }))

        assertEquals(2, gateway.requests.size, "the fixture took exactly one tool round")
        gateway.requests.forEach { it.onTextDelta?.invoke("流") }
        assertEquals(listOf("流", "流"), seen, "each request must forward the same observer")
    }

    @Test fun `a run without a text delta observer leaves every gateway turn's observer null`() {
        val gateway = ScriptedGateway(listOf(reply(text = "答案")))

        newLoop(gateway).run(request())

        assertNull(gateway.requests.single().onTextDelta, "no observer means no callback wiring, exactly as before 0.24")
    }

    // --------------------------------------------- the per-request cap is not the turn budget (D49)

    @Test fun `a per-request timeout caps the gateway turn without touching the turn budget`() {
        val clock = FakeClock(0)
        val gateway = ScriptedGateway(listOf(reply(text = "好")))

        newLoop(gateway, clock = clock).run(
            request(deadlineNanos = 60_000_000_000L, requestTimeoutNanos = 500_000_000L),
        )

        assertEquals(
            500_000_000L,
            gateway.requests.single().deadlineNanos,
            "单次模型请求的上限是档案超时，不是整个回合的预算",
        )
    }

    @Test fun `a zero request timeout keeps the old deadline semantics`() {
        val clock = FakeClock(0)
        val gateway = ScriptedGateway(listOf(reply(text = "好")))

        newLoop(gateway, clock = clock).run(request(deadlineNanos = 4_242L))

        assertEquals(4_242L, gateway.requests.single().deadlineNanos, "0 = 旧行为：仍拿回合 deadline 当请求上限")
    }

    // --------------------------------------------- per-tool latency telemetry (Phase 4A A9)

    /** The per-call latency lines only; the run-level `P3_METRIC` and per-call `P3_TOOLRESULT` share the sink. */
    private fun p4ToolLines(lines: List<String>): List<String> = lines.filter { it.startsWith("P4_TOOL") }

    /**
     * Phase 4A Task 9: one count-only `P4_TOOL` line per measured tool call, on the loop's existing metric sink.
     *
     * `ms` is the call's machine time from the injected clock, so the literal is deterministic. The argument and the
     * result body are sentinels: neither may reach the line, which carries only the tool name, the whole-millisecond
     * machine time and the envelope's existing wire outcome.
     */
    @Test fun `a tool call emits one P4_TOOL line with its machine millis and wire outcome`() {
        val clock = FakeClock(0)
        val lines = mutableListOf<String>()
        val executor = executorOf("lookup")
        executor.outcome = { _, _ ->
            clock.advance(7_000_000L)
            ActionResult(ActionOutcome.Done, """{"text":"MARKER-RESULT-BODY"}""")
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup", """{"secret":"MARKER-ARGUMENT"}"""))),
                reply(text = "答"),
            ),
        )

        val run = newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), clock = clock, metricLine = { lines += it },
        ).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(listOf("P4_TOOL tool=lookup ms=7 outcome=done"), p4ToolLines(lines))
        assertFalse(
            lines.any { it.contains("MARKER-ARGUMENT") || it.contains("MARKER-RESULT-BODY") },
            "the latency line may carry no argument or result content: $lines",
        )
    }

    /**
     * Two calls in one round are two readings, each with its own tool, machine time and wire token. The second call is a
     * failure, which pins that `outcome` reuses the envelope vocabulary rather than the run's own terminal state.
     */
    @Test fun `two calls in one round emit one P4_TOOL line each with their own outcome`() {
        val clock = FakeClock(0)
        val lines = mutableListOf<String>()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup"), actionDescriptor("open")))
        executor.outcome = { name, _ ->
            when (name) {
                "lookup" -> {
                    clock.advance(3_000_000L)
                    ActionResult(ActionOutcome.Done, "ok")
                }
                else -> {
                    clock.advance(11_000_000L)
                    ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED")
                }
            }
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"), call("c2", "open"))), reply(text = "好")),
        )

        newLoop(
            gateway, registry = ToolRegistry(listOf(executor)), clock = clock, metricLine = { lines += it },
        ).run(request())

        assertEquals(
            listOf(
                "P4_TOOL tool=lookup ms=3 outcome=done",
                "P4_TOOL tool=open ms=11 outcome=failed",
            ),
            p4ToolLines(lines),
        )
    }

    // --------------------------------------------- policy telemetry (0.3 Phase 4B B7)

    /** The per-decision policy lines only; `P3_*`/`P4_TOOL` lines share the same sink. */
    private fun p4PolicyLines(lines: List<String>): List<String> = lines.filter { it.startsWith("P4_POLICY") }

    /** The confirmation lines the loop may emit itself: the early no-channel refusal. */
    private fun p4ConfirmLines(lines: List<String>): List<String> = lines.filter { it.startsWith("P4_CONFIRM") }

    /**
     * 0.3 Phase 4B (B7): exactly one count-only `P4_POLICY` line per policy decision.
     *
     * Two calls are decided in one round, one allowed and one refused. The exact field order
     * (`mode action risk decision`) is the contract, and the decision's own words are the sealed class
     * names. The argument and summary sentinels pin that neither is ever appended.
     */
    @Test fun `each policy decision emits one P4_POLICY line with mode action risk and decision`() {
        val lines = mutableListOf<String>()
        val executor = RecordingActionExecutor(
            listOf(actionDescriptor("lookup", risk = RiskLevel.L1), actionDescriptor("danger", risk = RiskLevel.L3)),
        )
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, """{"text":"MARKER-RESULT"}""") }
        val gateway = ScriptedGateway(
            listOf(
                reply(
                    calls = listOf(
                        call("c1", "lookup", """{"secret":"MARKER-ARGUMENT"}"""),
                        call("c2", "danger"),
                    ),
                ),
                reply(text = "好"),
            ),
        )

        newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("danger" to PolicyDecision.Refuse)),
            metricLine = { lines += it },
        ).run(request())

        assertEquals(
            listOf(
                "P4_POLICY mode=normal action=lookup risk=L1 decision=Allow",
                "P4_POLICY mode=normal action=danger risk=L3 decision=Refuse",
            ),
            p4PolicyLines(lines),
        )
        assertFalse(
            lines.any { it.contains("MARKER-ARGUMENT") || it.contains("MARKER-RESULT") },
            "the policy line carries no argument or result content: $lines",
        )
    }

    /**
     * A question the policy requires is still one decision line; its human-readable summary is free text and must stay
     * off the count-only record (only the action's name, risk and the decision token ride it).
     */
    @Test fun `a required confirmation is recorded as a decision without its summary text`() {
        val lines = mutableListOf<String>()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("app.uninstall", requiresConfirmation = true)))
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "app.uninstall"))), reply(text = "那就不动")))

        newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(
                mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("MARKER-SUMMARY 卸载？")),
            ),
            confirmation = ScriptedConfirmation(ConfirmationOutcome(false, ConfirmationSource.PHONE)),
            metricLine = { lines += it },
        ).run(request())

        assertEquals(
            listOf("P4_POLICY mode=normal action=app.uninstall risk=L0 decision=RequireConfirmation"),
            p4PolicyLines(lines),
        )
        assertFalse(lines.any { it.contains("MARKER-SUMMARY") }, "the question text must not reach the line: $lines")
    }

    /**
     * The mode is not a turn snapshot: it is the live B3 provider's answer at **each** decision. The first call runs
     * while 不拦截 is on (an L3 tool is allowed); the call itself then switches the provider off, and the second L3 call
     * of the same round is escalated. One line per decision, each naming the mode that actually produced it.
     */
    @Test fun `the no interception mode is read live for every policy decision`() {
        var bypass = true
        val lines = mutableListOf<String>()
        val executor = RecordingActionExecutor(
            listOf(actionDescriptor("first", risk = RiskLevel.L3), actionDescriptor("second", risk = RiskLevel.L3)),
        )
        executor.outcome = { name, _ ->
            if (name == "first") bypass = false
            ActionResult(ActionOutcome.Done, "done")
        }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "first"), call("c2", "second"))), reply(text = "好")),
        )

        newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = WhitelistActionPolicy(RiskLevel.L1, emptyList(), noInterception = { bypass }),
            metricLine = { lines += it },
        ).run(request())

        assertEquals(
            listOf(
                "P4_POLICY mode=no_interception action=first risk=L3 decision=Allow",
                "P4_POLICY mode=normal action=second risk=L3 decision=RequireConfirmation",
            ),
            p4PolicyLines(lines),
        )
    }

    /** B4's early refusal is the honest no-channel point; the loop records it with channel `none`. */
    @Test fun `an unavailable confirmation channel emits one P4_CONFIRM unavailable line`() {
        val lines = mutableListOf<String>()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("app.uninstall", requiresConfirmation = true)))
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "app.uninstall"))), reply(text = "那就不动")))

        newLoop(
            gateway,
            registry = ToolRegistry(listOf(executor)),
            policy = ScriptedPolicy(mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("卸载？"))),
            confirmation = UnavailableConfirmation(),
            metricLine = { lines += it },
        ).run(request())

        assertEquals(
            listOf("P4_CONFIRM tool=app.uninstall decision=unavailable channel=none"),
            p4ConfirmLines(lines),
        )
    }

    /** An unknown tool never reaches the policy, so it has no decision and must not invent a policy line. */
    @Test fun `an unknown tool emits no P4_POLICY line`() {
        val lines = mutableListOf<String>()
        val gateway = ScriptedGateway(listOf(reply(calls = listOf(call("c1", "mystery"))), reply(text = "不认识这个工具")))

        newLoop(gateway, metricLine = { lines += it }).run(request())

        assertTrue(p4PolicyLines(lines).isEmpty(), "an unknown name is not a policy decision: $lines")
    }
}
