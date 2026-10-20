package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.3 Phase 2 Task 11 (spec §5.3): `P3_CACHE` is a **per model request** line owned by [ModelClientGateway].
 *
 * Owning it in the gateway is what makes the line cover every request: the wake run's tool rounds, the gateway's
 * deterministic overflow re-sends and the proactive `gateway.turn` path all pass through [ModelClientGateway.turn].
 * `AgentLoop` no longer emits a run-total line (that duplicate is pinned in `CacheMetricTest`).
 *
 * The line is asserted on its exact field list and order because later phases read it. Three states are pinned
 * separately: no accounting at all omits the line, an unreported counter is `-` (never a fabricated zero), and a
 * reported zero is `0`. The sizes are those of the frame **actually sent**: the system text length and the sum of the
 * dynamic-tail text lengths ([RequestLayout.dynamicTail]) of the kept frame, so an overflow recovery's HARD cut cannot
 * report the untrimmed tail it shortened (Task 12); the legacy no-frame path reports its instructions length and 0.
 */
class GatewayCacheMetricTest {

    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
    private val profile = gatewayProfile()
    private val envelopeFor: (String, Long, Long) -> EventEnvelope = { requestId, start, end ->
        EventEnvelope(SessionId("session"), Generation(1), requestId, start, end)
    }

    /** A gateway whose count-only sink appends to [lines]; the constructor's last parameter is the seam under test. */
    private fun gateway(client: ModelClient, lines: MutableList<String>) = ModelClientGateway(
        client = client,
        profileLookup = { profile },
        clock = clock,
        scheduler = scheduler,
        envelopeFor = envelopeFor,
        metricLine = { lines += it },
    )

