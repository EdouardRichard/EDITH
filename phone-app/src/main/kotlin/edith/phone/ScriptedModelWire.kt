package edith.phone

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A debug-only **scripted model wire**: an OkHttp interceptor that answers the model request with a canned SSE stream.
 *
 * Why it exists. Spec §17.8's Stage C acceptance needs an **L3/L4 tool call** to travel the real path - the real
 * `WhitelistActionPolicy`, the real confirmation gate and its phone card, the real privileged executor with its
 * `TargetState` re-check, and the real audit. The configured model would not emit one: four attempts on the K40 all
 * ended `steps=0`, and the fourth used a purely numeric target that transcribes cleanly, which rules out "the ASR ate
 * the package name" and leaves "the model asks before doing something destructive". A phase whose destructive half
 * cannot be exercised at all cannot be honestly accepted, so the *transport* is scripted instead: everything between
 * the transcript and the wire stays exactly as it ships, and only the model's choice is canned.
 *
 * What it is **not**: it does not bypass consent, the capability gate, the budget, the policy, the confirmation gate or
 * the executor. `ControlledModelClient` and everything above it still run; only the bytes are ours. That is the same
 * kind of disclosure the audio injector carries ("not the wearer's voice") and must be stated wherever its results are
 * used.
 *
 * The stream shape is `model-runtime`'s proven-green `responses/tool-call-completed.sse`: `response.created` →
 * `output_item.added` → arguments delta → `output_item.done` → `response.completed`. A missing terminal is a refusal
 * to that decoder, not a tool call, so the frames are not decorative.
 *
 * **While armed it never falls through to the real transport.** A scripted acceptance run must not be able to put a
 * transcript on the network merely because the script ran out of answers.
 *
 * It lives in the main source set because the composition has to install it on its OkHttp client, but it can only be
 * armed through [HostCompositionRoot.armScriptedModelAnswer], which refuses outside a debug build - so a release APK
 * contains an inert interceptor and no way to reach it.
 */
internal class ScriptedModelWire(private val log: (String) -> Unit = { P3Log.line(it) }) : Interceptor {

    /** One canned answer, already serialised as the wire would deliver it. */
    internal class Answer(val body: String)

    private class Script(val action: String, val argsJson: String, val text: String, val callId: String)

    private val lock = Any()
    private var script: Script? = null
    private var servedToolCall = false

    /**
     * A fresh call id per arming, and this is load-bearing rather than cosmetic: the host de-duplicates events on
     * `(session, generation, eventId)`, and a confirmation answer's `eventId` **is** the tool call's id. Reusing one id
     * across scripted runs made the first answer in a session work and every later one look like a duplicate, so the
     * answer was dropped and the question timed out as a denial - an instrument artefact that reads exactly like a
     * product defect.
     */
    private var serial = 0

    /** Arms the wire: the **next** request is answered with [action], every request after it with [text]. */
    fun arm(action: String, argsJson: String, text: String): Boolean {
        val name = action.trim()
        if (name.isEmpty()) return false
        synchronized(lock) {
            serial += 1
            script = Script(
                action = name,
                argsJson = argsJson.trim().ifEmpty { "{}" },
                text = text.ifBlank { DEFAULT_TEXT },
                callId = "call_scripted_$serial",
            )
            servedToolCall = false
        }
        log("P3_SCRIPT state=armed action=$name")
        return true
    }

    /** Hands the transport back. */
    fun disarm() {
        synchronized(lock) {
            script = null
            servedToolCall = false
        }
        log("P3_SCRIPT state=disarmed")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val answer = nextAnswer() ?: return chain.proceed(chain.request())
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(answer.body.toResponseBody(EVENT_STREAM))
            .build()
    }

    /**
     * The next canned answer, or `null` when the wire is not armed (and the real transport must run).
     *
     * The first answer after arming is the tool call; every later one is the closing text, so the loop's continuation
     * request is answered too. It stays armed until [disarm] or a new [arm] - see the class note on falling through.
     */
    internal fun nextAnswer(): Answer? {
        val current: Script
        val asToolCall: Boolean
        synchronized(lock) {
            current = script ?: return null
            asToolCall = !servedToolCall
            servedToolCall = true
        }
        return if (asToolCall) {
            log("P3_SCRIPT served=tool_call action=${current.action} call=${current.callId}")
            Answer(toolCallStream(current.action, current.argsJson, current.callId))
        } else {
            log("P3_SCRIPT served=text")
            Answer(textStream(current.text))
        }
    }

