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

    private fun gateway(client: edith.plugin.ModelClient, stop: () -> Boolean = { false }) =
        ModelClientGateway(client, { lookup(it) }, clock, scheduler, envelopeFor, stop)

    private fun turn(
        turnId: String = "turn-7",
        profileId: String = profile.id,
        instructions: String = "You are Edith.",
        history: List<ModelHistoryItem> = listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点")),
        tools: List<ToolSpec> = emptyList(),
        deadlineNanos: Long = 1_000_000_000L,
    ) = GatewayTurn(turnId, profileId, instructions, history, tools, deadlineNanos)

    private fun scripted(vararg events: ModelStreamEvent) =
        FakeModelClient { _, listener -> events.forEach(listener) }

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
        assertTrue(request.context.isEmpty(), "the loop folds context into instructions; the request carries none")
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
}
