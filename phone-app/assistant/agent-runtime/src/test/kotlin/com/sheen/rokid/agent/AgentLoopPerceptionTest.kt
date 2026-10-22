package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.3 Phase 2 Task 9: the `PERCEPTION` request-tail seam.
 *
 * The block is request-scoped context, not a conversation turn: it is folded into the request input immediately
 * before `USER_INPUT` (never into the system slot or the cacheable history), it is untrusted, and it is never written
 * to the run's transcript. A block identical to the previous request's is skipped, because a steady screen must not
 * pay the same bytes on every tool round.
 */
class AgentLoopPerceptionTest {

    private fun minimalFrame(userInput: String): (List<ModelHistoryItem>) -> RequestFrame = {
        RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "sys")),
            conversationFlow = listOf(Segment(SegmentOrigin.USER_INPUT, trusted = true, text = userInput)),
            tools = emptyList(),
        )
    }

    private fun request(
        perception: (() -> String?)? = null,
        runtimeState: (() -> TurnRuntimeState?)? = null,
        frameFor: ((List<ModelHistoryItem>) -> RequestFrame)? = minimalFrame("打开设置"),
        onRequestBuilt: ((RequestFrame) -> Unit)? = null,
    ) = AgentRequest(
        turnId = "turn-perception",
        utterance = "打开设置",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = Long.MAX_VALUE,
        frameFor = frameFor,
        runtimeState = runtimeState,
        perception = perception,
        onRequestBuilt = onRequestBuilt,
    )

    private fun loop(
        gateway: ModelGateway,
        registry: ToolRegistry = ToolRegistry(emptyList()),
        clock: MonotonicClock = FakeClock(),
    ) = AgentLoop(
        gateway,
        registry,
        ScriptedPolicy(),
        ProgressGuard(),
        RecordingAuditSink(),
        clock,
    )

    private fun perceptionOf(turn: GatewayTurn): Segment? =
        turn.frame?.conversationFlow?.singleOrNull { it.origin == SegmentOrigin.PERCEPTION }

    @Test
    fun `the block is the last segment before the user input and is untrusted`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        loop(gateway).run(request(perception = { "感知：前台=com.android.settings" }))

        val flow = gateway.requests.single().frame!!.conversationFlow
        assertEquals(SegmentOrigin.PERCEPTION, flow[flow.size - 2].origin)
        assertEquals(SegmentOrigin.USER_INPUT, flow.last().origin)
        assertFalse(flow[flow.size - 2].trusted, "perception is external, untrusted data")
        assertEquals("感知：前台=com.android.settings", flow[flow.size - 2].text)
    }

    @Test
    fun `the block never enters the transcript`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        val run = loop(gateway).run(request(perception = { "感知：前台=com.android.settings" }))

        assertTrue(
            run.transcript.none { it is ModelHistoryItem.Text && it.text.contains("com.android.settings") },
            "the perception block must not be persisted: ${run.transcript}",
        )
        assertTrue(
            gateway.requests.single().history.none {
                it is ModelHistoryItem.Text && it.text.contains("com.android.settings")
            },
            "the perception block must not ride the cacheable history",
        )
    }

    @Test
    fun `an identical block is injected only once`() {
        // An empty turn is retried: two model requests with no tool round between them.
        val gateway = ScriptedGateway(listOf(reply(), reply(text = "好。")))

        val run = loop(gateway).run(request(perception = { "感知：前台=com.android.settings" }))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, gateway.requests.size)
        assertEquals(1, gateway.requests[0].frame!!.conversationFlow.count { it.origin == SegmentOrigin.PERCEPTION })
        assertEquals(
            0,
            gateway.requests[1].frame!!.conversationFlow.count { it.origin == SegmentOrigin.PERCEPTION },
            "an unchanged perception block must save the tokens",
        )
    }

    @Test
    fun `a changed block is injected again`() {
        val gateway = ScriptedGateway(listOf(reply(), reply(text = "好。")))
        var blocks = 0

        loop(gateway).run(request(perception = { "感知：第${blocks++}次" }))

        assertEquals("感知：第0次", perceptionOf(gateway.requests[0])!!.text)
        assertEquals("感知：第1次", perceptionOf(gateway.requests[1])!!.text)
    }

    @Test
    fun `a null block injects nothing`() {
        val gateway = ScriptedGateway(listOf(reply(), reply(text = "好。")))

        loop(gateway).run(request(perception = { null }))

        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.requests.all { perceptionOf(it) == null })
    }

    @Test
    fun `a blank block injects nothing`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        loop(gateway).run(request(perception = { "   \n  " }))

        assertTrue(perceptionOf(gateway.requests.single()) == null)
    }

    @Test
    fun `the runtime state stays present and precedes the perception block`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        loop(gateway).run(
            request(
                perception = { "感知：前台=com.android.settings" },
                runtimeState = { TurnRuntimeState("steps=0 observations=0") },
            ),
        )

        val flow = gateway.requests.single().frame!!.conversationFlow
        val runtimeIndex = flow.indexOfLast { it.origin == SegmentOrigin.RUNTIME_STATE }
        val perceptionIndex = flow.indexOfLast { it.origin == SegmentOrigin.PERCEPTION }
        assertTrue(runtimeIndex >= 0, "the loop's runtime state must still be injected: $flow")
        assertTrue(perceptionIndex == flow.size - 2, "perception must sit immediately before USER_INPUT: $flow")
        assertTrue(runtimeIndex < perceptionIndex, "runtime state keeps its earlier tail position: $flow")
    }

    @Test
    fun `the provider is asked once per model request`() {
        val gateway = ScriptedGateway(listOf(reply(), reply(text = "好。")))
        var calls = 0

        loop(gateway).run(
            request(
                perception = {
                    calls++
                    "感知：第${calls}次"
                },
            ),
        )

        assertEquals(2, gateway.requests.size)
        assertEquals(2, calls, "the block is rebuilt for every model request, dedupe happens after the read")
    }

    @Test
    fun `a blank provider output never breaks the turn`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        val run = loop(gateway).run(request(perception = { "" }))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("好。", run.finalText)
    }

    @Test
    fun `a frame without a user input cannot exist so the no-insertion guard is unreachable`() {
        // Review minor: the dedupe hash used to be assigned before the `inputIndex < 0` guard. Moving it after the
        // guard is still correct defensively, but no behavioural test can reach that branch: every RequestFrame must
        // carry exactly one USER_INPUT and end on it, so `indexOfLast` is always >= 0.
        val failure = assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "sys")),
                conversationFlow = emptyList(),
                tools = emptyList(),
            )
        }
        assertTrue("USER_INPUT" in failure.message.orEmpty(), "the invariant is named: ${failure.message}")
    }

    @Test
    fun `the perception segment is absent when the run has no frame`() {
        // The legacy history path has no RequestFrame: there is no tail to ride, and the loop must not invent one.
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        val run = loop(gateway).run(request(perception = { "感知：前台=com.android.settings" }, frameFor = null))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(0, gateway.requests.single().frame?.conversationFlow?.count {
            it.origin == SegmentOrigin.PERCEPTION
        } ?: 0)
    }

    // ---------------------------------------------------------------- the assembled-request hook (Task 11 R12)

    @Test
    fun `the request hook sees the frame after the loop folded perception in`() {
        // The hook is the fix's whole point: the plugin's builder runs before the loop adds its request-scoped
        // segments, so only this seam can describe the tail the model really received.
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))
        val frames = mutableListOf<RequestFrame>()

        loop(gateway).run(
            request(
                perception = { "感知：前台=com.android.settings" },
                onRequestBuilt = { frames += it },
            ),
        )

        val sent = gateway.requests.single().frame!!
        assertEquals(listOf(sent), frames, "one hook call per assembled request, carrying the frame that was sent")
        assertEquals(
            1,
            frames.single().conversationFlow.count { it.origin == SegmentOrigin.PERCEPTION },
            "the hook must see the folded perception segment, not the builder's pre-fold frame: ${frames.single()}",
        )
    }

    @Test
    fun `the request hook runs once per request of a retry`() {
        // A retry is a second assembled request, and its frame is rebuilt from the transcript the retry continues.
        // An unchanged perception block is deduplicated, exactly as the sent frame shows.
        val gateway = ScriptedGateway(listOf(reply(), reply(text = "好。")))
        val frames = mutableListOf<RequestFrame>()

        loop(gateway).run(
            request(
                perception = { "感知：前台=com.android.settings" },
                onRequestBuilt = { frames += it },
            ),
        )

        assertEquals(2, gateway.requests.size)
        assertEquals(2, frames.size, "one hook call per assembled request: $frames")
        assertEquals(1, frames[0].conversationFlow.count { it.origin == SegmentOrigin.PERCEPTION })
        assertEquals(0, frames[1].conversationFlow.count { it.origin == SegmentOrigin.PERCEPTION })
    }

    @Test
    fun `the request hook is not called when the run assembles no frame`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))
        var calls = 0

        loop(gateway).run(request(frameFor = null, onRequestBuilt = { calls++ }))

        assertEquals(0, calls, "the legacy history path has no frame to describe")
    }

    @Test
    fun `a throwing request hook cannot fail the run`() {
        // Observability is never a verdict: a logger that throws must not turn a delivered answer into a failure.
        val gateway = ScriptedGateway(listOf(reply(text = "好。")))

        val run = loop(gateway).run(request(onRequestBuilt = { error("logger exploded") }))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals("好。", run.finalText)
    }
}
