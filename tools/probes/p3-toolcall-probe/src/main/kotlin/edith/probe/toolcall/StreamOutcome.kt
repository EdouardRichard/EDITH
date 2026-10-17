package edith.probe.toolcall

import com.sheen.rokid.model.ResponsesDecoder
import com.sheen.rokid.model.SseDecoder
import com.sheen.rokid.model.SseFrame
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlinx.serialization.json.Json

/**
 * One streaming attempt's verdict.
 *
 * [safeCode] separates the three findings that must never be confused: "the endpoint answered nothing like an SSE
 * turn" (`CONTENT_TYPE`, `NO_TERMINAL`, `EOF_INCOMPLETE`), "the turn ended without ever delivering the call"
 * (`NO_TOOL_CALL`) and "the call arrived" (`OK`). A completed stream that observed argument fragments but no terminal
 * call must never report `OK` — that is the C07 failure mode, where a swallowed call looks like a successful turn.
 *
 * [argumentsValidJson] is reported only for an actually delivered call; it is `false` when there is no call to judge.
 */
data class StreamOutcome(
    val httpStatus: Int, val textDeltas: Int, val toolCallDeltas: Int,
    val sawToolCallTerminal: Boolean, val toolName: String?, val itemId: String?,
    val callId: String?, val argumentsValidJson: Boolean, val terminal: String?, val safeCode: String?,
)

/**
 * Folds the real decoder's event stream into the probe's verdict.
 *
 * Rules, in order:
 * 1. `httpStatus != 200` keeps the non-streaming probe's failure shape: a gateway or auth answer is not a model answer.
 * 2. No `Terminal` event at all (a truncated read) reports `terminal = null` and `NO_TERMINAL`.
 * 3. A `ToolCallTerminal` is the only thing that may report `OK`; its arguments document decides `OK` vs
 *    `ARGUMENTS_INVALID_JSON`.
 * 4. Otherwise a call was observed but never closed (`ToolCallDelta` seen) → `NO_TOOL_CALL`, never `OK`. Without any
 *    call, the decoder's own code is surfaced when it has one (e.g. `EOF_INCOMPLETE`, `RESPONSE_LIMIT`), so a
 *    truncated or failed turn cannot be recorded as "this model does not call tools".
 */
fun outcomeOf(httpStatus: Int, events: List<ModelStreamEvent>): StreamOutcome {
    val textDeltas = events.count { it is ModelStreamEvent.TextDelta }
    val toolCallDeltas = events.count { it is ModelStreamEvent.ToolCallDelta }
    if (httpStatus != 200) {
        return StreamOutcome(httpStatus, 0, 0, false, null, null, null, false, null, "HTTP_$httpStatus")
    }
    val call = events.filterIsInstance<ModelStreamEvent.ToolCallTerminal>().lastOrNull()
    val terminal = events.filterIsInstance<ModelStreamEvent.Terminal>().lastOrNull()
    val argumentsValid = call?.let { runCatching { Json.parseToJsonElement(it.argsJson) }.isSuccess } ?: false
    val safeCode = when {
        terminal == null -> "NO_TERMINAL"
        call != null -> if (argumentsValid) "OK" else "ARGUMENTS_INVALID_JSON"
        toolCallDeltas > 0 -> "NO_TOOL_CALL"
        else -> terminal.safeCode ?: "NO_TOOL_CALL"
    }
    return StreamOutcome(
        httpStatus = httpStatus,
        textDeltas = textDeltas,
        toolCallDeltas = toolCallDeltas,
        sawToolCallTerminal = call != null,
        toolName = call?.name,
        itemId = call?.itemId,
        callId = call?.callId,
        argumentsValidJson = argumentsValid,
        terminal = terminal?.end?.name,
        safeCode = safeCode,
    )
}

/**
 * A request that never reached a verdict: the transport failed before the endpoint answered.
 *
 * Only the class name is kept. An exception message can carry a URL, a host name or worse, and this line is destined
 * for an evidence file — the class name is enough to tell a timeout from a refused connection.
 */
fun transportFailure(error: Throwable): StreamOutcome =
    outcomeOf(0, emptyList()).copy(safeCode = "TRANSPORT_${error.javaClass.simpleName.uppercase()}")

/**
 * Drives the real framing/decoding pair over the response body, exactly as `ControlledModelClient` does: 8 KiB reads
 * fed to [SseDecoder], every dispatched frame handed to [ResponsesDecoder], and `endOfInput()` at EOF so a truncated
 * stream still terminates instead of leaving the caller without a verdict.
 *
 * Tests run this same loop over fixed SSE text, so the offline pins and the live measurement cannot drift apart.
 */
fun decodeStream(requestId: String, chunks: Sequence<ByteArray>): List<ModelStreamEvent> {
    val framing = SseDecoder()
    val decoder = ResponsesDecoder(requestId)
    val events = mutableListOf<ModelStreamEvent>()
    fun accept(frames: List<SseFrame>) {
        for (frame in frames) events += decoder.accept(frame)
    }
    for (chunk in chunks) accept(framing.feed(chunk))
    accept(framing.finish())
    events += decoder.endOfInput()
    return events
}

/**
 * The one line `--stream` prints: exactly the outcome's fields, in a fixed order.
 *
 * The caller scans this text for the credential prefix before printing it and appends the scan result; nothing from the
 * wire — not the body, not a header, not the key — may join these fields.
 */
fun streamFields(outcome: StreamOutcome): String = "P3_T4B_STREAM " + listOf(
    "httpStatus=${outcome.httpStatus}",
    "textDeltas=${outcome.textDeltas}",
    "toolCallDeltas=${outcome.toolCallDeltas}",
    "sawToolCallTerminal=${outcome.sawToolCallTerminal}",
    "toolName=${outcome.toolName}",
    "itemId=${outcome.itemId}",
    "callId=${outcome.callId}",
    "argumentsValidJson=${outcome.argumentsValidJson}",
    "terminal=${outcome.terminal}",
    "safeCode=${outcome.safeCode}",
).joinToString(" ")
