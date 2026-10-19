package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.testsupport.FakeClock
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
    ) = AgentRequest(
        turnId = "turn-guard",
        utterance = "打开设置",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = deadlineNanos,
        frameFor = frameFor,
        allowPlanNudge = allowPlanNudge,
        runtimeState = runtimeState,
    )

    private fun loop(
        gateway: ModelGateway,
        registry: ToolRegistry,
        guard: ProgressGuard = ProgressGuard(),
        clock: MonotonicClock = FakeClock(),
        maxSteps: Int = 24,
        maxObservations: Int = 24,
    ) = AgentLoop(
        gateway,
        registry,
        ScriptedPolicy(),
        guard,
        RecordingAuditSink(),
        clock,
        maxSteps = maxSteps,
        maxObservations = maxObservations,
    )

    private fun descriptor(name: String, observation: Boolean = false, timeoutMillis: Long = 30_000L) =
        actionDescriptor(name, observation = observation, timeoutMillis = timeoutMillis)

    private fun executor(vararg descriptors: ActionDescriptor) = RecordingActionExecutor(descriptors.toList())

    private fun outputs(run: AgentRun): List<ModelHistoryItem.ToolOutput> =
        run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>()

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
        val executor = executor(descriptor("device"), descriptor("recover"))
        executor.outcome = { name, _ ->
            if (name == "recover") ActionResult(ActionOutcome.Done, "done", fingerprint = "screen")
            else ActionResult(ActionOutcome.Refused, "NODE_STALE")
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "device", """{"step":1}"""))),
                reply(calls = listOf(call("c2", "device", """{"step":2}"""))),
                reply(calls = listOf(call("c3", "recover", "{}"))),
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
        val gateway = ScriptedGateway(
            listOf(
                reply(calls = listOf(call("c1", "lookup"))),
                reply(text = "done"),
            ),
        )

        loop(gateway, ToolRegistry(listOf(executorOf("lookup")))).run(
            request(
                runtimeState = { TurnRuntimeState("foreground=com.android.settings revision=4") },
                frameFor = minimalFrame("打开设置"),
            ),
        )

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
