package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §4.2 / §7.3 B7: a run continues the session's transcript instead of starting from the turn's utterance, and
 * hands the transcript back so the caller can keep it.
 *
 * Both additions are **additive**: the defaults reproduce the previous behaviour exactly, which is what lets the
 * existing callers and their tests keep meaning what they meant.
 */
class AgentLoopHistoryTest {

    private fun newLoop(gateway: ModelGateway) = AgentLoop(
        gateway = gateway,
        registry = ToolRegistry(emptyList()),
        policy = ScriptedPolicy(),
        guard = ProgressGuard(),
        audit = RecordingAuditSink(),
        clock = FakeClock(),
    )

    private fun request(history: List<ModelHistoryItem> = emptyList()) = AgentRequest(
        turnId = "turn-1",
        utterance = "第二句",
        instructions = "You are Edith.",
        profileId = "p",
        deadlineNanos = Long.MAX_VALUE,
        history = history,
    )

    @Test
    fun `a request that supplies no history behaves exactly as before`() {
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))

        newLoop(gateway).run(request())

        val sent = gateway.requests.single().history
        assertEquals(1, sent.size, "the utterance still opens the history on its own")
        assertEquals("第二句", (sent.single() as ModelHistoryItem.Text).text)
    }

    @Test
    fun `the history field defaults to empty so an existing caller is unaffected`() {
        assertEquals(emptyList(), request().history)
    }

    @Test
    fun `a supplied history precedes this turn's utterance`() {
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))
        val prior = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "第一句"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一句的回答"),
        )

        newLoop(gateway).run(request(prior))

        val sent = gateway.requests.single().history
        assertEquals(3, sent.size)
        assertEquals("第一句", (sent[0] as ModelHistoryItem.Text).text)
        assertEquals("第一句的回答", (sent[1] as ModelHistoryItem.Text).text)
        assertEquals("第二句", (sent[2] as ModelHistoryItem.Text).text)
    }

    @Test
    fun `the returned transcript carries the whole conversation including the new turn`() {
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))

        val run = newLoop(gateway).run(request(listOf(ModelHistoryItem.Text(MessageRole.USER, "第一句"))))

        assertEquals(3, run.transcript.size)
        assertEquals("第一句", (run.transcript[0] as ModelHistoryItem.Text).text)
        assertEquals("第二句", (run.transcript[1] as ModelHistoryItem.Text).text)
        assertEquals("answer", (run.transcript[2] as ModelHistoryItem.Text).text)
    }

    @Test
    fun `the returned transcript never ends on a pending user turn`() {
        // `ContextBudgeter` rejects a transcript that ends on a lone text item, and the caller stores whatever it
        // gets back - so the loop must not hand out that shape.
        val gateway = ScriptedGateway(List(3) { reply(text = "", terminal = ModelEnd.INCOMPLETE) })

        val run = newLoop(gateway).run(request())

        assertTrue(run.transcript.isNotEmpty())
        val last = run.transcript.last()
        assertTrue(
            last !is ModelHistoryItem.Text || last.role != MessageRole.USER,
            "the transcript must be closed before it is handed over, was $last",
        )
    }

    @Test
    fun `a supplied history is not truncated by the loop`() {
        // Trimming is the host `ContextBudgeter`'s job; a loop that quietly dropped the caller's history would make
        // the session's length depend on how many turns it happened to take.
        val gateway = ScriptedGateway(listOf(reply(text = "answer")))
        val prior = (1..60).map { ModelHistoryItem.Text(MessageRole.USER, "message $it") }

        val run = newLoop(gateway).run(request(prior))

        assertEquals(61, gateway.requests.single().history.size)
        assertEquals(62, run.transcript.size)
    }

    @Test
    fun `a tool round keeps the prior history in front of everything it appends`() {
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "working", calls = listOf(call("c1", "compose_note"))),
                reply(text = "done"),
            ),
        )
        val registry = ToolRegistry(listOf(executorOf("compose_note")))

        val run = AgentLoop(
            gateway = gateway,
            registry = registry,
            policy = ScriptedPolicy(),
            guard = ProgressGuard(),
            audit = RecordingAuditSink(),
            clock = FakeClock(),
        ).run(request(listOf(ModelHistoryItem.Text(MessageRole.USER, "第一句"))))

        assertEquals("第一句", (run.transcript.first() as ModelHistoryItem.Text).text)
        assertTrue(run.transcript.any { it is ModelHistoryItem.ToolCall })
        assertTrue(run.transcript.any { it is ModelHistoryItem.ToolOutput })
    }
}