    private fun frame(system: String, vararg flow: Segment) = RequestFrame(
        listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, system)),
        flow.toList(),
        emptyList(),
    )

    private fun turn(frame: RequestFrame?, instructions: String = "You are Edith.") = GatewayTurn(
        turnId = "turn-cache",
        profileId = profile.id,
        instructions = instructions,
        history = listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点")),
        tools = emptyList(),
        deadlineNanos = 1_000_000_000L,
        frame = frame,
    )

    /** A synchronous terminal carrying [usage]; the gateway needs one accepted terminal to settle the turn. */
    private fun terminalClient(usage: ModelUsage?) = FakeModelClient { request, listener ->
        listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, usage, null))
    }

    @Test
    fun `a frame request reports its cache counts and the frame's system and dynamic sizes`() {
        val lines = mutableListOf<String>()
        val frame = frame(
            system = "sys",
            Segment(SegmentOrigin.TRANSCRIPT, true, "question", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "answer", SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.PERCEPTION, false, "perception-block"),
            Segment(SegmentOrigin.USER_INPUT, true, "now"),
        )

        gateway(
            terminalClient(ModelUsage(100, 20, cachedInputTokens = 64, cacheMissInputTokens = 36)),
            lines,
        ).turn(turn(frame))

        assertEquals(
            listOf("P3_CACHE cached=64 miss=36 inputTokens=100 outputTokens=20 systemChars=3 dynamicChars=16"),
            lines,
        )
        // Counts and sizes only: no tail text or user text may ride the line (`systemChars` is a field name, so the
        // system slot is asserted by its count, never by a substring check on the word "sys").
        assertFalse(lines.single().contains("perception-block"), lines.single())
        assertFalse(lines.single().contains("question"), lines.single())
        assertFalse(lines.single().contains("now"), lines.single())
    }

    @Test
    fun `a proactive-style gateway turn outside AgentLoop is reported too`() {
        // The proactive route has no AgentLoop: it assembles a frame and calls the gateway directly. The line must be
        // owned by the gateway, or this path would have no cache reading at all.
        val lines = mutableListOf<String>()
        val frame = frame(
            system = "proactive-system",
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory"),
            Segment(SegmentOrigin.SKILL_BODY, true, "skill-body"),
            Segment(SegmentOrigin.USER_INPUT, true, "现在几点了"),
        )

        val reply = gateway(
            terminalClient(ModelUsage(30, 8, cachedInputTokens = 20, cacheMissInputTokens = 10)),
            lines,
        ).turn(turn(frame, instructions = "proactive-system"))

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(
            listOf(
                "P3_CACHE cached=20 miss=10 inputTokens=30 outputTokens=8 " +
                    "systemChars=16 dynamicChars=16",
            ),
            lines,
        )
    }

    @Test
    fun `the legacy no-frame path reports the instructions length and zero dynamic chars`() {
        val lines = mutableListOf<String>()

        gateway(terminalClient(ModelUsage(10, 5)), lines).turn(turn(frame = null))

        assertEquals(
            listOf("P3_CACHE cached=- miss=- inputTokens=10 outputTokens=5 systemChars=14 dynamicChars=0"),
            lines,
        )
    }

    @Test
    fun `a reply with no provider accounting emits no line`() {
        val lines = mutableListOf<String>()
        val frame = frame(system = "sys", Segment(SegmentOrigin.USER_INPUT, true, "now"))

        gateway(terminalClient(null), lines).turn(turn(frame))

        assertTrue(lines.isEmpty(), "no usage is not a zero measurement: $lines")
    }

    @Test
    fun `an unreported cache counter prints a dash while a reported zero prints zero`() {
        val dashes = mutableListOf<String>()
        gateway(terminalClient(ModelUsage(100, 20)), dashes)
            .turn(turn(frame(system = "sys", Segment(SegmentOrigin.USER_INPUT, true, "now"))))
        assertEquals(
            listOf("P3_CACHE cached=- miss=- inputTokens=100 outputTokens=20 systemChars=3 dynamicChars=0"),
            dashes,
        )

        val zeros = mutableListOf<String>()
        gateway(terminalClient(ModelUsage(0, 0, cachedInputTokens = 0, cacheMissInputTokens = 0)), zeros)
            .turn(turn(frame(system = "sys", Segment(SegmentOrigin.USER_INPUT, true, "now"))))
        assertEquals(
            listOf("P3_CACHE cached=0 miss=0 inputTokens=0 outputTokens=0 systemChars=3 dynamicChars=0"),
            zeros,
        )
    }

    @Test
    fun `each model request reports its own counts and nothing is summed`() {
        val lines = mutableListOf<String>()
        var calls = 0
        val client = FakeModelClient { request, listener ->
            calls++
            val usage = if (calls == 1) ModelUsage(100, 20, 64, 36) else ModelUsage(10, 5, 6, 4)
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, usage, null))
        }
        val frame = frame(system = "sys", Segment(SegmentOrigin.USER_INPUT, true, "now"))
        val gateway = gateway(client, lines)

        gateway.turn(turn(frame))
        gateway.turn(turn(frame))

        assertEquals(
            listOf(
                "P3_CACHE cached=64 miss=36 inputTokens=100 outputTokens=20 systemChars=3 dynamicChars=0",
                "P3_CACHE cached=6 miss=4 inputTokens=10 outputTokens=5 systemChars=3 dynamicChars=0",
            ),
            lines,
        )
    }

    @Test
    fun `an overflow re-send is its own model request with its own line`() {
        // R70's recovery sends the same turn again with a cheaper shape. Both sends are real model requests, so both
        // report; the first reply's accounting must not be folded into the second.
        val lines = mutableListOf<String>()
        var calls = 0
        val client = FakeModelClient { request, listener ->
            calls++
            if (calls == 1) {
                listener(
                    ModelStreamEvent.Terminal(
                        request.requestId,
                        ModelEnd.FAILED,
                        ModelUsage(100, 20, 64, 36),
                        CONTEXT_OVERFLOW,
                    ),
                )
            } else {
                listener(
                    ModelStreamEvent.Terminal(
                        request.requestId,
                        ModelEnd.COMPLETED,
                        ModelUsage(10, 5, 6, 4),
                        null,
                    ),
                )
            }
        }
        val frame = frame(system = "sys", Segment(SegmentOrigin.USER_INPUT, true, "now"))

        val reply = gateway(client, lines).turn(turn(frame))

        assertEquals(1, reply.overflowRecoveries)
        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(ModelUsage(10, 5, 6, 4), reply.usage)
        assertEquals(
            listOf(
                "P3_CACHE cached=64 miss=36 inputTokens=100 outputTokens=20 systemChars=3 dynamicChars=0",
                "P3_CACHE cached=6 miss=4 inputTokens=10 outputTokens=5 systemChars=3 dynamicChars=0",
            ),
            lines,
        )
    }

    @Test
    fun `a hard recovery reports the kept frame's sizes, not the untrimmed assembly`() {
        // R70 recovery ② (HARD) shortens request-scoped tail segments (Task 12: head + marker + tail, never a silent
        // drop). `P3_CACHE` describes the model request that was actually sent, so the sizes come from the kept frame;
        // measuring the assembled `request.frame` would claim the full tail that never went on the wire.
        val lines = mutableListOf<String>()
        var calls = 0
        val client = FakeModelClient { request, listener ->
            calls++
            if (calls <= 2) throw ContractError.ContextWindowExceeded("does not fit")
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, ModelUsage(10, 5), null))
        }
        val frame = frame(
            system = "sys",
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory-evidence".repeat(100)),
            Segment(SegmentOrigin.PERCEPTION, false, "perception-block".repeat(100)),
            Segment(SegmentOrigin.USER_INPUT, true, "now"),
        )

        val reply = gateway(client, lines).turn(turn(frame))

        assertEquals(2, reply.overflowRecoveries)
        assertEquals(
            listOf("P3_CACHE cached=- miss=- inputTokens=10 outputTokens=5 systemChars=3 dynamicChars=1038"),
            lines,
            "the sent request reports the shortened tail (2 × 519 chars), not the assembled 3,100-char one",
        )
    }

    @Test
    fun `a throwing metric sink cannot turn a completed turn into a failure`() {
        val client = terminalClient(ModelUsage(1, 2))
        val gateway = ModelClientGateway(
            client = client,
            profileLookup = { profile },
            clock = clock,
            scheduler = scheduler,
            envelopeFor = envelopeFor,
            metricLine = { error("logger exploded") },
        )

        val reply = gateway.turn(turn(frame(system = "sys", Segment(SegmentOrigin.USER_INPUT, true, "now"))))

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals("", reply.text)
    }
}
