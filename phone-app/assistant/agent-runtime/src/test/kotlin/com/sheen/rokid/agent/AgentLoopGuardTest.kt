package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision
import com.sheen.rokid.testsupport.FakeClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 12 loop-level behaviour (R55–R61): the detectors and error tracker as they reach the model and the run's terminal
 * state, plus plan nudges, tool timeouts, the observation budget, runtime-state injection and cancel propagation.
 *
 * The scripted gateway is the same one `AgentLoopTest` uses: it records every [GatewayTurn] and answers in order, so a
 * test can count model round trips and inspect the exact transcript the model would see.
 */
class AgentLoopGuardTest {

    private val planText = "接下来我会先观察当前屏幕，然后再点按确认。"

    private fun request(
        allowPlanNudge: Boolean = false,
        runtimeState: (() -> TurnRuntimeState?)? = null,
        frameFor: ((List<ModelHistoryItem>) -> RequestFrame)? = null,
        deadlineNanos: Long = Long.MAX_VALUE,
        observationSource: ToolObservationSource? = null,
    ) = AgentRequest(
        turnId = "turn-guard",
        utterance = "打开设置",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = deadlineNanos,
        frameFor = frameFor,
        allowPlanNudge = allowPlanNudge,
        runtimeState = runtimeState,
        observationSource = observationSource,
    )

    private fun loop(
        gateway: ModelGateway,
        registry: ToolRegistry,
        guard: ProgressGuard = ProgressGuard(),
        clock: MonotonicClock = FakeClock(),
        maxSteps: Int = 24,
        maxObservations: Int = 24,
        metricLine: (String) -> Unit = {},
    ) = AgentLoop(
        gateway,
        registry,
        ScriptedPolicy(),
        guard,
        RecordingAuditSink(),
        clock,
        maxSteps = maxSteps,
        maxObservations = maxObservations,
        metricLine = metricLine,
    )

    private fun descriptor(
        name: String,
        observation: Boolean = false,
        timeoutMillis: Long = 30_000L,
        mayAskHuman: Boolean = false,
        screenObservation: Boolean = false,
    ) = actionDescriptor(
        name,
        observation = observation,
        timeoutMillis = timeoutMillis,
        mayAskHuman = mayAskHuman,
        screenObservation = screenObservation,
    )

    private fun executor(vararg descriptors: ActionDescriptor) = RecordingActionExecutor(descriptors.toList())

    /** The production clock shape: the R99 deadline checks must run against the same wall the tool sleeps on. */
    private fun realClock(): MonotonicClock = object : MonotonicClock {
        override fun nowNanos(): Long = System.nanoTime()
    }

    private fun outputs(run: AgentRun): List<ModelHistoryItem.ToolOutput> =
        run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>()

    /**
     * The envelope the **model** received for the run's single tool output.
     *
     * Task 8: a loop-enriched failure observation is non-persistent, so `run.transcript` carries the safe marker
     * instead of the payload. The contract those tests assert is what the model saw in this run, which is the next
     * request's history — not the stored form.
     */
    private fun envelopeInRequest(gateway: ScriptedGateway): JsonObject {
        val output = gateway.requests.last().history
            .filterIsInstance<ModelHistoryItem.ToolOutput>()
            .single()
            .output
        return Json.parseToJsonElement(output.substringBefore("\n\nLOOP_HINT:")) as JsonObject
    }

