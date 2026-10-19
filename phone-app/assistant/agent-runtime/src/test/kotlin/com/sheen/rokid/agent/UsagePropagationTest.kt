package com.sheen.rokid.agent

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
import kotlin.test.assertNull

/**
 * Task 6 (spec §4.8 / §4.13): the token accounting the provider reports on a model turn's one terminal has to survive
 * both adapters — `ModelClientGateway` must not drop it, and `AgentLoop` must sum it across the run's model turns into
 * [AgentRun.usage]. Only counts travel; no reply text is ever placed on this path.
 */
class UsagePropagationTest {

    // ------------------------------------------------------------ agent loop: accumulation

    private fun request() = AgentRequest(
        turnId = "turn-usage",
        utterance = "把音量调小一点",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = Long.MAX_VALUE,
    )

    private fun loop(gateway: ModelGateway) = AgentLoop(
        gateway,
        ToolRegistry(emptyList()),
        ScriptedPolicy(),
        ProgressGuard(),
        RecordingAuditSink(),
        FakeClock(),
    )

    @Test fun `usage from every model turn is summed into the run`() {
        // The empty first reply is retried by the loop, so this run really makes two model turns.
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "").copy(usage = ModelUsage(10, 5)),
                reply(text = "answer").copy(usage = ModelUsage(20, 7)),
            ),
        )

        val run = loop(gateway).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, gateway.requests.size, "the run must make two model turns for the sum to mean anything")
        assertEquals(ModelUsage(30, 12), run.usage)
    }

    @Test fun `a run whose turns report no usage exposes null`() {
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))

        val run = loop(gateway).run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertNull(run.usage, "no provider accounting must stay absent, not become a zero count")
    }

    @Test fun `usage components are summed independently when a turn reports only one`() {
        // `ModelUsage` admits a null component (a decoder that saw only one counter). A turn with no input count must
        // not erase the input total the other turn reported, and vice versa.
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "").copy(usage = ModelUsage(10, null)),
                reply(text = "answer").copy(usage = ModelUsage(null, 7)),
            ),
        )

        val run = loop(gateway).run(request())

        assertEquals(ModelUsage(10, 7), run.usage)
    }

    @Test fun `a run that ends on a failed turn still reports the usage that was delivered`() {
        // Usage is part of the turn's accounting, not of its verdict: every finish path carries what the provider
        // billed, including a run that ends early.
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "cut off", terminal = ModelEnd.FAILED, safeCode = "PROVIDER_FAILED")
                    .copy(usage = ModelUsage(3, 1)),
            ),
        )

        val run = loop(gateway).run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals(ModelUsage(3, 1), run.usage)
    }

    // ------------------------------------------------------------ gateway: pass-through

    private val profile = gatewayProfile()
    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)

    private fun gateway(client: ModelClient, stop: () -> Boolean = { false }) = ModelClientGateway(
        client = client,
        profileLookup = { profile },
        clock = clock,
        scheduler = scheduler,
        envelopeFor = { requestId, start, end ->
            EventEnvelope(SessionId("session"), Generation(1), requestId, start, end)
        },
        stop = stop,
    )

    private fun turn() = GatewayTurn(
        turnId = "turn-7",
        profileId = profile.id,
        instructions = "You are Edith.",
        history = listOf(ModelHistoryItem.Text(MessageRole.USER, "把音量调小一点")),
        tools = emptyList(),
        deadlineNanos = 1_000_000_000L,
    )

    @Test fun `the gateway carries the terminal usage into the reply`() {
        val client = FakeModelClient { request, listener ->
            listener(
                ModelStreamEvent.Terminal(
                    request.requestId,
                    ModelEnd.COMPLETED,
                    ModelUsage(11, 7),
                    null,
                ),
            )
        }

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(ModelUsage(11, 7), reply.usage)
    }

    @Test fun `a cancelled turn reports the usage its terminal already carried`() {
        // `cancel()` is a separate reply path; when the terminal (and its accounting) arrived before the stop was
        // observed, the cancelled reply must still hand the counts up.
        val client = FakeModelClient { request, listener ->
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, ModelUsage(4, 2), null))
        }

        val reply = gateway(client, stop = { true }).turn(turn())

        assertEquals(ModelEnd.CANCELLED, reply.terminal)
        assertEquals(ModelUsage(4, 2), reply.usage)
    }

    @Test fun `a relabelled incomplete turn keeps its usage`() {
        // A half-streamed call re-labels the verdict to INCOMPLETE. Re-labelling is not a reassessment of the token
        // accounting: the provider's usage stays on the reply.
        val client = FakeModelClient { request, listener ->
            listener(ModelStreamEvent.ToolCallDelta(request.requestId, "item-1", "lookup", "{}"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, ModelUsage(9, 3), null))
        }

        val reply = gateway(client).turn(turn())

        assertEquals(ModelEnd.INCOMPLETE, reply.terminal)
        assertEquals(ModelUsage(9, 3), reply.usage)
    }
}
