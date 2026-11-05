package com.sheen.rokid.phone.plugin.smarthelper.trace

import com.sheen.rokid.agent.AgentOutcome
import com.sheen.rokid.agent.AgentRun
import com.sheen.rokid.agent.AgentStep
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The debug-only model trace: one run is exactly one JSON line, and that line carries the transcript the model saw.
 *
 * These tests are about the *serialization contract* — the fields an analysis of "what did the model call, and what did
 * it observe" needs, and the single-line guarantee that makes the file appendable and `run-as ... cat`-able. They call
 * the formatter directly; the plugin's once-per-run wiring is `ModelTraceWiringTest`'s subject.
 */
class ModelTraceTest {

    private val observationPayload =
        """{"execution":{"outcome":"done"},"observation":{"screen":"[#1] 搜索 按钮","vision":"ok"},"data":"ok"}"""

    @Test
    fun `a run's trace line carries the transcript, reasoning, steps and observation payload`() {
        val run = AgentRun(
            outcome = AgentOutcome.COMPLETED,
            safeCode = null,
            finalText = "看到了。",
            steps = listOf(AgentStep(index = 0, action = "device", result = "ok")),
            transcript = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "我来看看", reasoning = "先观察"),
                ModelHistoryItem.ToolCall(
                    callId = "c1",
                    name = "device",
                    argsJson = """{"action":"tap","ref":"#n12","index":3,"text":"搜索"}""",
                    itemId = "item-1",
                ),
                ModelHistoryItem.ToolOutput("c1", observationPayload),
            ),
            finalReasoning = "先观察",
        )

        val line = ModelTrace.line("asr-1", "WAKE", run, null, 1_700_000_000_000L)

        assertFalse(line.contains('\n'), "one run must be exactly one line: $line")
        val trace = Json.parseToJsonElement(line).jsonObject
        assertEquals("asr-1", trace["turnId"]!!.jsonPrimitive.content)
        assertEquals("WAKE", trace["route"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_000L, trace["timestamp"]!!.jsonPrimitive.long)
        assertEquals("COMPLETED", trace["outcome"]!!.jsonPrimitive.content)
        assertEquals("看到了。", trace["finalText"]!!.jsonPrimitive.content)
        assertEquals("先观察", trace["finalReasoning"]!!.jsonPrimitive.content)

        val step = trace["steps"]!!.jsonArray[0].jsonObject
        assertEquals("device", step["action"]!!.jsonPrimitive.content)
        assertEquals("ok", step["result"]!!.jsonPrimitive.content)

        val history = trace["history"]!!.jsonArray
        assertEquals(4, history.size, "the whole transcript is traced: $history")
        val assistant = history[1].jsonObject
        assertEquals("text", assistant["type"]!!.jsonPrimitive.content)
        assertEquals("ASSISTANT", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("先观察", assistant["reasoning"]!!.jsonPrimitive.content)

        val call = history[2].jsonObject
        assertEquals("toolCall", call["type"]!!.jsonPrimitive.content)
        assertEquals("device", call["name"]!!.jsonPrimitive.content)
        assertEquals("c1", call["id"]!!.jsonPrimitive.content)
        assertEquals("item-1", call["itemId"]!!.jsonPrimitive.content)
        assertEquals("#n12", call["args"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("3", call["args"]!!.jsonObject["index"]!!.jsonPrimitive.content)
        assertEquals("搜索", call["args"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(call["argsJson"]!!.jsonPrimitive.content.contains(""""ref":"#n12""""), call.toString())

        val output = history[3].jsonObject
        assertEquals("toolOutput", output["type"]!!.jsonPrimitive.content)
        assertEquals("c1", output["callId"]!!.jsonPrimitive.content)
        assertEquals(
            "[#1] 搜索 按钮",
            output["payload"]!!.jsonObject["observation"]!!.jsonObject["screen"]!!.jsonPrimitive.content,
            "the model-facing payload must stay structured, not re-escaped into one opaque string",
        )
    }

    @Test
    fun `newlines and quotes in the model's own text never break the single-line contract`() {
        val run = AgentRun(
            outcome = AgentOutcome.FAILED,
            safeCode = "PROVIDER_ERROR",
            finalText = "第一个原因\r\n第二个原因 \"引号\"",
            steps = emptyList(),
            transcript = listOf(ModelHistoryItem.Text(MessageRole.USER, "第一行\n第二行")),
            finalReasoning = "想\n了两步",
        )

        val line = ModelTrace.line("asr-2", "WAKE", run, null, 1_700_000_000_001L)

        assertFalse(line.contains('\n'), "a body newline must be escaped, never emitted: $line")
        assertFalse(line.contains('\r'), "a CR must be escaped, never emitted: $line")
        val trace = Json.parseToJsonElement(line).jsonObject
        assertEquals("PROVIDER_ERROR", trace["code"]!!.jsonPrimitive.content)
        assertEquals(
            "第一行\n第二行",
            trace["history"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("想\n了两步", trace["finalReasoning"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a history-only request frame's tool round keeps the unredacted observation`() {
        // `AgentRun.transcript` is the storage-shaped transcript, so a perception-bearing tool output is replaced by a
        // marker there. The frame handed to the model is the only place the observation the model actually read survives.
        val run = AgentRun(
            outcome = AgentOutcome.COMPLETED,
            safeCode = null,
            finalText = "看完了。",
            steps = listOf(AgentStep(0, "device", "ok")),
            transcript = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.ToolCall("c1", "device", """{"action":"observe"}"""),
                ModelHistoryItem.ToolOutput("c1", "(perception content not persisted)"),
            ),
        )
        val frame = com.sheen.rokid.agent.RequestFrame(
            systemSegments = listOf(
                com.sheen.rokid.agent.Segment(
                    com.sheen.rokid.agent.SegmentOrigin.INSTRUCTIONS,
                    trusted = true,
                    text = "system",
                ),
            ),
            conversationFlow = listOf(
                com.sheen.rokid.agent.Segment(
                    com.sheen.rokid.agent.SegmentOrigin.TRANSCRIPT,
                    trusted = true,
                    text = "看一下屏幕",
                    role = com.sheen.rokid.agent.SegmentRole.USER,
                ),
                com.sheen.rokid.agent.Segment(
                    com.sheen.rokid.agent.SegmentOrigin.TOOL_ROUND,
                    trusted = true,
                    text = """[{"type":"call","callId":"c1","itemId":"c1","name":"device","argsJson":"{\"action\":\"observe\"}"}]""",
                ),
                com.sheen.rokid.agent.Segment(
                    com.sheen.rokid.agent.SegmentOrigin.TOOL_ROUND,
                    trusted = true,
                    text = """[{"type":"output","callId":"c1","output":"{\"execution\":{\"outcome\":\"done\"},\"observation\":{\"screen\":\"[#1] 设置 按钮\"},\"data\":\"\"}"}]""",
                ),
                com.sheen.rokid.agent.Segment(
                    com.sheen.rokid.agent.SegmentOrigin.USER_INPUT,
                    trusted = true,
                    text = "Continue after the tool result.",
                ),
            ),
            tools = emptyList(),
        )

        val trace = Json.parseToJsonElement(
            ModelTrace.line("asr-3", "WAKE", run, frame, 1_700_000_000_002L),
        ).jsonObject

        val sent = trace["requestToolRounds"]!!.jsonArray.map { it.jsonObject }
        val call = sent.single { it["type"]!!.jsonPrimitive.content == "toolCall" }
        assertEquals("device", call["name"]!!.jsonPrimitive.content)
        assertEquals("observe", call["args"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        val output = sent.single { it["type"]!!.jsonPrimitive.content == "toolOutput" }
        assertEquals(
            "[#1] 设置 按钮",
            output["payload"]!!.jsonObject["observation"]!!.jsonObject["screen"]!!.jsonPrimitive.content,
        )
    }
}
