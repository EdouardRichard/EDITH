package edith.agent

import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.InputPart
import edith.plugin.MessageRole
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelProfile
import edith.plugin.ModelStreamEvent
import edith.plugin.OutputModality
import edith.plugin.ToolSpec
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [`ModelClientGateway`] is the adapter between the agent loop's one-shot [GatewayTurn] and `plugin-api`'s streaming
 * [edith.plugin.ModelClient]: it assembles the stateless request (the in-flight user turn becomes the request `input`,
 * never history), accumulates the stream into a [GatewayReply], and — because the loop runs on one thread and the
 * transport delivers on another — owns the turn's deadline and the caller's `stop` while it waits.
 *
 * The clock and scheduler are the `:test-support` fakes, so the deadline is reached by advancing the clock, never by
 * sleeping. The one real-time wait (the async stop test) is bounded and latch-driven; no test calls `Thread.sleep`.
 */
class ModelClientGatewayTest {

    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
    private val profile = gatewayProfile()
    private var lookup: (String) -> ModelProfile? = { profile }
    private val envelopeCalls = mutableListOf<Triple<String, Long, Long>>()
    private val envelopeFor: (String, Long, Long) -> EventEnvelope = { requestId, start, end ->
        envelopeCalls += Triple(requestId, start, end)
        EventEnvelope(SessionId("session"), Generation(1), requestId, start, end)
    }

    private fun gateway(
        client: edith.plugin.ModelClient,
        stop: () -> Boolean = { false },
        audioParts: (() -> List<InputPart>)? = null,
    ) =
        ModelClientGateway(client, { lookup(it) }, clock, scheduler, envelopeFor, stop, audioParts)

    private fun turn(
        turnId: String = "turn-7",
        profileId: String = profile.id,
        instructions: String = "You are Edith.",
        history: List<ModelHistoryItem> = listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点")),
        tools: List<ToolSpec> = emptyList(),
        deadlineNanos: Long = 1_000_000_000L,
        // Phase 5 seams, both appended with defaults so every existing positional call site keeps its meaning:
        // `frame = null` is exactly today's `split(history)` behaviour, and `Int.MAX_VALUE` is the "no override"
        // sentinel the gateway reads as "trim to `windowBytes(profile)`".
        frame: RequestFrame? = null,
        limitBytes: Int = Int.MAX_VALUE,
    ) = GatewayTurn(turnId, profileId, instructions, history, tools, deadlineNanos, frame, limitBytes)

    private fun scripted(vararg events: ModelStreamEvent) =
        FakeModelClient { _, listener -> events.forEach(listener) }

    /**
     * A client that completes the turn on the calling thread. A silent [FakeModelClient] would leave the turn waiting
     * on a deadline the fake clock never advances, so every frame-path test that only inspects the sent request still
     * needs a terminal.
     */
    private fun terminalClient(requestId: String = "turn-7") =
        scripted(ModelStreamEvent.Terminal(requestId, ModelEnd.COMPLETED, null, null))

    // ------------------------------------------------------------ 1. profile lookup