    private fun minimalFrame(userInput: String): (List<ModelHistoryItem>) -> RequestFrame = {
        RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            conversationFlow = listOf(Segment(SegmentOrigin.USER_INPUT, true, userInput)),
            tools = emptyList(),
        )
    }

    // ---------------------------------------------------------------- generic_repeat

    @Test
    fun `a repeated call is warned and skipped without an extra model round trip`() {
        val executor = executor(descriptor("tap"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "screen", fingerprint = "same") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = (1..5).map { call("c$it", "tap") })),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("LOOP_REPEAT", run.safeCode)
        // The whole batch arrived in one model turn: a warning must not cost another round trip.
        assertEquals(1, gateway.requests.size)
        // Attempts 3 and 4 were warned and skipped; attempt 5 terminated. Only attempts 1 and 2 executed.
        assertEquals(2, executor.calls.size)
        assertEquals(5, run.steps.size)
        assertTrue(run.steps[0].executed)
        assertTrue(run.steps[1].executed)
        assertFalse(run.steps[2].executed)
        assertFalse(run.steps[3].executed)
        assertFalse(run.steps[4].executed)
        assertTrue(run.steps[2].result.startsWith("LOOP_WARNING:"), run.steps[2].result)
        assertTrue(run.steps[4].result.startsWith("LOOP_REPEAT"), run.steps[4].result)
        // The model really receives the synthetic result in the same tool round.
        assertTrue(outputs(run).any { it.output.startsWith("LOOP_WARNING:") })
    }

    // ---------------------------------------------------------------- poll_no_progress

    @Test
    fun `a repeated observation with an unchanged fingerprint terminates as LOOP_POLL`() {
        val executor = executor(descriptor("observe", observation = true))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "screen", fingerprint = "screen") }
        val gateway = ScriptedGateway(listOf(reply(calls = (1..5).map { call("c$it", "observe") })))

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("LOOP_POLL", run.safeCode)
        assertEquals(2, executor.calls.size)
        assertEquals(5, run.spentObservations)
        assertFalse(run.steps[2].executed)
    }

    // ---------------------------------------------------------------- state-changing progress (R98)

    @Test
    fun `repeated ineffective device act fingerprints trip the no-progress guard`() {
        // `device` declares observation=true for every call, but an `act` still *changes state*. The guard must see it:
        // different arguments keep the same-tool detectors (generic/poll) out of the way, so only the guard can end this.
        val executor = executor(descriptor("device", observation = true))
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "screen", fingerprint = "same-screen", stateChanging = true)
        }
        val gateway = ScriptedGateway(
            List(4) { reply(calls = listOf(call("c$it", "device", """{"action":"act","kind":"tap","ref":"#n$it"}"""))) } +
                reply(text = "done"),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("NO_PROGRESS", run.safeCode)
        assertEquals(3, run.steps.size)
        assertEquals(3, gateway.requests.size)
    }

    @Test
    fun `read-only observations never feed the no-progress guard`() {
        // R62's read-only exclusion survives R98: only a state-changing call feeds the guard. Observations stay the poll
        // detector's read path (see the LOOP_POLL test above). A guard of limit 1 would trip on the first observation if
        // this seam leaked reads into it.
        val executor = executor(descriptor("device", observation = true))
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "screen", fingerprint = "same-screen", stateChanging = false)
        }
        val gateway = ScriptedGateway(
            List(4) {
                reply(calls = listOf(call("c$it", "device", """{"action":"observe","filter":"all","since":$it}""")))
            } + reply(text = "done"),
        )
        val guard = ProgressGuard(limit = 1)

        val run = loop(gateway, ToolRegistry(listOf(executor)), guard = guard).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(4, run.steps.size)
        assertFalse(guard.tripped, "an observation is not a state-changing call")
    }

    // ---------------------------------------------------------------- ping_pong

    @Test
    fun `an A B A B A B alternation with no progress terminates as LOOP_PINGPONG`() {
        val executor = executor(descriptor("a", observation = true), descriptor("b", observation = true))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "screen", fingerprint = "same") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf("a", "b", "a", "b", "a", "b").mapIndexed { i, name -> call("c$i", name) })),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("LOOP_PINGPONG", run.safeCode)
        assertEquals(3, executor.calls.size, "the first three alternations run; two cycles trigger the warning")
        assertFalse(run.steps[3].executed)
        assertFalse(run.steps[4].executed)
        assertFalse(run.steps[5].executed)
    }

    // ---------------------------------------------------------------- global_circuit_breaker

    @Test
    fun `eight no progress results inject a notice and twelve terminate as LOOP_GLOBAL`() {
        val descriptors = (0..12).map { descriptor(if (it == 0) "seed" else "tool$it", observation = true) }
        val executor = executor(*descriptors.toTypedArray())
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "screen", fingerprint = "same") }
        val calls = (0..12).map { call("c$it", if (it == 0) "seed" else "tool$it") }
        val gateway = ScriptedGateway(listOf(reply(calls = calls)))

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("LOOP_GLOBAL", run.safeCode)
        assertEquals(13, executor.calls.size, "the global notice never skips a call")
        assertEquals(13, run.steps.size)
        assertTrue(outputs(run)[8].output.contains("LOOP_NOTICE"), "the 8th no-progress result carries the notice")
        assertEquals(1, gateway.requests.size)
    }

    // ---------------------------------------------------------------- repeated errors (R56)

    @Test
    fun `three identical errors fail the run as REPEATED_ERROR`() {
        val executor = executor(descriptor("device"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "CHANNEL_UNAVAILABLE") }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "device", """{"step":2}"""))),
                reply(calls = listOf(call("c3", "device", """{"step":3}"""))),
                reply(text = "不可达"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("REPEATED_ERROR", run.safeCode)
        assertEquals(3, run.steps.size)
        assertEquals(3, gateway.requests.size)
        assertTrue(outputs(run).first().output.contains("LOOP_HINT:CHANNEL_UNAVAILABLE"))
    }

    @Test
    fun `a confirmation denial never counts toward the error streak`() {
        val executor = executor(actionDescriptor("app.uninstall", requiresConfirmation = true))
        val policy = ScriptedPolicy(mapOf("app.uninstall" to PolicyDecision.RequireConfirmation("卸载?")))
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "app.uninstall", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "app.uninstall", """{"step":2}"""))),
                reply(calls = listOf(call("c3", "app.uninstall", """{"step":3}"""))),
                reply(text = "那就不动"),
            ),
        )
        val agent = AgentLoop(
            gateway,
            ToolRegistry(listOf(executor)),
            policy,
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            confirmation = ScriptedConfirmation(ConfirmationOutcome(false, ConfirmationSource.PHONE)),
        )

        val run = agent.run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("那就不动", run.finalText)
        assertEquals(3, run.steps.size)
        assertTrue(outputs(run).all { it.output.contains("do not retry automatically") })
    }

    @Test
    fun `a successful write resets the consecutive error streak`() {
        // The reset is the per-call state-changing declaration (R98), not "not an observation": `device` declares
        // observation=true for every call, so a real device act is the write that must reset the streak.
        val executor = executor(descriptor("device", observation = true), descriptor("recover", observation = true))
        executor.outcome = { name, _ ->
            if (name == "recover") {
                ActionResult(ActionOutcome.Done, "done", fingerprint = "screen", stateChanging = true)
            } else {
                ActionResult(ActionOutcome.Refused, "NODE_STALE")
            }
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "device", """{"step":2}"""))),
                reply(calls = listOf(call("c3", "recover", """{"action":"act"}"""))),
                reply(calls = listOf(call("c4", "device", """{"step":4}"""))),
                reply(calls = listOf(call("c5", "device", """{"step":5}"""))),
                reply(text = "好了"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("好了", run.finalText)
        assertEquals(5, run.steps.size)
    }

    @Test
    fun `successful notify and load skill results never reset the error streak`() {
        // R56/R98: only a state-changing success is progress for the error streak. A successful answer or reference
        // read is not evidence the device works, so it must not hide three identical device failures.
        val executor = executor(
            descriptor("device"),
            descriptor("notify_user"),
            descriptor("load_skill", observation = false),
        )
        executor.outcome = { name, _ ->
            when (name) {
                "notify_user" -> ActionResult(ActionOutcome.Done, "notified")
                "load_skill" -> ActionResult(ActionOutcome.Done, "loaded")
                else -> ActionResult(ActionOutcome.Refused, "NODE_STALE")
            }
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "notify_user", """{"text":"wait"}"""))),
                reply(calls = listOf(call("c3", "device", """{"step":2}"""))),
                reply(calls = listOf(call("c4", "load_skill", """{"skill":"navigation"}"""))),
                reply(calls = listOf(call("c5", "device", """{"step":3}"""))),
                reply(text = "不可达"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("REPEATED_ERROR", run.safeCode)
        assertEquals(5, run.steps.size)
    }

    @Test
    fun `three identical device failures with different timings fail as REPEATED_ERROR`() {
        // The production shape: ToolRegistry projects a failed ActionResult onto the matching outcome and its detail
        // becomes the envelope's `data` (the pre-0.3 `Refused:` prefix is gone). DeviceTool's detail is the whole device
        // envelope, whose timing block differs on every call. The error.code inside the JSON is the identity; if the
        // loop hashes the payload body instead, these are three different failures and the run never terminates.
        val executor = executor(descriptor("device"))
        var attempt = 0
        executor.outcome = { _, _ ->
            attempt++
            ActionResult(ActionOutcome.Refused, deviceFailure(totalMs = attempt.toLong()))
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "device", """{"step":2}"""))),
                reply(calls = listOf(call("c3", "device", """{"step":3}"""))),
                reply(text = "不可达"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome, "three identical NODE_STALE failures must end the run")
        assertEquals("REPEATED_ERROR", run.safeCode)
        assertEquals(3, run.steps.size)
        assertTrue(
            outputs(run).first().output.contains("LOOP_HINT:NODE_STALE"),
            "the NODE_STALE hint must reach the model: ${outputs(run).first().output}",
        )
    }

    private fun deviceFailure(totalMs: Long): String =
        """{"ok":false,"tool":"device","action":"act","channel":"accessibility","accepted":false,"applied":null,""" +
            """"verified":null,"images":[],"timing":{"actMs":1,"observeMs":2,"totalMs":$totalMs},"data":null,""" +
            """"error":{"code":"NODE_STALE","message":"the node reference is stale","recoverable":true}}"""

    // ---------------------------------------------------------------- prompt injection (A7)

    @Test
    fun `a custom plan nudge instruction and markers are the ones the model receives`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = "自定义计划标记：先观察屏幕。"),
                reply(text = "好。"),
            ),
        )
        val loop = AgentLoop(
            gateway,
            ToolRegistry(listOf(executor)),
            ScriptedPolicy(),
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            planNudgeInstruction = "资源纠偏指令",
            planMarkers = listOf("自定义计划标记"),
        )

        val run = loop.run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(3, gateway.requests.size, "the injected marker must trigger exactly one nudge")
        assertTrue(
            gateway.requests[2].history.any { it is ModelHistoryItem.Text && it.text == "资源纠偏指令" },
            "the model must receive the injected plan-nudge instruction: ${gateway.requests[2].history}",
        )
    }

    @Test
    fun `an explicitly empty plan marker set replaces the built-in list`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = planText)),
        )
        val loop = AgentLoop(
            gateway,
            ToolRegistry(listOf(executor)),
            ScriptedPolicy(),
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            planMarkers = emptyList(),
        )

        val run = loop.run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(planText, run.finalText)
        assertEquals(2, gateway.requests.size, "the injected empty list must replace the built-in markers")
    }

    // ---------------------------------------------------------------- plan nudge (R58)

    @Test
    fun `a plan-only answer is nudged up to three times and then accepted`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = "$planText 第一次"),
                reply(text = "$planText 第二次"),
                reply(text = "$planText 第三次"),
                reply(text = "$planText 第四次"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("$planText 第四次", run.finalText)
        assertEquals(1, run.planNudgeCap)
        // One opening turn + one tool round + three nudged continuations + the capped answer = five requests.
        assertEquals(5, gateway.requests.size)
        assertTrue(
            gateway.requests.drop(2).all { turn ->
                turn.history.any { it is ModelHistoryItem.Text && it.text.contains("Observe first") }
            },
            "every continuation must carry the observe-first nudge",
        )
        // The nudge scaffolding is loop-internal: a caller storing the transcript filters it out.
        assertTrue(
            AgentLoop.withoutRetryScaffolding(run.transcript)
                .none { it is ModelHistoryItem.Text && it.text.contains("Observe first") },
        )
    }

    @Test
    fun `no plan nudge fires when the request does not allow it`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = planText)),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(allowPlanNudge = false))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(planText, run.finalText)
        assertEquals(2, gateway.requests.size)
        assertEquals(0, run.planNudgeCap)
    }

    @Test
    fun `the same plan text after a nudge is never nudged again`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = planText),
                reply(text = planText),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(planText, run.finalText)
        assertEquals(3, gateway.requests.size, "one nudge, then the identical text is accepted")
    }

    @Test
    fun `a plan without any successful tool call is a plain answer`() {
        val gateway = ScriptedGateway(listOf(reply(text = planText)))

        val run = loop(gateway, ToolRegistry(emptyList())).run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `a completion sentence is never nudged`() {
        val executor = executorOf("lookup")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "接下来没有别的了，已完成。")),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, gateway.requests.size)
    }

    // ---------------------------------------------------------------- tool timeout (R60)

    @Test
    fun `a tool that overruns its descriptor timeout is ACTION_TIMEOUT and the run continues`() {
        val executor = SleepingExecutor(descriptor("slow", timeoutMillis = 50L), sleepMillis = 5_000L)
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "先看看屏幕")),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, gateway.requests.size, "a timeout is a tool result, not a run-ending verdict")
        assertEquals(1, run.steps.size)
        assertTrue(run.steps.single().result.startsWith("ACTION_TIMEOUT"), run.steps.single().result)
        assertTrue(outputs(run).single().output.contains("observe", ignoreCase = true))
        // The executor's own command was cancelled, so its worker does not outlive the run.
        executor.awaitWorkers(1_000)
        assertTrue(executor.workers.none { it.isAlive }, "the tool worker must be stopped")
    }

    @Test
    fun `a custom timeout instruction is the one the model receives`() {
        val executor = SleepingExecutor(descriptor("slow", timeoutMillis = 50L), sleepMillis = 5_000L)
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "先看看屏幕")),
        )
        val loop = AgentLoop(
            gateway,
            ToolRegistry(listOf(executor)),
            ScriptedPolicy(),
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            timeoutInstruction = "资源超时指令",
        )

        val run = loop.run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertTrue(outputs(run).single().output.contains("资源超时指令"), outputs(run).single().output)
        executor.awaitWorkers(1_000)
    }

    @Test
    fun `a tool that will not stop is ACTION_TIMEOUT_UNCERTAIN and ends the run before the next call`() {
        val executor = StuckExecutor(descriptor("stuck", timeoutMillis = 50L))
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "stuck"), call("c2", "stuck-second"))),
                reply(text = "never reached"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("ACTION_TIMEOUT_UNCERTAIN", run.safeCode)
        assertEquals(1, executor.started.get(), "the second write must never be issued")
        assertEquals(1, run.steps.size)
        assertEquals(1, gateway.requests.size, "no model request after an uncertain write")
        assertTrue(run.steps.single().result.startsWith("ACTION_TIMEOUT_UNCERTAIN"))
        assertEquals(0, executor.beginTurns.get(), "an uncertain stop must never re-arm the executors")
        executor.release()
    }

    @Test
    fun `a confirmed timeout re-arms the executors so a later call in the same turn can act`() {
        // The host executor's `cancel` is turn-scoped (DeviceKernel), so the timeout's stop request leaves it
        // cancelled until a `beginTurn`. Without the re-arm, the very next call in the same run is CANCELLED.
        val executor = TurnScopedExecutor(descriptor("slow", timeoutMillis = 50L), descriptor("quick"))
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "slow"), call("c2", "quick"))),
                reply(text = "done"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, run.steps.size)
        assertTrue(run.steps[0].result.startsWith("ACTION_TIMEOUT"), run.steps[0].result)
        assertEquals("ok", run.steps[1].result, "the next call must run against a re-armed executor")
        assertEquals(2, executor.calls.size, "the second call reached the executor")
        assertEquals(1, executor.beginTurns.get(), "the timeout recovery path re-arms exactly once")
    }

    @Test
    fun `a confirmed timeout carries the failure observation and stays non uncertain`() {
        val executor = SleepingExecutor(
            descriptor("slow", timeoutMillis = 50L, screenObservation = true),
            sleepMillis = 5_000L,
        )
        val observation = ToolObservation(
            foregroundPackage = "com.android.settings",
            screen = "Settings",
            vision = ToolVision.NotRequested,
        )
        val deadlines = mutableListOf<Long>()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "先看看屏幕")),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { deadlineMillis ->
                deadlines += deadlineMillis
                observation
            }),
        )

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(listOf(250L), deadlines, "a timeout observation gets its own short deadline")
        val envelope = envelopeInRequest(gateway)
        val execution = envelope.getValue("execution").jsonObject
        assertEquals("timeout", execution.getValue("outcome").jsonPrimitive.content)
        assertEquals("ACTION_TIMEOUT", execution.getValue("code").jsonPrimitive.content)
        assertFalse(execution.getValue("uncertain").jsonPrimitive.boolean, "a confirmed stop is not uncertain")
        assertTrue(execution.getValue("applied") is JsonNull, "a timed-out write's effect is unknown")
        assertTrue(execution.getValue("changed") is JsonNull, "a timed-out write's change is unknown")
        val observationJson = envelope.getValue("observation").jsonObject
        assertEquals("Settings", observationJson.getValue("screen").jsonPrimitive.content)
        assertEquals("com.android.settings", observationJson.getValue("foregroundPackage").jsonPrimitive.content)
        assertEquals("not_requested", observationJson.getValue("vision").jsonPrimitive.content)
        // Task 8: the model saw it; the stored transcript must not keep the observation text.
        assertFalse(outputs(run).single().output.contains("Settings"))
        executor.awaitWorkers(1_000)
    }

    @Test
    fun `a confirmed timeout without a source still reports an unavailable observation`() {
        val executor = SleepingExecutor(
            descriptor("slow", timeoutMillis = 50L, screenObservation = true),
            sleepMillis = 5_000L,
        )
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "先看看屏幕")),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val observation = envelopeInRequest(gateway).getValue("observation").jsonObject
        assertEquals("unavailable", observation.getValue("vision").jsonPrimitive.content)
        assertEquals("no_source", observation.getValue("visionReason").jsonPrimitive.content)
        executor.awaitWorkers(1_000)
    }

    @Test
    fun `an uncertain stop carries a best-effort observation and never a stable result`() {
        val executor = StuckExecutor(descriptor("stuck", timeoutMillis = 50L, screenObservation = true))
        val observation = ToolObservation(
            foregroundPackage = "com.android.settings",
            screen = "Settings",
            vision = ToolVision.NotRequested,
        )
        val deadlines = mutableListOf<Long>()
        var sourceCalls = 0
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "stuck"), call("c2", "stuck-second")))),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { deadlineMillis ->
                sourceCalls++
                deadlines += deadlineMillis
                observation
            }),
        )

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("ACTION_TIMEOUT_UNCERTAIN", run.safeCode)
        assertEquals(1, sourceCalls, "the uncertain call still asks the source once")
        assertEquals(listOf(250L), deadlines, "an uncertain observation gets the short failure deadline")
        assertEquals(
            "ACTION_TIMEOUT_UNCERTAIN",
            run.steps.single().result,
            "the step keeps the stable code even though the payload is content",
        )
        // Task 8: the run ends here, so the model never receives this output; the observation (and the execution facts
        // inside the same payload) must not be persisted. The pair stays attributable through the safe marker.
        val stored = outputs(run).single()
        assertFalse(stored.output.contains("Settings"), "the uncertain observation may not be stored: ${stored.output}")
        assertTrue(stored.output.isNotBlank(), "the call/output pair keeps a marker")
        assertEquals(1, gateway.requests.size, "the uncertain result never reaches another request")
        executor.release()
    }

    @Test
    fun `a confirmed timeout is re-armed before the failure observation source runs`() {
        // Review I1: the source reads the device through the very executors the timeout just cancelled, so a confirmed
        // timeout must re-arm them before asking. Otherwise the 250 ms capture sees a CANCELLED kernel / stale screen.
        val executor = TurnScopedExecutor(
            descriptor("slow", timeoutMillis = 50L, screenObservation = true),
            descriptor("quick"),
        )
        val beginTurnsWhenObserved = AtomicInteger(-1)
        var sourceCalls = 0
        val deadlines = mutableListOf<Long>()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "先看看屏幕")),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { deadlineMillis ->
                sourceCalls++
                deadlines += deadlineMillis
                beginTurnsWhenObserved.set(executor.beginTurns.get())
                ToolObservation(screen = "Settings", vision = ToolVision.NotRequested)
            }),
        )

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(1, sourceCalls, "a confirmed timeout asks the source exactly once")
        assertEquals(listOf(250L), deadlines, "the failure observation gets the short deadline")
        assertEquals(
            1,
            beginTurnsWhenObserved.get(),
            "the confirmed timeout must re-arm before the source observes, not after",
        )
        assertEquals(1, executor.beginTurns.get(), "the confirmed timeout still re-arms exactly once")
    }

    @Test
    fun `an uncertain stop never re-arms before or after the failure observation`() {
        // An uncertain stop may still be in flight: no later write may run, so the executors stay cancelled. The source
        // is still asked once (the screen may be readable), but it must not observe a re-armed executor.
        val executor = StuckExecutor(descriptor("stuck", timeoutMillis = 50L, screenObservation = true))
        val beginTurnsWhenObserved = AtomicInteger(-1)
        var sourceCalls = 0
        val deadlines = mutableListOf<Long>()
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "stuck"), call("c2", "stuck-second")))),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(
            request(observationSource = ToolObservationSource { deadlineMillis ->
                sourceCalls++
                deadlines += deadlineMillis
                beginTurnsWhenObserved.set(executor.beginTurns.get())
                ToolObservation(screen = "Settings", vision = ToolVision.NotRequested)
            }),
        )

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("ACTION_TIMEOUT_UNCERTAIN", run.safeCode)
        assertEquals(1, sourceCalls, "an uncertain stop asks the source exactly once")
        assertEquals(listOf(250L), deadlines, "the failure observation gets the short deadline")
        assertEquals(0, beginTurnsWhenObserved.get(), "an uncertain stop must never re-arm the executors")
        assertEquals(0, executor.beginTurns.get(), "the re-arm stays off on the uncertain path")
        executor.release()
    }

    // ---------------------------------------------------------------- bounded failure capture (M4)

    @Test
    fun `a bounded failure capture returns observation timeout and the loop still completes`() {
        val executor = executor(descriptor("open", screenObservation = true))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "看到了")),
        )
        val deadlines = mutableListOf<Long>()
        // `observe` is booby-trapped: the loop must take the image-aware capture path, not the pre-M4 one.
        val source = object : ToolObservationSource {
            override fun observe(deadlineMillis: Long): ToolObservation? =
                error("the loop must use observeWithImages, not observe")

            override fun observeWithImages(deadlineMillis: Long): ToolObservationCapture {
                deadlines += deadlineMillis
                // A source that uses its whole budget answers an explicit timeout fact, never a silent null.
                Thread.sleep(deadlineMillis)
                return ToolObservationCapture(
                    ToolObservation(vision = ToolVision.Unavailable, visionReason = "observation_timeout"),
                )
            }
        }

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "a bounded capture never fails the run")
        assertEquals(listOf(250L), deadlines, "the failure observation's whole budget is 250 ms")
        val observation = envelopeInRequest(gateway).getValue("observation").jsonObject
        assertEquals("unavailable", observation.getValue("vision").jsonPrimitive.content)
        assertEquals("observation_timeout", observation.getValue("visionReason").jsonPrimitive.content)
        assertEquals(
            emptyList(),
            gateway.requests.last().history.filterIsInstance<ModelHistoryItem.ToolOutput>().single().images,
            "a timed-out capture must not carry an image",
        )
        assertEquals(emptyList(), outputs(run).single().images)
    }

    @Test
    fun `a failure capture image rides the next model request and never the stored transcript`() {
        val image = ImageRef("artifact-shot-1", "image/jpeg", 768, 1664, "sha-256-shot-1")
        val executor = executor(descriptor("open", screenObservation = true))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "看到了")),
        )
        val capture = ToolObservationCapture(
            ToolObservation(
                foregroundPackage = "com.android.settings",
                screen = "Settings",
                vision = ToolVision.Ok,
            ),
            images = listOf(image),
        )
        val source = object : ToolObservationSource {
            override fun observe(deadlineMillis: Long): ToolObservation? =
                error("the loop must use observeWithImages, not observe")

            override fun observeWithImages(deadlineMillis: Long): ToolObservationCapture = capture
        }

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        val sent = gateway.requests.last().history
            .filterIsInstance<ModelHistoryItem.ToolOutput>()
            .single()
        assertEquals(listOf(image), sent.images, "the capture's image must reach the next request: $sent")
        assertTrue(sent.output.contains("\"screen\":\"Settings\""), sent.output)
        assertEquals(1, run.metrics.images, "the capture image is produced by this run and counted")

        // The same privacy rule as every observation: the model saw it in this run; the stored transcript may not.
        val stored = outputs(run).single()
        assertEquals("(perception content not persisted)", stored.output)
        assertEquals(emptyList(), stored.images, "a failure-capture image may never be stored")
    }

    @Test
    fun `a thrown screen tool's capture image rides the next model request and never the stored transcript`() {
        // M7 (spec §6.7): a `TOOL_THREW` screen tool is still a non-Done result, so the same `observeWithImages` capture
        // path must attach the failure image. The image is visible to the model in this run and redacted from the store.
        val image = ImageRef("artifact-throw-1", "image/jpeg", 768, 1664, "sha-256-throw-1")
        val executor = executor(descriptor("open", screenObservation = true))
        executor.failure = IllegalStateException("boom")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "看到了")),
        )
        val capture = ToolObservationCapture(
            ToolObservation(
                foregroundPackage = "com.android.settings",
                screen = "Settings",
                vision = ToolVision.Ok,
            ),
            images = listOf(image),
        )
        val source = object : ToolObservationSource {
            override fun observe(deadlineMillis: Long): ToolObservation? =
                error("the loop must use observeWithImages, not observe")

            override fun observeWithImages(deadlineMillis: Long): ToolObservationCapture = capture
        }

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request(observationSource = source))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("TOOL_THREW", run.steps.single().result)
        val sent = gateway.requests.last().history
            .filterIsInstance<ModelHistoryItem.ToolOutput>()
            .single()
        assertEquals(listOf(image), sent.images, "a thrown call's capture image must reach the next request: $sent")
        assertTrue(sent.output.contains("\"screen\":\"Settings\""), sent.output)
        assertEquals(1, run.metrics.images, "the capture image is produced by this run and counted")
        val stored = outputs(run).single()
        assertEquals("(perception content not persisted)", stored.output)
        assertEquals(emptyList(), stored.images, "a thrown call's failure image may never be stored")
    }

    // ---------------------------------------------------------------- human confirmation allowance (R99)

    @Test
    fun `a may ask human call that outlives the turn deadline still returns its result and shifts the deadline`() {
        // The plugin's ConfirmingActions asks the wearer inside `execute` for up to 120 s (D47). The call must get its
        // machine cap plus that human allowance and must not be clamped by the turn deadline; the wait is then shifted
        // out of the turn (D49), so the run continues instead of reporting DEADLINE right after a legal approval.
        val executor = SleepingExecutor(
            descriptor("confirm", timeoutMillis = 50L, mayAskHuman = true),
            sleepMillis = 500L,
        )
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "confirm"))), reply(text = "done")),
        )
        val clock = realClock()

        val run = loop(gateway, ToolRegistry(listOf(executor)), clock = clock).run(
            request(deadlineNanos = System.nanoTime() + 400_000_000L),
        )

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "the shifted deadline must not end the run as DEADLINE")
        assertEquals(2, gateway.requests.size)
        assertEquals("ok", run.steps.single().result, "the human allowance, not the turn deadline, bounds this call")
        executor.awaitWorkers(1_000)
    }

    @Test
    fun `a may ask human wait is reported as human wait and kept out of the tool metric`() {
        // Review I7: the plugin's ConfirmingActions asks the wearer inside `execute` (the shell wrapper), so its wait
        // reached the run only as the deadline shift. A caller that extends its hint window from
        // `run.humanWaitNanos` must see that part too, and it must not be reported as machine (tool) time.
        val executor = SleepingExecutor(
            descriptor("confirm", timeoutMillis = 50L, mayAskHuman = true),
            sleepMillis = 400L,
        )
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "confirm"))), reply(text = "done")),
        )
        val metricLines = mutableListOf<String>()
        val startedAt = System.nanoTime()

        val run = loop(
            gateway, ToolRegistry(listOf(executor)), clock = realClock(), metricLine = { metricLines += it },
        ).run(request(deadlineNanos = System.nanoTime() + 1_000_000_000L))
        val elapsed = System.nanoTime() - startedAt

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        // The 400 ms sleep minus the 50 ms machine cap is ~350 ms of person time: additive to the run's total and
        // bounded by the wall clock the run actually took.
        assertTrue(
            run.humanWaitNanos >= 250_000_000L,
            "the shell confirmation wait must reach humanWaitNanos: ${run.humanWaitNanos}",
        )
        assertTrue(
            run.humanWaitNanos <= elapsed,
            "human wait cannot exceed the wall clock the run took: ${run.humanWaitNanos} > $elapsed",
        )
        // The same wait must stay out of machine time: toolMs is the 50 ms cap, not the 400 ms sleep.
        assertTrue(
            run.metrics.toolMillis < 300L,
            "the human wait must stay out of toolMs: ${run.metrics.toolMillis}",
        )
        // Phase 3 Task 4 adds one per-call P3_TOOLRESULT line on the same sink; the run-level line is the subject here.
        val metricLine = metricLines.single { it.startsWith("P3_METRIC") }
        val toolMs = Regex(" toolMs=(\\d+) ").find(metricLine)?.groupValues?.get(1)?.toLong()
        assertEquals(
            run.metrics.toolMillis, toolMs,
            "P3_METRIC.toolMs must carry the same machine-time count: $metricLine",
        )
        executor.awaitWorkers(1_000)
    }

    @Test
    fun `a non human call is still bounded by the descriptor cap and the turn deadline`() {
        // The allowance is additive only for a descriptor that declares it. An ordinary tool keeps
        // min(timeoutMillis, deadline - now): here the 30 s machine cap would let a 5 s sleep finish, but the turn
        // deadline is 100 ms, so the call times out and the run continues with ACTION_TIMEOUT.
        val executor = SleepingExecutor(descriptor("slow", timeoutMillis = 30_000L), sleepMillis = 5_000L)
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "done")),
        )
        val clock = realClock()

        val run = loop(gateway, ToolRegistry(listOf(executor)), clock = clock).run(
            request(deadlineNanos = System.nanoTime() + 100_000_000L),
        )

        assertEquals(AgentOutcome.DEADLINE, run.outcome, "an ordinary call is still clamped by the turn deadline")
        assertEquals(1, gateway.requests.size, "the deadline ends the turn before another model request")
        assertTrue(run.steps.single().result.startsWith("ACTION_TIMEOUT"), run.steps.single().result)
        executor.awaitWorkers(1_000)
    }

    // ---------------------------------------------------------------- observation budget (R61)

    @Test
    fun `the twenty-fourth observation ends the run as OBSERVATION_BUDGET`() {
        val tools = (1..25).map { descriptor("observe$it", observation = true) }
        val executor = executor(*tools.toTypedArray())
        val calls = (1..25).map { call("c$it", "observe$it", """{"step":$it}""") }
        executor.outcome = { name, _ -> ActionResult(ActionOutcome.Done, name, fingerprint = "fp-$name") }
        val gateway = ScriptedGateway(listOf(reply(calls = calls)))

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.NO_PROGRESS, run.outcome)
        assertEquals("OBSERVATION_BUDGET", run.safeCode)
        assertEquals(24, run.spentObservations)
        assertEquals(24, run.steps.size)
        assertEquals(24, executor.calls.size)
    }

    @Test
    fun `an exempt non-observation tool spends no observation budget`() {
        val executor = executor(actionDescriptor("load_skill", countsTowardStepLimit = false))
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = (1..5).map { call("c$it", "load_skill", """{"skill":"s$it"}""") }),
                reply(text = "读完了"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor))).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(0, run.spentObservations)
        assertEquals(5, run.steps.size)
    }

    // ---------------------------------------------------------------- runtime state (R59)

    @Test
    fun `every request of a run carries one runtime state segment`() {
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = "done"),
            ),
        )
        var state = 0

        val run = loop(gateway, ToolRegistry(listOf(executorOf("lookup")))).run(
            request(
                runtimeState = { TurnRuntimeState("step=${state++} foreground=com.android.settings") },
                frameFor = minimalFrame("打开设置"),
            ),
        )

        assertEquals(2, gateway.requests.size)
        val first = gateway.requests[0].frame!!
        val second = gateway.requests[1].frame!!
        val firstStates = first.conversationFlow.filter { it.origin == SegmentOrigin.RUNTIME_STATE }
        val secondStates = second.conversationFlow.filter { it.origin == SegmentOrigin.RUNTIME_STATE }
        assertEquals(1, firstStates.size)
        assertEquals(1, secondStates.size)
        assertTrue(firstStates.single().text.contains("step=0"))
        assertTrue(secondStates.single().text.contains("step=1"))
        assertFalse(firstStates.single().trusted, "runtime state is untrusted input")
        // It is request metadata only: the run's transcript keeps no copy.
        assertTrue(run.transcript.none { it is ModelHistoryItem.Text && it.text.contains("foreground=") })
    }

    @Test
    fun `an unchanged runtime state is injected only once`() {
        // A retry is the one continuation that executes no tool: the loop-owned counters are unchanged, so the whole
        // composed line — device state and counters — is identical and the second request must save those tokens.
        val gateway = ScriptedGateway(
            listOf(
                reply(),
                reply(text = "done"),
            ),
        )

        val run = loop(gateway, ToolRegistry(emptyList())).run(
            request(
                runtimeState = { TurnRuntimeState("foreground=com.android.settings revision=4") },
                frameFor = minimalFrame("打开设置"),
            ),
        )

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, gateway.requests.size)
        assertEquals(
            1,
            gateway.requests[0].frame!!.conversationFlow.count { it.origin == SegmentOrigin.RUNTIME_STATE },
        )
        assertEquals(
            0,
            gateway.requests[1].frame!!.conversationFlow.count { it.origin == SegmentOrigin.RUNTIME_STATE },
            "the identical state was already sent and must save tokens",
        )
    }

    @Test
    fun `the runtime state carries loop counters and write and typing flags`() {
        // R100: the loop's own state is part of the line, not just the device's. A state-changing call with no
        // fingerprint is an unverified write; a state-changing call that carries an input value is typed-but-unsent
        // until the next state-changing call.
        val executor = executor(descriptor("device", observation = true))
        var step = 0
        executor.outcome = { _, _ ->
            when (++step) {
                1 -> ActionResult(ActionOutcome.Done, "typed", stateChanging = true)
                2 -> ActionResult(ActionOutcome.Done, "pressed", fingerprint = "screen", stateChanging = true)
                else -> ActionResult(ActionOutcome.Done, "observed")
            }
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(
                    calls = listOf(
                        call("c1", "device", """{"action":"act","kind":"type","ref":"#n1","value":"hello"}"""),
                    ),
                ),
                reply(calls = listOf(call("c2", "device", """{"action":"act","kind":"press_key","key":"enter"}"""))),
                reply(calls = listOf(call("c3", "device", """{"action":"observe"}"""))),
                reply(text = "done"),
            ),
        )

        loop(gateway, ToolRegistry(listOf(executor))).run(
            request(
                runtimeState = { TurnRuntimeState("foreground=com.android.settings revision=4") },
                frameFor = minimalFrame("打开设置"),
            ),
        )

        val opening = runtimeStateOf(gateway.requests[0])!!
        assertTrue(opening.contains("foreground=com.android.settings"), opening)
        assertTrue(opening.contains("steps=0"), opening)
        assertTrue(opening.contains("observations=0"), opening)
        assertTrue(opening.contains("writeUnverified=no"), opening)
        assertTrue(opening.contains("typedUnsent=no"), opening)

        val afterTyping = runtimeStateOf(gateway.requests[1])!!
        assertTrue(afterTyping.contains("steps=1"), afterTyping)
        assertTrue(afterTyping.contains("observations=1"), afterTyping)
        assertTrue(afterTyping.contains("writeUnverified=yes"), "a write with no fingerprint: $afterTyping")
        assertTrue(afterTyping.contains("typedUnsent=yes"), "a typed input was not followed by a write: $afterTyping")

        val afterSend = runtimeStateOf(gateway.requests[2])!!
        assertTrue(afterSend.contains("writeUnverified=no"), "the write carries a fingerprint: $afterSend")
        assertTrue(afterSend.contains("typedUnsent=no"), "the next state-changing call clears the flag: $afterSend")
    }

    @Test
    fun `a composed runtime state stays within the 300 character bound`() {
        // The provider hands over a line already at the cap; the loop-owned fields must still be present, so the
        // composition trims the device half instead of dropping the counters or exceeding `TurnRuntimeState.MAX_CHARS`.
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        loop(gateway, ToolRegistry(emptyList())).run(
            request(
                runtimeState = { TurnRuntimeState("x".repeat(TurnRuntimeState.MAX_CHARS)) },
                frameFor = minimalFrame("现在几点了"),
            ),
        )

        val state = runtimeStateOf(gateway.requests.single())!!
        assertTrue(state.length <= TurnRuntimeState.MAX_CHARS, "length=${state.length}")
        assertTrue(state.contains("steps=0"), state)
        assertTrue(state.contains("observations=0"), state)
        assertTrue(state.contains("writeUnverified=no"), state)
        assertTrue(state.contains("typedUnsent=no"), state)
    }

    private fun runtimeStateOf(turn: GatewayTurn): String? =
        turn.frame?.conversationFlow?.singleOrNull { it.origin == SegmentOrigin.RUNTIME_STATE }?.text

    @Test
    fun `a runtime state is bounded to 300 characters and never blank`() {
        assertFailsWith<IllegalArgumentException> { TurnRuntimeState("   ") }
        assertFailsWith<IllegalArgumentException> { TurnRuntimeState("x".repeat(TurnRuntimeState.MAX_CHARS + 1)) }
        TurnRuntimeState("x".repeat(TurnRuntimeState.MAX_CHARS))
    }

    // ---------------------------------------------------------------- cancel propagation (R61)

    @Test
    fun `cancel while a tool is running ends the run and appends no unfinished output`() {
        val executor = BlockingExecutor(descriptor("block"))
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "block"))), reply(text = "never reached")),
        )
        val agent = loop(gateway, ToolRegistry(listOf(executor)))
        val finished = CountDownLatch(1)
        var run: AgentRun? = null
        val runner = thread(name = "guard-cancel-test") {
            run = agent.run(request())
            finished.countDown()
        }

        assertTrue(executor.started.await(5, TimeUnit.SECONDS), "the tool must be running")
        agent.cancel()
        agent.cancel() // repeated cancel is idempotent

        assertTrue(finished.await(10, TimeUnit.SECONDS), "cancel must reach the blocked tool")
        assertEquals(AgentOutcome.CANCELLED, run?.outcome)
        assertEquals(1, executor.cancels.get(), "the registry cancels the executor exactly once")
        assertEquals(0, executor.beginTurns.get(), "a STOP/cancel must never re-arm the executors")
        assertTrue(run!!.steps.isEmpty(), "the cancelled call never becomes a step")
        assertTrue(outputs(run!!).isEmpty(), "no unfinished tool output is appended")
        assertTrue(gateway.requests.size == 1)
        executor.release()
        runner.join(5_000)
    }

    // ---------------------------------------------------------------- P3_TOOLRESULT (Phase 3 Task 4)

    /** The per-call lines only; the loop's one run-level `P3_METRIC` shares the same sink. */
    private fun toolResultLines(lines: List<String>): List<String> = lines.filter { it.startsWith("P3_TOOLRESULT") }

    /**
     * P3-9 (spec §7.1): one count-only `P3_TOOLRESULT` line per tool call, on the loop's existing sink.
     *
     * The exact literal pins the required field order. `shape` is `ad4e36aa`, the independently computed digest of this
     * done envelope's shape (all strings masked, numbers zeroed, `{"ok":true}` kept); the envelope's own algorithm is
     * pinned separately in `ToolResultEnvelopeTest`. No argument, payload or observation text may reach the line.
     */
    @Test
    fun `a tool call emits one P3_TOOLRESULT line with the exact field order and no content`() {
        val lines = mutableListOf<String>()
        val executor = executor(descriptor("lookup"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, """{"ok":true}""") }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup", """{"secret":"MARKER-ARGUMENT"}"""))),
                reply(text = "答"),
            ),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor)), metricLine = { lines += it }).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(
            listOf(
                "P3_TOOLRESULT turn=turn-guard call=c1 tool=lookup outcome=done code=- recoverable=false " +
                    "accepted=- applied=- verified=- changed=- uncertain=false partial=false observation=none " +
                    "vision=not_requested screenChars=- images=0 shape=ad4e36aa",
            ),
            toolResultLines(lines),
        )
        assertFalse(lines.any { it.contains("MARKER-ARGUMENT") }, lines.toString())
    }

    @Test
    fun `two calls in one round emit one line each`() {
        val lines = mutableListOf<String>()
        val executor = executor(descriptor("lookup"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "done") }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup", """{"page":1}"""), call("c2", "lookup", """{"page":2}"""))),
                reply(text = "答"),
            ),
        )

        loop(gateway, ToolRegistry(listOf(executor)), metricLine = { lines += it }).run(request())

        assertEquals(listOf("c1", "c2"), toolResultLines(lines).map { Regex(" call=(\\S+) ").find(it)!!.groupValues[1] })
    }

    @Test
    fun `a thrown call emits an uncertain line with unknown tri states`() {
        val lines = mutableListOf<String>()
        val executor = executor(descriptor("open"))
        executor.failure = RuntimeException("boom")
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "好")),
        )

        loop(gateway, ToolRegistry(listOf(executor)), metricLine = { lines += it }).run(request())

        val line = toolResultLines(lines).single()
        assertTrue(line.contains(" tool=open outcome=uncertain code=TOOL_THREW "), line)
        assertTrue(line.contains(" accepted=- applied=- verified=- changed=- uncertain=true "), line)
        assertTrue(Regex(" shape=[0-9a-f]{8}$").containsMatchIn(line), line)
    }

    @Test
    fun `a refused call emits its own line with the legacy stable code`() {
        val lines = mutableListOf<String>()
        val executor = executor(descriptor("shell"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "STATE_UNCHANGED") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "shell"))), reply(text = "好")),
        )

        loop(gateway, ToolRegistry(listOf(executor)), metricLine = { lines += it }).run(request())

        val line = toolResultLines(lines).single()
        assertTrue(line.contains(" tool=shell outcome=refused code=STATE_UNCHANGED "), line)
        assertTrue(line.contains(" accepted=- applied=- verified=- changed=- uncertain=false "), line)
    }

    @Test
    fun `a confirmed timeout emits a timeout line`() {
        val lines = mutableListOf<String>()
        val executor = SleepingExecutor(descriptor("slow", timeoutMillis = 50L), sleepMillis = 5_000L)
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "slow"))), reply(text = "先看看屏幕")),
        )

        loop(gateway, ToolRegistry(listOf(executor)), metricLine = { lines += it }).run(request())

        val line = toolResultLines(lines).single()
        assertTrue(line.contains(" tool=slow outcome=timeout code=ACTION_TIMEOUT "), line)
        assertTrue(line.contains(" accepted=- applied=- verified=- changed=- uncertain=false "), line)
        executor.awaitWorkers(1_000)
    }

    @Test
    fun `a call carrying one image reports that call's image count and no artifact identity`() {
        val lines = mutableListOf<String>()
        val image = ImageRef("artifact-per-call", "image/jpeg", 768, 1664, "sha256-per-call")
        val executor = executor(descriptor("shot"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "done", images = listOf(image)) }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "shot"))), reply(text = "好")),
        )

        val run = loop(gateway, ToolRegistry(listOf(executor)), metricLine = { lines += it }).run(request())

        val line = toolResultLines(lines).single()
        assertTrue(line.contains(" observation=image "), line)
        assertTrue(line.contains(" images=1 "), line)
        assertFalse(line.contains("artifact-per-call"), line)
        assertFalse(line.contains("sha256-per-call"), line)
        assertEquals(1, run.metrics.images, "the run-level image count is unchanged")
    }

    @Test
    fun `a failed screen call logs the observation kind and screen length but never the screen text`() {
        val lines = mutableListOf<String>()
        val executor = executor(descriptor("open", screenObservation = true))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED") }
        val observation = ToolObservation(
            foregroundPackage = "com.secret.package",
            screen = "SECRET-SCREEN-TEXT",
            vision = ToolVision.NotRequested,
        )
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "open"))), reply(text = "好")),
        )

        loop(
            gateway,
            ToolRegistry(listOf(executor)),
            metricLine = { lines += it },
        ).run(request(observationSource = ToolObservationSource { observation }))

        val line = toolResultLines(lines).single()
        assertTrue(line.contains(" observation=text "), line)
        assertTrue(line.contains(" vision=not_requested "), line)
        assertTrue(line.contains(" screenChars=18 "), line)
        assertFalse(line.contains("SECRET-SCREEN-TEXT"), line)
        assertFalse(line.contains("com.secret.package"), line)
    }

    @Test
    fun `a throwing metric sink cannot change the tool result or the run outcome`() {
        val executor = executor(descriptor("lookup"))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "done") }
        val gateway = ScriptedGateway(
            listOf(reply(calls = listOf(call("c1", "lookup"))), reply(text = "答")),
        )
        val agent = AgentLoop(
            gateway,
            ToolRegistry(listOf(executor)),
            ScriptedPolicy(),
            ProgressGuard(),
            RecordingAuditSink(),
            FakeClock(),
            metricLine = { error("logger exploded") },
        )

        val run = agent.run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome, "telemetry is never a verdict")
        assertEquals("ok", run.steps.single().result)
    }

    // ---------------------------------------------------------------- fakes

    /** A quick tool whose worker thread is observable, so the test can prove the loop's executor is shut down. */
    private class SleepingExecutor(
        private val descriptor: ActionDescriptor,
        private val sleepMillis: Long,
    ) : ActionExecutor {
        val workers = mutableListOf<Thread>()
        @Volatile
        private var cancelled = false

        override fun descriptors(): List<ActionDescriptor> = listOf(descriptor)

        override fun execute(name: String, argsJson: String): ActionResult {
            val worker = Thread.currentThread()
            synchronized(workers) { workers += worker }
            if (!cancelled) {
                try {
                    Thread.sleep(sleepMillis)
                } catch (_: InterruptedException) {
                    // A cancelled call ends here; the loop already decided it timed out.
                }
            }
            return ActionResult(ActionOutcome.Done, "too late")
        }

        override fun cancel() {
            cancelled = true
            synchronized(workers) { workers.forEach { it.interrupt() } }
        }

        fun awaitWorkers(millis: Long) {
            synchronized(workers) { workers.toList() }.forEach { it.join(millis) }
        }
    }

    /** A tool that ignores both `cancel()` and interrupts: the only honest answer is ACTION_TIMEOUT_UNCERTAIN. */
    private class StuckExecutor(private val descriptor: ActionDescriptor) : ActionExecutor {
        val started = AtomicInteger()
        val beginTurns = AtomicInteger()
        private val release = CountDownLatch(1)

        override fun descriptors(): List<ActionDescriptor> = listOf(descriptor)

        override fun execute(name: String, argsJson: String): ActionResult {
            started.incrementAndGet()
            while (true) {
                try {
                    if (release.await(1, TimeUnit.SECONDS)) break
                } catch (_: InterruptedException) {
                    // Deliberately ignored: this fake models a wedged platform call.
                }
            }
            return ActionResult(ActionOutcome.Done, "late")
        }

        override fun cancel() = Unit

        override fun beginTurn() {
            beginTurns.incrementAndGet()
        }

        fun release() = release.countDown()
    }

    /**
     * A turn-scoped executor like the device kernel: [cancel] refuses every later call until [beginTurn].
     *
     * The `slow` tool overruns its descriptor timeout (the loop's cancel then interrupts it); the `quick` tool proves
     * whether the same turn was re-armed before the next call.
     */
    private class TurnScopedExecutor(
        private val slow: ActionDescriptor,
        private val quick: ActionDescriptor,
    ) : ActionExecutor {
        val beginTurns = AtomicInteger()
        val calls = mutableListOf<String>()
        private val cancelled = AtomicBoolean(false)
        @Volatile
        private var worker: Thread? = null

        override fun descriptors(): List<ActionDescriptor> = listOf(slow, quick)

        override fun execute(name: String, argsJson: String): ActionResult {
            calls += name
            if (cancelled.get()) return ActionResult(ActionOutcome.Failed, "CANCELLED")
            if (name == slow.name) {
                worker = Thread.currentThread()
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    // The timeout's cancel interrupts the call; its late answer is discarded by the loop.
                }
                return ActionResult(ActionOutcome.Done, "late")
            }
            return ActionResult(ActionOutcome.Done, "quick")
        }

        override fun cancel() {
            cancelled.set(true)
            worker?.interrupt()
        }

        override fun beginTurn() {
            beginTurns.incrementAndGet()
            cancelled.set(false)
        }
    }

    /** A tool that blocks until released by the test: a cancelled call never returns, so it stays unfinished. */
    private class BlockingExecutor(private val descriptor: ActionDescriptor) : ActionExecutor {
        val started = CountDownLatch(1)
        val cancels = AtomicInteger()
        val beginTurns = AtomicInteger()
        private val stop = CountDownLatch(1)

        override fun descriptors(): List<ActionDescriptor> = listOf(descriptor)

        override fun execute(name: String, argsJson: String): ActionResult {
            started.countDown()
            while (true) {
                try {
                    if (stop.await(1, TimeUnit.SECONDS)) return ActionResult(ActionOutcome.Done, "late")
                } catch (_: InterruptedException) {
                    return ActionResult(ActionOutcome.Done, "late")
                }
            }
        }

        override fun cancel() {
            // Deliberately does not release the call: this is the "unfinished at cancellation" case R61 names.
            cancels.incrementAndGet()
        }

        override fun beginTurn() {
            beginTurns.incrementAndGet()
        }

        fun release() = stop.countDown()
    }
}
