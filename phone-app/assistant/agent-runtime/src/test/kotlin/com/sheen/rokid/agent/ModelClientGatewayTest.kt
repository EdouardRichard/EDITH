package com.sheen.rokid.agent

import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ToolSpec
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [`ModelClientGateway`] is the adapter between the agent loop's one-shot [GatewayTurn] and `plugin-api`'s streaming
 * [com.sheen.rokid.plugin.ModelClient]: it assembles the stateless request (the in-flight user turn becomes the request `input`,
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
        client: com.sheen.rokid.plugin.ModelClient,
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
        // `Int.MAX_VALUE` is the "no override" sentinel the gateway reads as "trim to the policy's hard limit", and
        // `frame = null` is exactly today's `split(history)` behaviour. `frame` is the last parameter.
        limitTokens: Int = Int.MAX_VALUE,
        frame: RequestFrame? = null,
        // 0.24 S4 Task 16: the streaming observer, appended so every positional call site keeps its meaning.
        onTextDelta: ((String) -> Unit)? = null,
        // 0.25 Task 27 (R143): the wearer's utterance the frame path may restore when trim drops it; null is the
        // pre-Task-27 construction and keeps every existing call site unchanged.
        userUtterance: String? = null,
    ) = GatewayTurn(
        turnId, profileId, instructions, history, tools, deadlineNanos, limitTokens, frame, onTextDelta, userUtterance,
    )

    private fun scripted(vararg events: ModelStreamEvent) =
        FakeModelClient { _, listener -> events.forEach(listener) }

    /**
     * A client that completes the turn on the calling thread. A silent [FakeModelClient] would leave the turn waiting
     * on a deadline the fake clock never advances, so every frame-path test that only inspects the sent request still
     * needs a terminal.
     */
    private fun terminalClient(requestId: String = "turn-7") =
        scripted(ModelStreamEvent.Terminal(requestId, ModelEnd.COMPLETED, null, null))

    /** A frame whose system slot is one instruction segment; the dynamic-tail tests only care about the flow. */
    private fun frame(vararg flow: Segment) =
        RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow.toList(), emptyList())

    /** Runs one real gateway turn over [frame] and answers the request it actually sent. */
    private fun captureRequest(frame: RequestFrame, limitTokens: Int = 1_000_000): ModelRequest {
        val client = terminalClient()
        gateway(client).turn(turn(frame = frame, limitTokens = limitTokens))
        return client.requests.single()
    }

    /** The text the adapter will render in the request tail (audio parts excluded: these frames carry none). */
    private fun inputText(request: ModelRequest): String =
        request.input.filterIsInstance<InputPart.Text>().joinToString("\n") { it.text }

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

    @Test fun `a profile too small for a policy fails closed as PROFILE_TOO_SMALL`() {
        // Before the final-fix wave `ContextPolicy.from` was swallowed with `getOrNull()`, the session had no manager
        // and the turn was reported as `PROFILE_MISSING` even though the profile existed. The code must name the real
        // condition and still refuse before the transport.
        val tooSmall = profile.copy(inputBudgetTokens = 1, maxOutputTokens = 1, safetyTokens = 1)
        lookup = { tooSmall }
        val client = FakeModelClient()

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals(ContextPolicy.PROFILE_TOO_SMALL, reply.safeCode)
        assertTrue(client.requests.isEmpty(), "an unusable profile must be refused before any transport call")
    }

    @Test fun `an activation policy failure is reported with its own code`() {
        // The plugin computes the policy while activating and hands the typed failure to every turn; the gateway must
        // relay that code instead of re-deriving a generic one.
        val client = FakeModelClient()

        val reply = gateway(client).turn(turn().copy(contextFailure = ContextPolicy.PROFILE_INVALID))

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals(ContextPolicy.PROFILE_INVALID, reply.safeCode)
        assertTrue(client.requests.isEmpty(), "a fail-closed session must never reach the transport")
    }

    @Test fun `a fixed prompt that cannot fit the hard input is PROMPT_EXCEEDS_BUDGET`() {
        // The whole static prompt + this turn's input alone exceed the profile's hard input, so no history trim can
        // make the request sendable. The turn must be refused with the dedicated code rather than going out and
        // burning the overflow recoveries on an impossible shape.
        val small = profile.copy(inputBudgetTokens = 100, maxOutputTokens = 1, safetyTokens = 1)
        lookup = { small }
        val frame = RequestFrame(
            listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "i".repeat(400))),
            listOf(Segment(SegmentOrigin.USER_INPUT, true, "你好")),
            emptyList(),
        )
        val client = FakeModelClient()

        val reply = gateway(client).turn(turn(profileId = small.id, frame = frame))

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals(ContextPolicy.PROMPT_EXCEEDS_BUDGET, reply.safeCode)
        assertTrue(client.requests.isEmpty(), "the impossible prompt must never reach the transport")
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

    // ------------------------------------------------------------ 4b. streaming text deltas (0.24 S4 Task 16)

    @Test fun `text deltas reach the observer in wire order and leave the accumulated final text unchanged`() {
        val seen = CopyOnWriteArrayList<String>()
        val client = scripted(
            ModelStreamEvent.TextDelta("turn-7", "今天"),
            ModelStreamEvent.TextDelta("turn-7", "晴。"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        val reply = gateway(client).turn(turn(onTextDelta = { seen += it }))

        assertEquals(listOf("今天", "晴。"), seen, "every delta is handed over in the order the transport delivered it")
        assertEquals("今天晴。", reply.text, "the observer must not change the final assembly")
        assertEquals(ModelEnd.COMPLETED, reply.terminal)
    }

    @Test fun `a tool only stream invokes no text observer`() {
        val seen = CopyOnWriteArrayList<String>()
        val client = scripted(
            ModelStreamEvent.ToolCallTerminal("turn-7", "item-1", "call-1", "notify_user", "{}"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        gateway(client).turn(turn(onTextDelta = { seen += it }))

        assertTrue(seen.isEmpty(), "there is no answer text to stream: $seen")
    }

    @Test fun `a delta after the terminal is neither observed nor allowed to change the final text`() {
        val seen = CopyOnWriteArrayList<String>()
        val client = scripted(
            ModelStreamEvent.TextDelta("turn-7", "答案"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
            ModelStreamEvent.TextDelta("turn-7", "尾巴"),
        )

        val reply = gateway(client).turn(turn(onTextDelta = { seen += it }))

        assertEquals(listOf("答案"), seen, "a late delta is after the verdict and must not reach the display path")
        assertEquals("答案", reply.text, "the final text is the accumulated text at the terminal")
    }

    @Test fun `a throwing text observer does not break the model turn`() {
        val client = scripted(
            ModelStreamEvent.TextDelta("turn-7", "答案"),
            ModelStreamEvent.Terminal("turn-7", ModelEnd.COMPLETED, null, null),
        )

        val reply = gateway(client).turn(turn(onTextDelta = { error("the display refused the revision") }))

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals("答案", reply.text, "a best-effort display callback must never lose the answer")
    }

    @Test fun `the text observer runs on the transport thread`() {
        val threads = CopyOnWriteArrayList<String>()
        lateinit var delivery: Thread
        val client = FakeModelClient { request, listener ->
            delivery = thread(name = "gateway-transport") {
                listener(ModelStreamEvent.TextDelta(request.requestId, "流"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            }
        }

        val reply = gateway(client).turn(turn(onTextDelta = { threads += Thread.currentThread().name }))
        delivery.join(5_000)

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(listOf("gateway-transport"), threads, "the callback belongs to the transport thread, not the loop's")
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

        gateway(client).turn(turn(frame = frame, limitTokens = 200))

        val sent = client.requests.single()
        assertEquals(listOf("本轮到底几点了"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
        assertEquals(0, sent.history.size, "旧回合被裁掉了")
    }

    @Test fun `a runtime state segment folds into the request input and never into the history`() {
        // Task 12 (R59): RUNTIME_STATE is request metadata like a RETRY instruction — it must not become a lone user
        // history group, which `ContextBudgeter` rejects, and it must not push the wearer's own turn out of `input`.
        val client = terminalClient()
        val flow = listOf(
            Segment(SegmentOrigin.RUNTIME_STATE, false, "foreground=com.android.settings revision=4"),
            Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        gateway(client).turn(turn(frame = frame, limitTokens = 1_000_000))

        val sent = client.requests.single()
        assertEquals(0, sent.history.size, "runtime state is not a conversation turn")
        val input = sent.input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.indexOf("foreground=") >= 0, input)
        assertTrue(input.indexOf("foreground=") < input.indexOf("现在呢"), "state rides ahead of the input: $input")
        assertTrue(input.endsWith("现在呢"), "the wearer's turn stays the input: $input")
    }

    @Test fun `a clean-body summary reaches the wire bounded exactly once inside the leading user message`() {
        // AC-6 / I-1: the frame path must render the boundary itself. The store hands over the **bounded** text
        // (I-2), so a frame whose summary arrives pre-rendered would pass even with the render arm deleted —
        // `roleOf(null) == ASSISTANT` supplies the role either way. This case is therefore built by hand with a
        // clean body, which is exactly what a caller-supplied frame can look like.
        //
        // Owner-approved shape (2026-10-16): the summary is not its own message; it is folded into the **one**
        // leading user message, ahead of the oldest surviving transcript text. A standalone leading user message
        // followed by the transcript's own first user turn would be two consecutive user texts, which
        // `ContextBudgeter` rejects by design (see `GatewayWindowAcceptanceTest`).
        val client = terminalClient()
        val body = "先前对话摘要"
        val flow = listOf(
            Segment(SegmentOrigin.SUMMARY, false, body),
            Segment(SegmentOrigin.TRANSCRIPT, true, "之前问过一句", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "之前答过一句", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        gateway(client).turn(turn(frame = frame, limitTokens = 1_000_000))

        val sent = client.requests.single()
        val head = sent.history.first() as ModelHistoryItem.Text
        assertEquals(MessageRole.USER, head.role, "the merged leading context is one user message")
        assertTrue(head.text.startsWith(SummaryBoundary.OPEN), head.text)
        assertTrue(head.text.contains(SummaryBoundary.render(body)), "the clean body must be rendered with the boundary")
        assertEquals(1, head.text.split(SummaryBoundary.OPEN).size - 1, "exactly one boundary: ${head.text}")
        // The context rides the transcript's **oldest surviving** user turn, so that turn's text is inside the head
        // message; every later transcript item keeps its own role and text.
        assertTrue(head.text.endsWith("之前问过一句"), head.text)
        assertEquals(ModelHistoryItem.Text(MessageRole.ASSISTANT, "之前答过一句"), sent.history[1])
        assertEquals(listOf("现在呢"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
    }

    @Test fun `a summary that already carries the boundary is not wrapped twice`() {
        // I-1's second half: the `isRendered` guard. A caller-supplied frame may carry the bounded rendering (the
        // store now does), and the gateway must pass it through rather than nesting a second boundary.
        val client = terminalClient()
        val bounded = SummaryBoundary.render("先前对话摘要")
        val flow = listOf(
            Segment(SegmentOrigin.SUMMARY, false, bounded),
            Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        gateway(client).turn(turn(frame = frame, limitTokens = 1_000_000))

        // The flow has no transcript user turn to carry the context, so it rides the request input itself (a
        // history holding only the leading context is a lone user group, which the budgeter rejects).
        val input = client.requests.single().input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.contains(bounded), input)
        assertEquals(1, input.split(SummaryBoundary.OPEN).size - 1, "no second boundary: $input")
        assertEquals(1, input.split(SummaryBoundary.CLOSE).size - 1, "no second boundary: $input")
    }

    // ------------------------------------------------------------ 7c. the dynamic tail (0.3 Task 10)

    @Test fun `memory and perception do not rewrite the first history message`() {
        // 0.3 design §5.1/§5.3: memory evidence and perception are request-scoped tail context. They must not be folded
        // into `history[0]` (that would rewrite the cacheable prefix on every perception tick) and must instead ride
        // the request input ahead of the wearer's utterance.
        val request = captureRequest(
            frame(
                Segment(SegmentOrigin.TRANSCRIPT, true, "old question", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old answer", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory: likes tea"),
                Segment(SegmentOrigin.PERCEPTION, false, "foreground=com.android.settings"),
                Segment(SegmentOrigin.USER_INPUT, true, "now"),
            ),
        )

        assertEquals("old question", (request.history.first() as ModelHistoryItem.Text).text)
        assertTrue(
            request.history.none { it is ModelHistoryItem.Text && "memory: likes tea" in it.text },
            "memory must never enter the history: ${request.history}",
        )
        assertTrue(
            request.history.none { it is ModelHistoryItem.Text && "foreground=" in it.text },
            "perception must never enter the history: ${request.history}",
        )
        val input = inputText(request)
        assertTrue("memory: likes tea" in input && "foreground=com.android.settings" in input && "now" in input, input)
    }

    @Test fun `skill body is not in the system slot or history`() {
        // Task 9's SKILL_BODY is dynamic, request-scoped content: a `load_skill` pull changes only the request tail, so
        // the system slot and every history item remain byte-identical. The transcript head is deliberate — without
        // it the old head-folding path would already place the body in the input (the empty-history branch), so the
        // test would pin nothing.
        val request = captureRequest(
            frame(
                Segment(SegmentOrigin.TRANSCRIPT, true, "old question", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old answer", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.SKILL_BODY, true, "skill body"),
                Segment(SegmentOrigin.USER_INPUT, true, "now"),
            ),
        )

        assertFalse("skill body" in request.instructions, request.instructions)
        assertTrue(request.history.isEmpty() || request.history.none { it is ModelHistoryItem.Text && "skill body" in it.text })
        assertTrue(request.input.filterIsInstance<InputPart.Text>().any { "skill body" in it.text })
    }

    @Test fun `summary remains the only head context`() {
        // The summary is the one accepted prefix rewrite (§5.1): it stays folded into the head user turn while the
        // dynamic origins move to the tail.
        val request = captureRequest(
            frame(
                Segment(SegmentOrigin.SUMMARY, false, "summary"),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old question", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old answer", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, "now"),
            ),
        )

        assertTrue((request.history.first() as ModelHistoryItem.Text).text.contains("summary"))
    }

    @Test fun `the dynamic tail reaches the request input in flow order before the utterance`() {
        val request = captureRequest(
            frame(
                Segment(SegmentOrigin.TRANSCRIPT, true, "old question", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old answer", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory text"),
                Segment(SegmentOrigin.PERCEPTION, false, "perception text"),
                Segment(SegmentOrigin.SKILL_BODY, true, "skill body text"),
                Segment(SegmentOrigin.RUNTIME_STATE, false, "runtime text"),
                Segment(SegmentOrigin.RETRY, true, "retry text"),
                Segment(SegmentOrigin.USER_INPUT, true, "the utterance"),
            ),
        )

        val input = request.input.filterIsInstance<InputPart.Text>().single().text
        val positions = listOf(
            "memory text",
            "perception text",
            "skill body text",
            "runtime text",
            "retry text",
            "the utterance",
        ).map { marker -> input.indexOf(marker) }
        assertTrue(positions.all { it >= 0 }, "every dynamic block and the utterance must be in the input: $input")
        assertEquals(positions.sorted(), positions, "the dynamic tail must keep the frame's flow order: $input")
        assertTrue(input.endsWith("the utterance"), "the wearer's turn stays last: $input")
        assertEquals("old question", (request.history.first() as ModelHistoryItem.Text).text)
    }

    @Test fun `summary is the only dynamic text folded into the head`() {
        val request = captureRequest(
            frame(
                Segment(SegmentOrigin.SUMMARY, false, "the summary"),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old question", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old answer", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory text"),
                Segment(SegmentOrigin.PERCEPTION, false, "perception text"),
                Segment(SegmentOrigin.USER_INPUT, true, "now"),
            ),
        )

        val head = request.history.first() as ModelHistoryItem.Text
        assertTrue(head.text.contains("the summary"), head.text)
        assertTrue(head.text.endsWith("old question"), "the summary rides the oldest surviving user turn: ${head.text}")
        assertFalse("memory text" in head.text, "memory is tail context, not head context: ${head.text}")
        assertFalse("perception text" in head.text, "perception is tail context, not head context: ${head.text}")
        val input = inputText(request)
        assertTrue("memory text" in input && "perception text" in input, input)
    }

    @Test fun `a summary with no transcript user head goes into the request input`() {
        // The empty-history behaviour is preserved: a history holding only the summary would be a lone user group the
        // send-time budgeter rejects, so the summary rides the input instead.
        val request = captureRequest(
            frame(
                Segment(SegmentOrigin.SUMMARY, false, "orphan summary"),
                Segment(SegmentOrigin.USER_INPUT, true, "now"),
            ),
        )

        assertTrue(request.history.isEmpty(), "a summary alone cannot be history: ${request.history}")
        val input = request.input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.contains("orphan summary"), input)
        assertTrue(input.endsWith("now"), "the wearer's turn stays last: $input")
    }

    @Test fun `the cacheable prefix excludes the dynamic tail while the input carries it`() {
        fun requestFor(memory: String, perception: String, skillBody: String): ModelRequest = captureRequest(
            frame(
                Segment(SegmentOrigin.TRANSCRIPT, true, "old question", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "old answer", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, memory),
                Segment(SegmentOrigin.PERCEPTION, false, perception),
                Segment(SegmentOrigin.SKILL_BODY, true, skillBody),
                Segment(SegmentOrigin.USER_INPUT, true, "now"),
            ),
        )

        val first = requestFor(memory = "memory A", perception = "perception A", skillBody = "skill A")
        val second = requestFor(memory = "memory B", perception = "perception B", skillBody = "skill B")

        assertEquals(
            PrefixHash.of(first.instructions, first.tools, first.history),
            PrefixHash.of(second.instructions, second.tools, second.history),
            "a change in the dynamic tail must not change the cacheable prefix",
        )
        assertNotEquals(
            inputText(first),
            inputText(second),
            "the changed dynamic tail must still reach the request input",
        )
    }

    @Test fun `a frame's tool round reaches the wire as a tool call and its output`() {
        // Phase 5: `AgentLoop.frameFor` rebuilds every request of a run from one renderer, so a tool-round continuation
        // arrives as `TOOL_ROUND` segments. The wire shape for those is not text: the endpoint is stateless and the
        // model's call must be echoed back as a call item beside its result, or the tool exchange does not round-trip
        // (and two assistant texts in a row are a shape `ContextBudgeter` rejects).
        val client = terminalClient()
        val round = buildJsonArray {
            add(buildJsonObject {
                put("type", "call")
                put("callId", "c1")
                put("itemId", "item-1")
                put("name", "notify_user")
                put("argsJson", """{"text":"稍等","channel":"screen"}""")
            })
            add(buildJsonObject {
                put("type", "output")
                put("callId", "c1")
                put("output", "notified:1")
            })
        }.toString()
        val flow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, true, "旧问题", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "旧回答", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.TRANSCRIPT, true, "现在几点", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "(tool call)", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.TOOL_ROUND, true, round),
            Segment(SegmentOrigin.USER_INPUT, true, "现在几点"),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        gateway(client).turn(turn(frame = frame, limitTokens = 1_000_000))

        val sent = client.requests.single()
        assertEquals(
            ModelHistoryItem.ToolCall("c1", "notify_user", """{"text":"稍等","channel":"screen"}""", itemId = "item-1"),
            sent.history[4],
            "the model's own call must come back as a call item: ${sent.history}",
        )
        assertEquals(
            ModelHistoryItem.ToolOutput("c1", "notified:1"),
            sent.history[5],
            "the tool result must come back as its output item: ${sent.history}",
        )
    }

    @Test fun `without a frame the gateway behaves exactly as before`() {
        // 旧通路回归：既有 15 个 GatewayTurn 调用点的形状不能被这次改动影响。
        val client = terminalClient()
        gateway(client).turn(turn(history = listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点"))))
        assertEquals(listOf("把音量调小一点"), client.requests.single().input.filterIsInstance<InputPart.Text>().map { it.text })
    }

    @Test fun `the compacted memory survives in the tail while cut one can still cut an older turn`() {
        // I-3: this test previously ordered the frame `[memory, oldTurn, input]`, which production never assembles
        // (`TurnRunner`: store snapshot → perception → memory → `USER_INPUT`). In the real order the memory sits
        // **after** the last turn group, i.e. inside the ① slice of the group that precedes it — so the guard only
        // passed because the order was inverted. Rewritten against the real order; 0.3 Task 10 additionally moved the
        // surviving memory out of the head user message and into the request input tail.
        //
        // Limit arithmetic (`TurnTrimmer.total()`, `measure = TokenMeter.estimate + 32`, plus the fixed 32): the limit
        // below is exactly the frame **without** the older turn — 32 + 33(sys) + 132(kept user) + 132(kept assistant)
        // + 232(memory) + 36(input) = **597** token units — so cut ① must take that one whole group and stop.
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "记".repeat(200))
        val oldUser = Segment(SegmentOrigin.TRANSCRIPT, true, "旧".repeat(300), SegmentRole.USER)
        val oldAssistant = Segment(SegmentOrigin.TRANSCRIPT, true, "答".repeat(300), SegmentRole.ASSISTANT)
        val keptUser = Segment(SegmentOrigin.TRANSCRIPT, true, "新".repeat(100), SegmentRole.USER)
        val keptAssistant = Segment(SegmentOrigin.TRANSCRIPT, true, "复".repeat(100), SegmentRole.ASSISTANT)
        val system = Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")
        val input = Segment(SegmentOrigin.USER_INPUT, true, "现在几点")
        val frame = RequestFrame(
            listOf(system),
            listOf(oldUser, oldAssistant, keptUser, keptAssistant, memory, input),
            emptyList(),
        )
        fun tokens(segment: Segment) = TokenMeter.estimate(segment.text) + 32
        val withoutTheOlderTurn =
            FIXED_OVERHEAD + tokens(system) + tokens(keptUser) + tokens(keptAssistant) + tokens(memory) + tokens(input)
        val client = terminalClient()
        val gateway = gateway(client)

        gateway.turn(turn(frame = frame, limitTokens = withoutTheOlderTurn))

        val counts = requireNotNull(gateway.lastTrim)
        assertEquals(1, counts.droppedTurns, "① cut exactly the older whole turn")
        assertTrue(counts.droppedOrigins.isEmpty(), "② never had to touch the memory")
        val sent = client.requests.single()
        val head = sent.history.first() as ModelHistoryItem.Text
        assertEquals(MessageRole.USER, head.role, "the history opens on the surviving user turn")
        assertEquals(keptUser.text, head.text, "the head is the transcript's own text: no dynamic context is folded in")
        assertEquals(ModelHistoryItem.Text(MessageRole.ASSISTANT, keptAssistant.text), sent.history[1])
        val sentInput = sent.input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(sentInput.contains(memory.text), "the compacted memory is still on the wire, in the request tail")
        assertTrue(sentInput.endsWith("现在几点"), "the wearer's turn stays last: $sentInput")
        assertFalse(
            sent.history.any { it is ModelHistoryItem.Text && it.text.contains(oldUser.text) },
            "the older turn is gone",
        )
    }

    @Test fun `a memory segment after the last turn leaves inside that turn's cut one slice`() {
        // I-3's real behaviour, asserted instead of the wished-for "memory is always reported under ②".
        //
        // With the production order, ①'s slice runs from the group's first segment to the next turn start — and to
        // `flow.size` when no later turn follows. A memory segment sits after the last group, so once ① has to evict
        // that group the memory leaves **inside the same slice** and is charged to `droppedTurns`; `droppedOrigins`
        // stays empty. That is what makes the declared cut ② a no-op for every flow that carries a turn group (see
        // `TrimOrder`): only a transcript-less flow (`[memory, USER_INPUT]`) ever reaches ②.
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "记".repeat(200))
        val oldUser = Segment(SegmentOrigin.TRANSCRIPT, true, "旧".repeat(300), SegmentRole.USER)
        val oldAssistant = Segment(SegmentOrigin.TRANSCRIPT, true, "答".repeat(300), SegmentRole.ASSISTANT)
        val keptUser = Segment(SegmentOrigin.TRANSCRIPT, true, "新".repeat(100), SegmentRole.USER)
        val keptAssistant = Segment(SegmentOrigin.TRANSCRIPT, true, "复".repeat(100), SegmentRole.ASSISTANT)
        val system = Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")
        val input = Segment(SegmentOrigin.USER_INPUT, true, "现在几点")
        val frame = RequestFrame(
            listOf(system),
            listOf(oldUser, oldAssistant, keptUser, keptAssistant, memory, input),
            emptyList(),
        )
        fun tokens(segment: Segment) = TokenMeter.estimate(segment.text) + 32
        val onlySystemAndInput = FIXED_OVERHEAD + tokens(system) + tokens(input)
        val client = terminalClient()
        val gateway = gateway(client)

        gateway.turn(turn(frame = frame, limitTokens = onlySystemAndInput))

        val counts = requireNotNull(gateway.lastTrim)
        assertEquals(2, counts.droppedTurns, "both groups go: the tighter window cannot fit the newer turn either")
        assertTrue(counts.droppedOrigins.isEmpty(), "the memory left with ①'s slice, so ② reports nothing")
        val sent = client.requests.single()
        assertTrue(sent.history.isEmpty(), "nothing but the current turn fits")
        assertEquals(listOf("现在几点"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
    }

    @Test fun `a trim that drops the current user transcript restores the utterance as the input`() {
        // 0.25 Task 27 (R143): the production continuation asks with the fixed cue because the loop's transcript
        // already carries the question. At a tight window, cut ① evicts that whole turn (it is the only transcript),
        // so without a safety net the cue would be the *only* thing the model reads. The utterance must ride the
        // request input again — and it cannot be duplicated, because no USER text survives in the history.
        val utterance = "把客厅的灯调暗一点"
        val cue = "Continue after the tool result."
        val toolRound = """[{"type":"call","callId":"c1","name":"notify_user","argsJson":"{}"},""" +
            """{"type":"output","callId":"c1","output":"ok"}]"""
        val flow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, true, utterance, SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "(tool call)", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.TOOL_ROUND, true, toolRound),
            Segment(SegmentOrigin.USER_INPUT, true, cue),
        )
        val system = Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")
        val frame = RequestFrame(listOf(system), flow, emptyList())
        fun measure(text: String) = TokenMeter.estimate(text) + 32
        // Exactly the frame without the current turn's transcript — 32 + 33(sys) + 40(cue) = **105** token units:
        // ① has to drop that one group and then stops.
        val limit = FIXED_OVERHEAD + measure(system.text) + measure(cue)
        val client = terminalClient()
        val gateway = gateway(client)

        gateway.turn(turn(frame = frame, limitTokens = limit, userUtterance = utterance))

        assertEquals(
            1,
            requireNotNull(gateway.lastTrim).droppedTurns,
            "cut ① must evict the current turn's USER transcript",
        )
        val sent = client.requests.single()
        assertTrue(sent.history.isEmpty(), "the evicted turn leaves no USER text: ${sent.history}")
        assertEquals(
            listOf(utterance),
            sent.input.filterIsInstance<InputPart.Text>().map { it.text },
            "the input must be the wearer's question, not the cue, once the question left the history",
        )
        assertFalse(
            sent.input.filterIsInstance<InputPart.Text>().any { it.text == cue },
            "the cue must not be the only input: ${sent.input}",
        )
    }

    @Test fun `restoring the utterance keeps the runtime state ahead of it`() {
        // The state line is request metadata, not a conversation turn: cut ① cannot evict it, and the restoration must
        // not drop it either. It rides ahead of the question exactly as the frame had arranged it.
        val utterance = "现在几点了"
        val cue = "Continue after the tool result."
        val state = "foreground=com.android.settings revision=4"
        val flow = listOf(
            Segment(SegmentOrigin.RUNTIME_STATE, false, state),
            Segment(SegmentOrigin.TRANSCRIPT, true, utterance, SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "(tool call)", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.USER_INPUT, true, cue),
        )
        val system = Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")
        val frame = RequestFrame(listOf(system), flow, emptyList())
        fun measure(text: String) = TokenMeter.estimate(text) + 32
        val limit = FIXED_OVERHEAD + measure(system.text) + measure(state) + measure(cue)
        val client = terminalClient()

        gateway(client).turn(turn(frame = frame, limitTokens = limit, userUtterance = utterance))

        val input = client.requests.single().input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.startsWith(state), "the runtime state must stay ahead of the restored question: $input")
        assertTrue(input.endsWith(utterance), "the question must be the restored input: $input")
        assertFalse(input.contains(cue), "the cue must be replaced by the question: $input")
    }

    @Test fun `a frame whose transcript opens on an assistant text is refused as NO_USER_TURN`() {
        // The frame path used to drop `split(history)`'s refusal silently. `RequestFrame` guarantees exactly one
        // `USER_INPUT`, but it does **not** guarantee that the transcript opens on a user turn; a history that opens
        // with an assistant text could never pass `ContextBudgeter`, so the gateway refuses it here instead of
        // handing an unsendable request to the transport.
        val client = terminalClient()
        val flow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, true, "没有用户回合的开头", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())

        val reply = gateway(client).turn(turn(frame = frame))

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("NO_USER_TURN", reply.safeCode)
        assertTrue(client.requests.isEmpty(), "an unsendable frame must be refused before any transport call")
    }

    @Test fun `a frame whose surviving history is a lone user group is refused as NO_USER_TURN`() {
        // `ContextBudgeter` rejects a history whose last group is a single user message, by design
        // (`ContextBudgeter.kt:53` — whole-group eviction depends on it). A frame reaches that shape after cut ① when
        // the only turn that survives is an unanswered user half: the older whole turn is evicted for the budget and
        // the pending user turn is left alone. The gateway must refuse it here rather than hand an unsendable request
        // to the transport — the 2026-10-14 failure mode (one bad shape ⇒ every later turn fails until a restart).
        val client = terminalClient()
        val pending = "没有被回答的一问"
        val input = "现在呢"
        val flow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, true, "旧".repeat(100), SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "答".repeat(100), SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.TRANSCRIPT, true, pending, SegmentRole.USER),
            Segment(SegmentOrigin.USER_INPUT, true, input),
        )
        val frame = RequestFrame(listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")), flow, emptyList())
        // The window fits the system slot, the pending user turn and the input — 32 + 33(sys) + 40(pending)
        // + 35(input) = **140** token units — and none of the older turn: cut ① takes exactly that whole group and
        // stops, so the surviving history is the lone user half.
        fun measure(text: String) = TokenMeter.estimate(text) + 32
        val limit = FIXED_OVERHEAD + measure("sys") + measure(pending) + measure(input)
        val gateway = gateway(client)

        val reply = gateway.turn(turn(frame = frame, limitTokens = limit))

        assertEquals(1, requireNotNull(gateway.lastTrim).droppedTurns, "cut ① took exactly the older whole turn")
        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("NO_USER_TURN", reply.safeCode)
        assertTrue(client.requests.isEmpty(), "an unsendable frame must be refused before any transport call")
    }

    @Test fun `the gateway exposes trim counts and never any text`() {
        // I-6: the probe-shaped seam must be counts only. `TrimReport` carries the kept `RequestFrame`, whose
        // segments hold text, so `lastTrim.toString()` used to be able to print a surviving body; the gateway now
        // exposes `TrimCounts`, which has no text to print at all.
        val client = terminalClient()
        val surviving = "本轮问句的正文"
        val frame = RequestFrame(
            listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "旧".repeat(3_000), SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "答".repeat(3_000), SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, surviving),
            ),
            emptyList(),
        )
        val gateway = gateway(client)

        gateway.turn(turn(frame = frame, limitTokens = 100))

        val counts: TrimCounts = requireNotNull(gateway.lastTrim)
        assertEquals(1, counts.droppedTurns)
        assertTrue(counts.droppedOrigins.isEmpty())
        assertFalse(counts.toString().contains(surviving), "the retained body must not be printable: $counts")
        assertFalse(counts.toString().contains("旧"), "no trimmed body either: $counts")
    }

    @Test fun `the trim window is the policy hard limit`() {
        // 0.3 final-fix wave: `ContextPolicy.hardLimit`/`hardMargin` are live at send time. The frame path trims to
        // `hardLimit = hardInputLimit - hardMargin` (a deliberate 10% reserve), not to the bare profile remainder
        // minus a second 32-unit margin. The fixture profile's hard input is
        // `minOf(60_000, 100_000 - 8_000 - 1_000)` = 60_000, the margin `floor(60_000 × 0.10)` = 6_000, so the
        // trimmer is handed **54_000**.
        assertEquals(54_000, ModelClientGateway.windowTokens(profile))
        assertEquals(ContextPolicy.from(profile).hardLimit, ModelClientGateway.windowTokens(profile))
    }

    @Test fun `the frame path sends one instructions message for the whole system slot`() {
        // `frameToTranscript` must take `instructions` from the frame's whole system slot, not map each system segment
        // to its own message: the trimmer measures that slot as ONE message, so n system segments mapped as n messages
        // would undercount by 32 token units per extra segment and the send-time check could reject the trimmed
        // request.
        val client = terminalClient()
        val system = listOf(
            Segment(SegmentOrigin.INSTRUCTIONS, true, "base instructions"),
            Segment(SegmentOrigin.SKILL, true, "active skill"),
            Segment(SegmentOrigin.MEMORY_POLICY, true, "memory policy"),
        )
        val frame = RequestFrame(system, listOf(Segment(SegmentOrigin.USER_INPUT, true, "在吗")), emptyList())

        gateway(client).turn(turn(frame = frame, limitTokens = 5_000))

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