    @Test fun `a missing profile fails without calling the client`() {
        lookup = { null }
        val client = FakeModelClient()

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("PROFILE_MISSING", reply.safeCode)
        assertEquals("", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
        assertTrue(client.requests.isEmpty(), "a missing profile must be refused before any transport call")
    }

    // ------------------------------------------------------------ 2. the input/history split

    @Test fun `the trailing user turn becomes the request input and leaves the earlier transcript as history`() {
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "first"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "one"),
            ModelHistoryItem.ToolCall("c1", "a", "{}"),
            ModelHistoryItem.ToolOutput("c1", "x"),
            ModelHistoryItem.Text(MessageRole.USER, "second"),
        )
        val client = scripted(ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null))

        gateway(client).turn(turn(history = history))

        val request = client.requests.single()
        assertEquals(listOf(InputPart.Text("second")), request.input)
        assertEquals(history.take(4), request.history)
    }

    @Test fun `a transcript that has moved past its user turn stays whole history and sends no input`() {
        // This is the shape the loop hands over after a tool round: `[user, assistant, call, output]`, with no new
        // user text. The transcript already carries the utterance before the assistant's work, so nothing is lifted
        // out of it: the whole transcript stays history, the request carries an empty input, and the wire keeps the
        // tool output last instead of re-asserting the user message after it.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "ask"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling"),
            ModelHistoryItem.ToolCall("c1", "a", "{}"),
            ModelHistoryItem.ToolOutput("c1", "done"),
        )
        val client = scripted(ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null))

        gateway(client).turn(turn(history = history))

        val request = client.requests.single()
        assertTrue(request.input.isEmpty(), "a continuation turn is a history-only request: no input is re-asserted")
        assertEquals(history, request.history)
        assertEquals(
            1,
            request.history.count { it is ModelHistoryItem.Text && it.role == MessageRole.USER },
            "the utterance appears once, in history; it must never be duplicated into input",
        )
    }

    @Test fun `a history with no user turn fails without calling the client`() {
        val client = FakeModelClient()

        val reply = gateway(client).turn(
            turn(history = listOf(ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"), ModelHistoryItem.ToolOutput("c1", "x"))),
        )

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("NO_USER_TURN", reply.safeCode)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun `an empty history fails without calling the client`() {
        val client = FakeModelClient()

        val reply = gateway(client).turn(turn(history = emptyList()))

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("NO_USER_TURN", reply.safeCode)
        assertTrue(client.requests.isEmpty())
    }

    // ------------------------------------------------------------ 2b. long-term memory (Phase 3: moved to TurnRunner)
    // Memory retrieval was removed from ModelClientGateway in Phase 3 (request assembly convergence).
    // The gateway no longer retrieves memory itself; that responsibility moved to agent-runtime's TurnRunner.
    // Tests for memory retrieval behavior now live in TurnRunner tests.

    // ------------------------------------------------------------ 3. one-to-one request mapping

    @Test fun `the turn is mapped one to one onto the model request`() {
        val tools = listOf(ToolSpec("device.set_volume", "sets the volume", """{"type":"object"}"""))
        val client = scripted(ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null))

        gateway(client).turn(turn(instructions = "You are Edith.", tools = tools, deadlineNanos = 4_242))

        val request = client.requests.single()
        assertEquals("turn-7", request.requestId)
        assertEquals("turn-7", request.turnId)
        assertEquals(profile.id, request.profileId)
        assertEquals(profile.revision, request.profileRevision)
        assertEquals("You are Edith.", request.instructions)
        assertEquals(OutputModality.TEXT, request.output)
        assertEquals(4_242, request.deadlineNanos)
        assertEquals(tools, request.tools)
        // Phase 3: context field removed from ModelRequest; memory assembly moved to TurnRunner
        assertEquals(listOf(Triple("turn-7", 0L, 0L)), envelopeCalls, "the request envelope is a point at the turn start")
        assertEquals(EventEnvelope(SessionId("session"), Generation(1), "turn-7", 0, 0), request.envelope)
    }

    @Test fun `the looked up profile supplies the id and revision used by the request`() {
        val revised = gatewayProfile("other-profile").copy(revision = 12)
        lookup = { revised }
        val client = scripted(ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null))

        gateway(client).turn(turn(profileId = "other-profile"))

        val request = client.requests.single()
        assertEquals("other-profile", request.profileId)
        assertEquals(12, request.profileRevision)
    }

    // ------------------------------------------------------------ 4. stream accumulation

    @Test fun `text deltas accumulate in order and only terminal calls become tool calls`() {
        val client = scripted(
            ModelStreamEvent.TextDelta("turn-7", "音量"),
            ModelStreamEvent.ToolCallDelta("turn-7", "item-1", "device.set_volume", """{"level":"""),
            ModelStreamEvent.ToolCallTerminal("turn-7", "item-1", "call-1", "device.set_volume", """{"level":3}"""),
            ModelStreamEvent.TextDelta("turn-7", "已调小"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        val reply = gateway(client).turn(turn())

        assertEquals("音量已调小", reply.text)
        assertEquals(
            listOf(ToolCall("call-1", "device.set_volume", """{"level":3}""", "item-1")),
            reply.toolCalls,
        )
        assertEquals(ModelEnd.COMPLETED, reply.terminal)
    }

    @Test fun `a tool call delta without its terminal produces no call and an incomplete empty turn`() {
        // A `function_call_arguments.delta` whose `output_item.done` never arrives is a truncated call. Reporting it as
        // a completed empty turn would be exactly the silent success the decoder's own gate forbids.
        val client = scripted(
            ModelStreamEvent.ToolCallDelta("turn-7", "item-1", "device.set_volume", """{"level":3}"""),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        val reply = gateway(client).turn(turn())

        assertTrue(reply.toolCalls.isEmpty())
        assertEquals(ModelEnd.INCOMPLETE, reply.terminal)
        assertEquals("TOOL_CALL_DELIVERY_INCOMPLETE", reply.safeCode)
    }

    @Test fun `a truncated call keeps the provider safe code instead of discarding it`() {
        // The delivery is incomplete *and* the provider gave its own machine-readable reason. Re-labelling the verdict
        // must keep that reason: dropping it would erase what the transport actually reported.
        val client = scripted(
            ModelStreamEvent.ToolCallDelta("turn-7", "item-1", "device.set_volume", """{"level":3}"""),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.INCOMPLETE, null, "max_output_tokens"),
        )

        val reply = gateway(client).turn(turn())

        assertTrue(reply.toolCalls.isEmpty())
        assertEquals(ModelEnd.INCOMPLETE, reply.terminal)
        assertEquals("TOOL_CALL_DELIVERY_INCOMPLETE:max_output_tokens", reply.safeCode)
    }

    @Test fun `a dangling tool delta does not override a turn that also produced text`() {
        val client = scripted(
            ModelStreamEvent.ToolCallDelta("turn-7", "item-1", "a", "{}"),
            ModelStreamEvent.TextDelta("turn-7", "answer"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        val reply = gateway(client).turn(turn())

        assertEquals("answer", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
        assertEquals(ModelEnd.COMPLETED, reply.terminal)
    }

    @Test fun `a terminal call is a tool call even without a preceding delta`() {
        val client = scripted(
            ModelStreamEvent.ToolCallTerminal("turn-7", "item-9", "call-9", "lookup", "{}"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        val reply = gateway(client).turn(turn())

        assertEquals(listOf(ToolCall("call-9", "lookup", "{}", "item-9")), reply.toolCalls)
    }

    // ------------------------------------------------------------ 5. terminal mapping

    @Test fun `the terminal event is the reply terminal and carries its safe code`() {
        val client = scripted(ModelStreamEvent.Terminal("turn-7", ModelEnd.INCOMPLETE, null, "max_output_tokens"))

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.INCOMPLETE, reply.terminal)
        assertEquals("max_output_tokens", reply.safeCode)
    }

    @Test fun `a stream that ends without a terminal is failed rather than an empty success`() {
        val client = scripted(ModelStreamEvent.TextDelta("turn-7", "half a thought"))

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("NO_TERMINAL", reply.safeCode)
        assertEquals("half a thought", reply.text)
    }

    @Test fun `an asynchronous stream is awaited past its immediate return`() {
        // The real transport returns from `request` before any event exists. Only a stream that ran to completion on
        // the calling thread and left no terminal is over; a later asynchronous terminal must still be awaited.
        val release = CountDownLatch(1)
        val client = FakeModelClient { request, listener ->
            thread(name = "gateway-delivery") {
                check(release.await(5, TimeUnit.SECONDS))
                listener(ModelStreamEvent.TextDelta(request.requestId, "async"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            }
        }
        var reply: GatewayReply? = null
        val runner = thread(name = "gateway-async-test") { reply = gateway(client).turn(turn()) }

        val giveUpAt = System.nanoTime() + 5_000_000_000L
        while (client.requests.isEmpty() && System.nanoTime() < giveUpAt) Thread.onSpinWait()
        release.countDown()
        runner.join(5_000)

        assertEquals(ModelEnd.COMPLETED, reply?.terminal)
        assertEquals("async", reply?.text)
    }

    @Test fun `the first terminal wins and a later one is ignored`() {
        val client = scripted(
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.FAILED, null, "LATE"),
        )

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(null, reply.safeCode)
    }

    @Test fun `one gateway instance keeps no stream state between two turns`() {
        // The adapter is long-lived (one instance per composition root), so a second turn must not see the first
        // turn's text, tool calls or terminal. Two turns on one instance accumulate independently.
        val client = FakeModelClient { request, listener ->
            val round = request.turnId.last()
            listener(ModelStreamEvent.TextDelta(request.requestId, "turn-$round"))
            listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "item-$round", "call-$round", "lookup", "{}"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        val gateway = gateway(client)

        val first = gateway.turn(turn(turnId = "turn-1"))
        val second = gateway.turn(turn(turnId = "turn-2"))

        assertEquals("turn-1", first.text)
        assertEquals(listOf(ToolCall("call-1", "lookup", "{}", "item-1")), first.toolCalls)
        assertEquals(ModelEnd.COMPLETED, first.terminal)
        assertEquals("turn-2", second.text)
        assertEquals(listOf(ToolCall("call-2", "lookup", "{}", "item-2")), second.toolCalls)
        assertEquals(ModelEnd.COMPLETED, second.terminal)
        assertEquals(2, client.requests.size)
        assertEquals(2, envelopeCalls.size, "each turn builds its own request envelope")
    }

    // ------------------------------------------------------------ 6. deadline

    @Test fun `a deadline reached while the terminal is in flight cancels the underlying request`() {
        // A transport that consumes the whole deadline without producing a terminal: the adapter must not block past
        // it, and it must cancel the handle so the socket read does not outlive the turn.
        val client = FakeModelClient { _, _ -> clock.advance(2_000_000_000L) }

        val reply = gateway(client).turn(turn(deadlineNanos = 1_000_000_000L))

        assertEquals(ModelEnd.DEADLINE, reply.terminal)
        assertEquals("DEADLINE", reply.safeCode)
        assertTrue(reply.toolCalls.isEmpty())
        assertTrue(client.handles.single().cancelled)
    }

    @Test fun `a terminal that arrives before the deadline still wins`() {
        val client = FakeModelClient { request, listener ->
            clock.advance(999_999_999L)
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        val reply = gateway(client).turn(turn(deadlineNanos = 1_000_000_000L))

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
    }

    // ------------------------------------------------------------ 7. stop

    @Test fun `a stop that arrives while the turn is in flight cancels the handle and beats the late terminal`() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val client = FakeModelClient { request, listener ->
            entered.countDown()
            // The listener is released only after the test flips `stop`: the terminal is delivered while the turn is
            // already cancelled, so it must not become a finished reply.
            check(released.await(5, TimeUnit.SECONDS))
            listener(ModelStreamEvent.TextDelta(request.requestId, "late answer"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        val stopped = AtomicBoolean(false)
        var reply: GatewayReply? = null
        val runner = thread(name = "gateway-stop-test") { reply = gateway(client, { stopped.get() }).turn(turn()) }

        assertTrue(entered.await(5, TimeUnit.SECONDS), "the turn must reach the client")
        stopped.set(true)
        released.countDown()
        runner.join(5_000)

        assertEquals(ModelEnd.CANCELLED, reply?.terminal)
        assertEquals("CANCELLED", reply?.safeCode)
        assertTrue(client.handles.single().cancelled)
    }

    @Test fun `a stop is observed while the adapter waits for an asynchronous terminal`() {
        val client = FakeModelClient { _, _ -> }
        val stopped = AtomicBoolean(false)
        var reply: GatewayReply? = null
        val runner = thread(name = "gateway-wait-test") {
            reply = gateway(client, { stopped.get() }).turn(turn(deadlineNanos = 60_000_000_000L))
        }

        val giveUpAt = System.nanoTime() + 5_000_000_000L
        while (client.requests.isEmpty() && System.nanoTime() < giveUpAt) Thread.onSpinWait()
        stopped.set(true)
        runner.join(5_000)

        assertEquals(ModelEnd.CANCELLED, reply?.terminal)
        assertEquals("CANCELLED", reply?.safeCode)
        assertTrue(client.handles.single().cancelled)
    }

    @Test fun `an interrupt while the turn waits cancels the underlying request`() {
        // A delivery thread that never sends a terminal models a blocked transport listener: the turn thread sits in
        // its bounded wait. An interrupt is the turn being torn down from outside, so the client's stream must not
        // outlive it — the handle has to be cancelled, not just the scheduler's timer.
        val delivered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = FakeModelClient { request, listener ->
            thread(name = "gateway-blocked-listener") {
                listener(ModelStreamEvent.TextDelta(request.requestId, "partial"))
                delivered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        var interrupted = false
        val runner = thread(name = "gateway-interrupt-test") {
            try {
                gateway(client).turn(turn())
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }

        assertTrue(delivered.await(5, TimeUnit.SECONDS), "the turn must be waiting on the stream")
        runner.interrupt()
        runner.join(5_000)
        release.countDown()

        assertTrue(interrupted, "the interrupt must reach the caller")
        assertTrue(client.handles.single().cancelled, "the stream must not outlive the interrupted turn")
    }

    // ------------------------------------------------------------ 7b. the RequestFrame path (Phase 5)

    @Test fun `a frame is trimmed and turned into the request before it leaves the gateway`() {
        // AC-5：极端预算下本轮 input 仍在。
        val client = terminalClient()
        val huge = (1..40).joinToString(" ") { "旧回合$it 的正文" }
        val flow = buildList {
            add(Segment(SegmentOrigin.TRANSCRIPT, true, huge, SegmentRole.USER))
            add(Segment(SegmentOrigin.TRANSCRIPT, true, huge, SegmentRole.ASSISTANT))
            add(Segment(SegmentOrigin.USER_INPUT, true, "本轮到底几点了"))
        }
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        gateway(client).turn(turn(frame = frame, limitBytes = 200))

        val sent = client.requests.single()
        assertEquals(listOf("本轮到底几点了"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
        assertEquals(0, sent.history.size, "旧回合被裁掉了")
    }

    @Test fun `a summary reaches the wire with the boundary and as an assistant message`() {
        // AC-6
        val client = terminalClient()
        val flow = listOf(
            Segment(SegmentOrigin.SUMMARY, false, SummaryBoundary.render("先前对话摘要")),
            Segment(SegmentOrigin.TRANSCRIPT, true, "之前问过一句", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "之前答过一句", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        gateway(client).turn(turn(frame = frame, limitBytes = 1_000_000))

        val sent = client.requests.single()
        val texts = sent.history.filterIsInstance<ModelHistoryItem.Text>()
        val summary = texts.single { it.text.startsWith(SummaryBoundary.OPEN) }
        assertEquals(MessageRole.ASSISTANT, summary.role, "摘要必须是 assistant 那条，不能是 user")
        assertEquals(MessageRole.USER, texts.first { it.text == "之前问过一句" }.role, "角色必须来自元数据")
    }

    @Test fun `without a frame the gateway behaves exactly as before`() {
        // 旧通路回归：既有 15 个 GatewayTurn 调用点的形状不能被这次改动影响。
        val client = terminalClient()
        gateway(client).turn(turn(history = listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点"))))
        assertEquals(listOf("把音量调小一点"), client.requests.single().input.filterIsInstance<InputPart.Text>().map { it.text })
    }

    @Test fun `memory evidence survives when only an old turn has to be cut`() {
        // Design D-4's order only holds if a memory segment sits **outside** every turn group: cut ① identifies a turn
        // by `TRANSCRIPT` + `role`, so a memory segment is never part of one. The limit below is computed to be exactly
        // the bytes of the frame **without** the old turn, so it forces ① to cut that turn and leaves the memory in.
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "记".repeat(2_000))
        val oldUser = Segment(SegmentOrigin.TRANSCRIPT, true, "旧".repeat(3_000), SegmentRole.USER)
        val oldAssistant = Segment(SegmentOrigin.TRANSCRIPT, true, "答".repeat(3_000), SegmentRole.ASSISTANT)
        val system = Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")
        val input = Segment(SegmentOrigin.USER_INPUT, true, "现在几点")
        val frame = RequestFrame(listOf(system), listOf(memory, oldUser, oldAssistant, input), emptyList())
        fun bytes(segment: Segment) = segment.text.toByteArray(Charsets.UTF_8).size + 32
        // `TurnTrimmer.total()` at the limit the test passes: fixed 32 + system + memory + input — i.e. the frame
        // without the old turn, so ① must cut it and must not eat the memory.
        val limitWithoutTheOldTurn = FIXED_OVERHEAD + bytes(system) + bytes(memory) + bytes(input)
        val client = terminalClient()
        val gateway = gateway(client)

        gateway.turn(turn(frame = frame, limitBytes = limitWithoutTheOldTurn))

        val report = requireNotNull(gateway.lastTrim)
        assertEquals(1, report.droppedTurns, "① cut exactly one whole turn")
        assertTrue(report.droppedOrigins.isEmpty(), "② never had to touch memory")
        assertTrue(report.kept.conversationFlow.contains(memory), "the memory segment is still in the kept frame")
        assertEquals(
            listOf("现在几点"),
            client.requests.single().input.filterIsInstance<InputPart.Text>().map { it.text },
        )

        // Second half: tighten the window until ① alone cannot fit the frame, so ② has to drop the memory. The memory
        // must then be charged to ② (`droppedOrigins`) while `droppedTurns` stays 1 — the cut that owns the old turn
        // must not also be the cut that silently loses memory, which is the accounting design D-4 depends on.
        client.requests.clear()
        gateway.turn(turn(frame = frame, limitBytes = 4_000))

        val tightened = requireNotNull(gateway.lastTrim)
        assertEquals(1, tightened.droppedTurns, "① still cuts only the turn it owns")
        assertEquals(
            listOf(SegmentOrigin.MEMORY_EVIDENCE),
            tightened.droppedOrigins,
            "the memory fell to cut ② and is reported as such",
        )
        assertFalse(tightened.kept.conversationFlow.contains(memory), "② did drop it, and said so")
    }

    @Test fun `the trim report records counts and never any text`() {
        // The probe-shaped seam: `lastTrim` is for counts only (spec's 只记计数). `TrimReport` cannot carry text by
        // construction (`kept` is a `RequestFrame`, whose `toString` prints no body), and `droppedOrigins` is origins.
        val client = terminalClient()
        val frame = RequestFrame(
            listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "旧".repeat(3_000), SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "答".repeat(3_000), SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, "现在几点"),
            ),
            emptyList(),
        )
        val gateway = gateway(client)

        gateway.turn(turn(frame = frame, limitBytes = 100))

        val report = requireNotNull(gateway.lastTrim)
        assertEquals(1, report.droppedTurns)
        assertTrue(report.droppedOrigins.isEmpty())
        assertFalse(report.toString().contains("旧"), "the report must not carry the trimmed body")
    }

    @Test fun `the trim window is the send time limit minus the fixed overhead`() {
        // The two fixed 32s: `TurnTrimmer.total()` already adds `FIXED_OVERHEAD`, and `ContextBudgeter` adds its own
        // on top of the per-message accounting (`ContextBudgeter.kt:61`). The fixture profile's window is
        // `minOf(60_000, 100_000 - 8_000 - 1_000)` = 60_000, so the trimmer must be handed exactly 59_968 — sharing
        // 60_000 would let it call a frame "fits" that the send-time check rejects by 32.
        assertEquals(59_968, ModelClientGateway.windowBytes(profile))
    }

    @Test fun `the frame path sends one instructions message for the whole system slot`() {
        // `frameToTranscript` must take `instructions` from the frame's whole system slot, not map each system segment
        // to its own message: the trimmer measures that slot as ONE message, so n system segments mapped as n messages
        // would undercount by 32 bytes per extra segment and the send-time check could reject the trimmed request.
        val client = terminalClient()
        val system = listOf(
            Segment(SegmentOrigin.INSTRUCTIONS, true, "base instructions"),
            Segment(SegmentOrigin.SKILL, true, "active skill"),
            Segment(SegmentOrigin.MEMORY_POLICY, true, "memory policy"),
        )
        val frame = RequestFrame(system, listOf(Segment(SegmentOrigin.USER_INPUT, true, "在吗")), emptyList())

        gateway(client).turn(turn(frame = frame, limitBytes = 5_000))

        val sent = client.requests.single()
        assertEquals(
            frame.systemText,
            sent.instructions,
            "the wire instructions must be one string: the system slot joined by newlines, exactly what the trimmer measured",
        )
    }

    // ------------------------------------------------------------ 8. redaction

    @Test fun `the turn never prints the request body or the profile secret`() {
        val sentinel = "SENTINEL-PRIVATE-9f3a"
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val client = FakeModelClient { request, listener ->
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        try {
            System.setOut(PrintStream(out, true, "UTF-8"))
            System.setErr(PrintStream(err, true, "UTF-8"))
            gateway(client).turn(turn(instructions = sentinel, history = listOf(ModelHistoryItem.Text(MessageRole.USER, sentinel))))
            // The repo's redaction convention lives in the contract types' toString; the adapter must not defeat it.
            assertFalse(client.requests.single().toString().contains(sentinel))
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
        assertFalse(out.toString("UTF-8").contains(sentinel), "the adapter must not print the request body")
        assertFalse(err.toString("UTF-8").contains(sentinel), "the adapter must not log the request body")
    }

    @Test fun `audioParts null keeps request input text only`() {
        val client = FakeModelClient { _, listener ->
            listener(ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null))
        }
        gateway(client).turn(turn())
        val req = client.requests.single()
        assertTrue(req.input.all { it is InputPart.Text }, "expected all Text parts, got ${req.input.map { it::class.simpleName }}")
    }

    @Test fun `audioParts appends audio after transcript text`() {
        val client = FakeModelClient { _, listener ->
            listener(ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null))
        }
        gateway(client, audioParts = { listOf(InputPart.Audio("ref-1")) }).turn(turn())
        val req = client.requests.single()
        assertTrue(req.input.last() is InputPart.Audio, "last part should be Audio")
        assertEquals("ref-1", (req.input.last() as InputPart.Audio).localRef)
        assertTrue(req.input.any { it is InputPart.Text }, "text part must still be present")
    }
}
