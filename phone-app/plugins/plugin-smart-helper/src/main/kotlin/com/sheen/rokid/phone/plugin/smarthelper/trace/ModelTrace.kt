package com.sheen.rokid.phone.plugin.smarthelper.trace

import com.sheen.rokid.agent.AgentRun
import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The debug-only, one-line JSON description of what one agent run saw and did.
 *
 * This is **not** a log line and deliberately does not go through `P3Log`: `P3Log`'s contract is counts and stable
 * codes only, and it must stay that way. This trace carries the model's own bodies because its whole purpose is a
 * local, App-private debugging file that `run-as` can pull off the phone; it is only ever written by a debug build
 * (`BuildConfig.DEBUG`), never uploaded, and never committed as evidence.
 *
 * One run is exactly **one** `JsonObject.toString()` line. kotlinx-serialization escapes every body newline, quote and
 * control character, so a model answer or a screen tree can never split one record into two.
 *
 * What a run leaves behind is not enough on its own: `AgentRun.transcript` is the persistence-shaped transcript, so a
 * tool output carrying perception content (a screen observation) is replaced there by a marker. The request frame the
 * loop actually sent still holds the full output, so [line] adds those rounds under `requestToolRounds`. That is where the
 * `observation.screen` tree the model read can be recovered instead of only the redaction marker.
 */
internal object ModelTrace {

    /** Lenient: the model authors `argsJson` and tool data, and a lenient parse is still faithful about the value. */
    private val json = Json { isLenient = true }

    fun line(
        turnId: String,
        route: String,
        run: AgentRun,
        sentFrame: RequestFrame?,
        timestampMillis: Long,
    ): String {
        val trace = buildJsonObject {
            put("turnId", turnId)
            put("route", route)
            put("timestamp", timestampMillis)
            put("outcome", run.outcome.name)
            put("code", run.safeCode)
            put("finalText", run.finalText)
            put("finalReasoning", run.finalReasoning)
            put("steps", buildJsonArray {
                run.steps.forEach { step ->
                    add(buildJsonObject {
                        put("index", step.index)
                        put("action", step.action)
                        put("result", step.result)
                        put("executed", step.executed)
                    })
                }
            })
            put("history", buildJsonArray { run.transcript.forEach { add(historyItem(it)) } })
            val sentRounds = requestToolRounds(sentFrame)
            if (sentRounds.isNotEmpty()) {
                put("requestToolRounds", buildJsonArray { sentRounds.forEach { add(it) } })
            }
        }
        return trace.toString()
    }

    /** One `ModelHistoryItem` in the same three shapes the transcript has: text, tool call, tool output. */
    private fun historyItem(item: ModelHistoryItem): JsonObject = when (item) {
        is ModelHistoryItem.Text -> buildJsonObject {
            put("type", "text")
            put("role", item.role.name)
            put("text", item.text)
            put("reasoning", item.reasoning)
        }
        is ModelHistoryItem.ToolCall -> buildJsonObject {
            put("type", "toolCall")
            put("id", item.callId)
            put("itemId", item.itemId)
            put("name", item.name)
            put("argsJson", item.argsJson)
            put("args", parsedOrString(item.argsJson))
        }
        is ModelHistoryItem.ToolOutput -> buildJsonObject {
            put("type", "toolOutput")
            put("callId", item.callId)
            put("payload", parsedOrString(item.output))
            if (item.images.isNotEmpty()) {
                put("images", buildJsonArray {
                    item.images.forEach { image ->
                        add(buildJsonObject {
                            put("artifactId", image.artifactId)
                            put("mime", image.mime)
                            put("width", image.width)
                            put("height", image.height)
                            put("sha256", image.sha256)
                        })
                    }
                })
            }
        }
    }

    /**
     * The tool rounds of the request frame the loop last assembled, normalised to the same vocabulary as [historyItem].
     *
     * The frame is the only surviving carrier of an observation the loop redacted out of its stored transcript, and of
     * a call's arguments exactly as they were sent. A frame whose segments do not decode contributes nothing: a trace
     * must never invent a round it cannot read.
     */
    private fun requestToolRounds(frame: RequestFrame?): List<JsonObject> {
        if (frame == null) return emptyList()
        return frame.conversationFlow
            .filter { it.origin == SegmentOrigin.TOOL_ROUND }
            .flatMap { segment ->
                val parsed = runCatching { json.parseToJsonElement(segment.text) }.getOrNull()
                (parsed as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            }
            .mapNotNull { entry ->
                when (entry.stringMember("type")) {
                    "call" -> buildJsonObject {
                        put("type", "toolCall")
                        put("id", entry.stringMember("callId"))
                        put("itemId", entry.stringMember("itemId"))
                        put("name", entry.stringMember("name"))
                        val argsJson = entry.stringMember("argsJson")
                        if (argsJson != null) {
                            put("argsJson", argsJson)
                            put("args", parsedOrString(argsJson))
                        }
                    }
                    "output" -> buildJsonObject {
                        put("type", "toolOutput")
                        put("callId", entry.stringMember("callId"))
                        entry.stringMember("output")?.let { put("payload", parsedOrString(it)) }
                        (entry["images"] as? JsonArray)?.let { put("images", it) }
                    }
                    // A shape this trace does not know is skipped rather than guessed at.
                    else -> null
                }
            }
    }

    private fun JsonObject.stringMember(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Tool payloads are JSON by contract; a value that is not JSON stays a string instead of failing the trace. */
    private fun parsedOrString(text: String): JsonElement =
        runCatching { json.parseToJsonElement(text) }.getOrNull() ?: JsonPrimitive(text)
}
