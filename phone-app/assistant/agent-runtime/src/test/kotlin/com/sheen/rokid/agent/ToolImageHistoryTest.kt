package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 0.25 Task 24 (C1/R133): a tool's image reference must survive the production loop boundary.
 *
 * `DeviceTool.screenshot` answers an `ImageRef`, `ToolRegistry` projects the `ActionResult`, and this loop must put it
 * on the `ModelHistoryItem.ToolOutput` of the tool round it feeds back. Without that item the gateway and the plugin's
 * `TOOL_ROUND` segment have nothing to carry, which is the final review's Critical C1.
 *
 * Task 8 fix review: the image lives **in this run's model request only**. The stored transcript is perception content
 * by the same C2 rule as every observation, so the artifact reference is redacted at `finish` - a successful image has no
 * persistent store path. The in-run continuation still carries it (the assertion below is on the gateway request).
 */
class ToolImageHistoryTest {

    private val image = ImageRef("img-shot-1", "image/jpeg", 768, 1664, "sha-256-shot-1")

    private fun request() = AgentRequest(
        turnId = "turn-24",
        utterance = "看一下屏幕",
        instructions = "You are Edith.",
        profileId = "p",
        deadlineNanos = Long.MAX_VALUE,
    )

    private fun loop(gateway: ModelGateway, executor: RecordingActionExecutor) = AgentLoop(
        gateway = gateway,
        registry = ToolRegistry(listOf(executor)),
        policy = ScriptedPolicy(),
        guard = ProgressGuard(),
        audit = RecordingAuditSink(),
        clock = FakeClock(),
    )

    @Test
    fun `a tool result image reaches the next gateway turn but never the stored transcript`() {
        val executor = executorOf("device")
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "captured", images = listOf(image))
        }
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "capturing", calls = listOf(call("c1", "device", """{"action":"screenshot"}"""))),
                reply(text = "done"),
            ),
        )

        val run = loop(gateway, executor).run(request())

        val continuation = gateway.requests.single { turn -> turn.history.any { it is ModelHistoryItem.ToolOutput } }
        val sent = assertIs<ModelHistoryItem.ToolOutput>(continuation.history.last())
        assertEquals(listOf(image), sent.images, "the next request's history must carry the tool image: $sent")
        assertEquals(1, run.metrics.images, "P3_METRIC.images counts the artifact this run's tool call produced (R78)")

        // Task 8 fix review: the stored transcript is not a second image carrier. The model saw the picture and the
        // call remains attributable by id; the artifact reference itself does not outlive the run.
        val stored = run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals("c1", stored.callId)
        assertEquals(emptyList(), stored.images, "an image may never be stored, whatever the outcome: ${stored.output}")
    }

    @Test
    fun `a tool result without images keeps the empty pre 0-25 shape`() {
        val executor = executorOf("device")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "captured") }
        val gateway = ScriptedGateway(
            listOf(
                reply(text = "capturing", calls = listOf(call("c1", "device", """{"action":"screenshot"}"""))),
                reply(text = "done"),
            ),
        )

        val run = loop(gateway, executor).run(request())

        assertEquals(
            emptyList(),
            run.transcript.filterIsInstance<ModelHistoryItem.ToolOutput>().single().images,
            "a tool that produced no image must not invent one",
        )
    }
}
