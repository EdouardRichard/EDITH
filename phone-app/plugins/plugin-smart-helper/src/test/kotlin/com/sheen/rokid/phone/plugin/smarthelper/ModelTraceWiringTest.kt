package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The once-per-run wiring of the debug trace: every finished run hands exactly one line to `traceLine`, and that line is
 * the run's own transcript rather than a reconstruction.
 *
 * Both agent paths are covered because they are two call sites: the shared turn engine (`runTurnBody`, which serves wake,
 * typed and follow-up turns) and the proactive hint run, which has its own `AgentLoop`.
 */
class ModelTraceWiringTest {

    private fun stores(): Map<ContextRoute, ConversationStore> =
        ContextRoute.entries.associateWith { ConversationStore() }

    /** A wide window so the fixture's history shape, never its size, is what these assertions see. */
    private fun wide(rig: SmartTestRig) {
        rig.profile = smartProfile(
            usableWindowTokens = 32_000,
            inputBudgetTokens = 16_000,
            maxOutputTokens = 2_000,
            safetyTokens = 1_000,
        )
    }

    private fun awaitLines(lines: MutableList<String>, expected: Int, millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (lines.size < expected && System.nanoTime() < deadline) Thread.sleep(5)
    }

    @Test
    fun `one finished turn emits exactly one trace line carrying its transcript and the observation it saw`() {
        val traces = mutableListOf<String>()
        val rig = SmartTestRig(conversationStores = stores(), traceLine = { traces += it })
        wide(rig)
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        rig.actions.descriptors += smartAction("device", screenObservation = true)
        rig.actions.outcome = { _, _ ->
            ActionResult(
                ActionOutcome.Done,
                "ok",
                observation = ToolObservation(screen = "[#1] 设置 按钮", vision = ToolVision.Ok),
            )
        }
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests++
            if (requests == 1) {
                listener(smartToolCall("c1", "device", """{"action":"observe"}""", requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "设置页已打开。")
            }
        }

        rig.wakeAndFinal("看一下屏幕")

        assertEquals(1, traces.size, "one finished run must emit exactly one line: $traces")
        val trace = Json.parseToJsonElement(traces.single()).jsonObject
        assertEquals("asr-1", trace["turnId"]!!.jsonPrimitive.content)
        assertEquals("WAKE", trace["route"]!!.jsonPrimitive.content)
        assertEquals("COMPLETED", trace["outcome"]!!.jsonPrimitive.content)
        assertEquals("设置页已打开。", trace["finalText"]!!.jsonPrimitive.content)
        assertEquals(
            "device",
            trace["steps"]!!.jsonArray.single().jsonObject["action"]!!.jsonPrimitive.content,
        )

        val sent = trace["requestToolRounds"]!!.jsonArray.map { it.jsonObject }
        val call = sent.single { it["type"]!!.jsonPrimitive.content == "toolCall" }
        assertEquals("observe", call["args"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        val output = sent.single { it["type"]!!.jsonPrimitive.content == "toolOutput" }
        assertEquals(
            "[#1] 设置 按钮",
            output["payload"]!!.jsonObject["observation"]!!.jsonObject["screen"]!!.jsonPrimitive.content,
            "the trace must carry the observation the model read, not the stored-transcript redaction marker",
        )
    }

    @Test
    fun `a proactive hint run emits its own trace line`() {
        val traces = mutableListOf<String>()
        val rig = SmartTestRig(traceLine = { traces += it })
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"提醒一句"}""", requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        awaitLines(traces, 1)

        assertEquals(1, traces.size, "the proactive hint run is an agent run too: $traces")
        val trace = Json.parseToJsonElement(traces.single()).jsonObject
        assertEquals("PROACTIVE", trace["route"]!!.jsonPrimitive.content)
        assertEquals("COMPLETED", trace["outcome"]!!.jsonPrimitive.content)
        assertEquals(
            "propose_hint",
            trace["steps"]!!.jsonArray.single().jsonObject["action"]!!.jsonPrimitive.content,
        )
    }
}
