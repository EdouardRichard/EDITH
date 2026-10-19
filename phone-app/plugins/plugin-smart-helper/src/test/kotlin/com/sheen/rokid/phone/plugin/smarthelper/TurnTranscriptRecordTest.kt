package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 Task 4: the production plugin records a whole tool round, and the frame renderer is the one channel that carries
 * a tool output's image references from the store to the model request.
 *
 * Before this task `recordTurnAndCompress` wrote only `appendUser` / `appendAssistant`, so every tool call, observation
 * and image the turn produced was lost across turns. The tests here assert the production wiring end to end: the store
 * receives the run's transcript (not a rebuilt two-text approximation), the next request carries the round, and an
 * image reference survives `ConversationStore` → `toSegment()` → `ModelClientGateway` unchanged.
 */
class TurnTranscriptRecordTest {

    private val reference = ImageRef(
        artifactId = "img-20261019-0001",
        mime = "image/jpeg",
        width = 768,
        height = 1664,
        sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    )

    private fun stores(): Map<ContextRoute, ConversationStore> =
        ContextRoute.entries.associateWith { ConversationStore() }

    private fun rig(stores: Map<ContextRoute, ConversationStore>): SmartTestRig =
        SmartTestRig(conversationStores = stores).also {
            it.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        }

    // ------------------------------------------------------------ the renderer's wire contract

    @Test
    fun `an image bearing tool output renders its image array`() {
        val item = ModelHistoryItem.ToolOutput("call-1", """{"ok":true}""", listOf(reference))

        val segment = item.toSegment()

        assertEquals(SegmentOrigin.TOOL_ROUND, segment.origin)
        assertTrue(segment.trusted)
        val entry = Json.parseToJsonElement(segment.text).jsonArray.single().jsonObject
        assertEquals("output", entry["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", entry["callId"]?.jsonPrimitive?.content)
        assertEquals("""{"ok":true}""", entry["output"]?.jsonPrimitive?.content)
        val image = entry["images"]?.jsonArray?.single()?.jsonObject
        assertEquals(reference.artifactId, image?.get("artifactId")?.jsonPrimitive?.content)
        assertEquals(reference.mime, image?.get("mime")?.jsonPrimitive?.content)
        assertEquals(reference.width, image?.get("width")?.jsonPrimitive?.int)
        assertEquals(reference.height, image?.get("height")?.jsonPrimitive?.int)
        assertEquals(reference.sha256, image?.get("sha256")?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool output without images omits the images member`() {
        // The pre-0.25 shape must stay byte-for-byte sendable: an empty `images` member is not the same contract as no
        // member, and the decoder's backward-compatibility promise is about a round that never had one.
        val segment = ModelHistoryItem.ToolOutput("call-1", "done").toSegment()

        val entry = Json.parseToJsonElement(segment.text).jsonArray.single().jsonObject
        assertFalse(entry.containsKey("images"), "a plain output must not invent an images member: ${segment.text}")
        assertEquals("call-1", entry["callId"]?.jsonPrimitive?.content)
        assertEquals("done", entry["output"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------ store → frame → gateway

    @Test
    fun `an image reference stored with a tool round reaches the next request`() {
        val stores = stores()
        val rig = rig(stores)
        // A completed earlier turn with a screenshot round. `appendTranscript` is the production record path, so the
        // seed is the same shape the plugin writes after a run.
        stores.getValue(ContextRoute.WAKE).appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "我来看看"),
                ModelHistoryItem.ToolCall("call-1", "device", """{"action":"observe"}""", "item-1"),
                ModelHistoryItem.ToolOutput("call-1", """{"ok":true}""", listOf(reference)),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "看到了"),
            ),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "现在九点。") }

        rig.wakeAndFinal("现在几点了")

        val sent = rig.model.requests.single()
        assertTrue(
            sent.history.any { it is ModelHistoryItem.ToolCall && it.callId == "call-1" },
            "the stored call must reach the request as a structured item: ${sent.history}",
        )
        val output = sent.history.filterIsInstance<ModelHistoryItem.ToolOutput>().single { it.callId == "call-1" }
        assertEquals(
            listOf(reference),
            output.images,
            "store → toSegment → gateway must not drop the image reference: ${sent.history}",
        )
    }

    // ------------------------------------------------------------ the production record path

    @Test
    fun `a multi step run records its tool round and the closing reply`() {
        val stores = stores()
        val rig = rig(stores)
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request++
            if (request == 1) {
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = modelRequest.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "已经看过了。")
            }
        }

        rig.wakeAndFinal("现在几点了")

        val history = stores.getValue(ContextRoute.WAKE).history()
        assertEquals(
            1,
            history.count { it is ModelHistoryItem.Text && it.role == MessageRole.USER && it.text == "现在几点了" },
            "the turn's utterance must be recorded once, not once per recorded half: $history",
        )
        val call = history.filterIsInstance<ModelHistoryItem.ToolCall>().single()
        assertEquals("notify_user", call.name)
        assertEquals("c1", call.callId)
        val output = history.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals("c1", output.callId)
        assertEquals("notified:1", output.output)
        // The closing assistant text is what the wearer actually heard/saw: the notification bodies plus the model's
        // closing text, exactly the sentence `recordTurnAndCompress` has always recorded.
        val closing = history.last()
        assertTrue(closing is ModelHistoryItem.Text, "the turn must close on assistant text: $history")
        assertEquals(MessageRole.ASSISTANT, closing.role)
        assertEquals("稍等\n已经看过了。", closing.text)
    }

    @Test
    fun `the next turn's request carries the previous turn's tool round`() {
        val stores = stores()
        val rig = rig(stores)
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request++
            if (request == 1) {
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = modelRequest.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "好。")
            }
        }

        rig.wakeAndFinal("现在几点了", turnId = "asr-1")
        // The wake path has a 3 s cooldown, so the second wake must pass it.
        rig.tick(4_000)
        rig.wakeAndFinal("第二个问题", turnId = "asr-2")

        val sent = rig.model.requests.last()
        assertTrue(
            sent.history.any { it is ModelHistoryItem.ToolCall && it.callId == "c1" },
            "the previous turn's call must be in the next request's history: ${sent.history}",
        )
        assertTrue(
            sent.history.any { it is ModelHistoryItem.ToolOutput && it.callId == "c1" && it.output == "notified:1" },
            "the previous turn's output must be in the next request's history: ${sent.history}",
        )
        assertTrue(
            sent.history.any { it is ModelHistoryItem.Text && it.text.contains("稍等") },
            "the notification body the wearer heard must stay in the session: ${sent.history}",
        )
    }

    // ------------------------------------------------------------ the fallback path

    @Test
    fun `a run that hands over no transcript falls back to the two text halves`() {
        // A transport failure makes `AgentLoop.run` throw, so the plugin's catch builds an `AgentRun` with no
        // transcript. The record path must keep its pre-0.25 shape for that turn, not append nothing.
        val stores = stores()
        val rig = rig(stores)
        rig.model.onRequest = { _, _ -> throw IllegalStateException("transport down") }

        rig.wakeAndFinal("帮我看看")

        val history = stores.getValue(ContextRoute.WAKE).history()
        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "帮我看看"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "智能助手这次没能给出结果。"),
            ),
            history,
            "an empty transcript must still leave an answerable text turn",
        )
    }
}