    /** `event: <name>` + `data: <json>`, joined by blank lines: a blank line is what the decoder dispatches on. */
    private fun stream(frames: List<String>): String = frames.joinToString(separator = FRAME_SEPARATOR, postfix = FRAME_SEPARATOR)

    private fun frame(event: String, json: String): String = "event: $event\ndata: $json"

    private fun toolCallStream(name: String, argsJson: String, callId: String): String {
        val arguments = escape(argsJson)
        val itemId = "fc_$callId"
        val responseId = "resp_$callId"
        val item = """{"id":"$itemId","type":"function_call","call_id":"$callId","name":"$name","arguments":"$arguments"}"""
        return stream(
            listOf(
                frame("response.created", """{"type":"response.created","sequence_number":8,"response":{"id":"$responseId","status":"in_progress"}}"""),
                frame("response.output_item.added", """{"type":"response.output_item.added","sequence_number":10,"output_index":0,"item":{"id":"$itemId","type":"function_call","call_id":"$callId","name":"$name"}}"""),
                frame("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","sequence_number":13,"output_index":0,"item_id":"$itemId","delta":"$arguments"}"""),
                frame("response.output_item.done", """{"type":"response.output_item.done","sequence_number":15,"output_index":0,"item":$item}"""),
                frame("response.completed", """{"type":"response.completed","sequence_number":21,"response":{"id":"$responseId","status":"completed","output":[$item],"usage":{"input_tokens":1,"output_tokens":1}}}"""),
            ),
        )
    }

    private fun textStream(text: String): String {
        val escaped = escape(text)
        val message = """{"id":"$MESSAGE_ID","type":"message","role":"assistant","content":[{"type":"output_text","text":"$escaped"}]}"""
        return stream(
            listOf(
                frame("response.created", """{"type":"response.created","sequence_number":8,"response":{"id":"$RESPONSE_ID_TEXT","status":"in_progress"}}"""),
                frame("response.output_item.added", """{"type":"response.output_item.added","sequence_number":10,"output_index":0,"item":{"id":"$MESSAGE_ID","type":"message","role":"assistant","content":[]}}"""),
                frame("response.content_part.added", """{"type":"response.content_part.added","sequence_number":12,"item_id":"$MESSAGE_ID","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}"""),
                frame("response.output_text.delta", """{"type":"response.output_text.delta","sequence_number":15,"item_id":"$MESSAGE_ID","output_index":0,"content_index":0,"delta":"$escaped"}"""),
                frame("response.output_text.done", """{"type":"response.output_text.done","sequence_number":19,"item_id":"$MESSAGE_ID","output_index":0,"content_index":0,"text":"$escaped"}"""),
                frame("response.completed", """{"type":"response.completed","sequence_number":21,"response":{"id":"$RESPONSE_ID_TEXT","status":"completed","output":[$message],"usage":{"input_tokens":1,"output_tokens":1}}}"""),
            ),
        )
    }

    /** Enough JSON string escaping for the only inputs this instrument takes: a name, `{}`-shaped arguments and a line. */
    private fun escape(raw: String): String = buildString(raw.length + 8) {
        raw.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private companion object {
        /** One frame terminator shape, used for both the separator and the postfix - the decoder needs both. */
        const val FRAME_SEPARATOR = "\n\n"
        const val ITEM_ID = "fc_scripted"
        const val CALL_ID = "call_scripted"
        const val RESPONSE_ID = "resp_scripted"
        const val MESSAGE_ID = "msg_scripted"
        const val RESPONSE_ID_TEXT = "resp_scripted_text"
        const val DEFAULT_TEXT = "已处理。"
        val EVENT_STREAM = "text/event-stream; charset=utf-8".toMediaType()
    }
}

/**
 * Builds the arguments JSON for one scripted call from **separate** extras rather than one JSON literal.
 *
 * The JSON had to go: `adb shell am broadcast --es args '{"package":"…"}'` loses its quotes to the device shell, so the
 * executor legitimately answers `ARGUMENTS_INVALID` and the instrument looks broken rather than the call. Building the
 * object here removes the shell from the escaping question entirely. Only the members this phase's actions declare are
 * accepted; a member an action does not take is simply absent from the object.
 */
internal fun scriptedToolCallArgs(
    packageName: String?,
    permission: String?,
    granted: Boolean?,
    value: Int?,
): String = buildJsonObject {
    packageName?.let { put("package", it) }
    permission?.let { put("permission", it) }
    granted?.let { put("granted", it) }
    value?.let { put("value", it) }
}.toString()
