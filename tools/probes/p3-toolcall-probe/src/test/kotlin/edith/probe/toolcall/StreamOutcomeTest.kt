package edith.probe.toolcall

import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.net.SocketTimeoutException

/**
 * The streaming verdict, pinned before any network call.
 *
 * The delivery path is always streaming, so this is the shape the loop under Tasks 13/14 will actually see. The
 * fixtures are replayed through the **real** [com.sheen.rokid.model.SseDecoder] / [com.sheen.rokid.model.ResponsesDecoder] pair in small
 * chunks — the same loop the live `--stream` mode runs — rather than through a second parser that could agree with a
 * mistaken reading of the wire.
 *
 * The failure this guards against is the one the plan names under C07: a call that was observed but never completed
 * must never read as a delivered call, and a turn that ended without a terminal must never read as `COMPLETED`.
 */
class StreamOutcomeTest {

    @Test
    fun streamedToolCallIsAssembledFromDeltasAndTheItemDone() {
        val decoded = events(sse(TOOL_CALL_FRAMES))
        val outcome = outcomeOf(200, decoded)

        // The measured order the plan asked about: the arguments deltas arrive before the item that closes the call.
        assertTrue(
            decoded.indexOfFirst { it is ModelStreamEvent.ToolCallDelta } <
                decoded.indexOfFirst { it is ModelStreamEvent.ToolCallTerminal },
            "events=$decoded",
        )
        assertEquals(0, outcome.textDeltas)
        assertEquals(1, outcome.toolCallDeltas)
        assertTrue(outcome.sawToolCallTerminal)
        assertEquals("record_note", outcome.toolName)
        assertEquals("fc_1", outcome.itemId)
        assertEquals("call_1", outcome.callId)
        assertTrue(outcome.argumentsValidJson)
        assertEquals("COMPLETED", outcome.terminal)
        assertEquals("OK", outcome.safeCode)
    }

    @Test
    fun streamedArgumentsWithoutTheItemDoneNeverReportACompleteCall() {
        val withoutDone = TOOL_CALL_FRAMES.filterNot { it.startsWith("event: response.output_item.done") }
        val outcome = outcomeOf(200, events(sse(withoutDone)))

        assertEquals(1, outcome.toolCallDeltas, "the fragments were still observed")
        assertFalse(outcome.sawToolCallTerminal)
        // The endpoint answered `completed`, but the call never closed: this must never be read as tool support.
        assertEquals("NO_TOOL_CALL", outcome.safeCode)
        assertEquals("INCOMPLETE", outcome.terminal)
        assertNull(outcome.toolName)
        assertNull(outcome.callId)
        assertFalse(outcome.argumentsValidJson)
    }

    @Test
    fun textOnlyStreamCompletesWithoutAToolCall() {
        val outcome = outcomeOf(200, events(sse(TEXT_FRAMES)))

        assertEquals(2, outcome.textDeltas)
        assertEquals(0, outcome.toolCallDeltas)
        assertFalse(outcome.sawToolCallTerminal)
        assertNull(outcome.toolName)
        assertEquals("COMPLETED", outcome.terminal)
        assertEquals("NO_TOOL_CALL", outcome.safeCode)
    }

    @Test
    fun truncatedStreamWithoutATerminalIsReportedAsNoTerminal() {
        val outcome = outcomeOf(200, listOf(ModelStreamEvent.TextDelta("probe", "partial")))

        assertNull(outcome.terminal)
        assertEquals("NO_TERMINAL", outcome.safeCode)
        assertEquals(1, outcome.textDeltas)
        assertFalse(outcome.sawToolCallTerminal)
    }

    @Test
    fun nonStreamingHttpFailureKeepsTheExistingFailureShape() {
        val outcome = outcomeOf(401, emptyList())

        assertEquals(401, outcome.httpStatus)
        assertEquals(0, outcome.textDeltas)
        assertEquals(0, outcome.toolCallDeltas)
        assertFalse(outcome.sawToolCallTerminal)
        assertNull(outcome.terminal)
        assertEquals("HTTP_401", outcome.safeCode)
    }

    @Test
    fun terminalToolCallWithUnparsableArgumentsIsNotReportedAsOk() {
        val outcome = outcomeOf(
            200,
            listOf(
                ModelStreamEvent.ToolCallTerminal("probe", "fc_1", "call_1", "record_note", "{not json"),
                ModelStreamEvent.Terminal("probe", ModelEnd.COMPLETED, null, null),
            ),
        )

        assertTrue(outcome.sawToolCallTerminal)
        assertFalse(outcome.argumentsValidJson)
        assertEquals("ARGUMENTS_INVALID_JSON", outcome.safeCode)
    }

    @Test
    fun transportFailureNamesTheClassAndNeverTheMessage() {
        val outcome = transportFailure(SocketTimeoutException("connect to https://example.invalid/ failed"))

        assertEquals(0, outcome.httpStatus)
        assertEquals("TRANSPORT_SOCKETTIMEOUTEXCEPTION", outcome.safeCode)
        assertFalse(outcome.toString().contains("example.invalid"), "a transport message may carry a URL; it is not printed")
    }

    @Test
    fun printedLineCarriesOnlyTheOutcomeFields() {
        val fields = streamFields(outcomeOf(200, events(sse(TEXT_FRAMES))))
            .removePrefix("P3_T4B_STREAM ")
            .split(" ")
            .map { it.substringBefore("=") }
            .toSet()

        assertEquals(
            setOf(
                "httpStatus", "textDeltas", "toolCallDeltas", "sawToolCallTerminal", "toolName",
                "itemId", "callId", "argumentsValidJson", "terminal", "safeCode",
            ),
            fields,
        )
    }

    /** Four-byte chunks: every frame boundary lands mid-line, so the real framing loop is exercised, not bypassed. */
    private fun events(sse: String, chunk: Int = 4): List<ModelStreamEvent> =
        decodeStream("probe", sse.toByteArray(Charsets.UTF_8).asList().chunked(chunk).map { it.toByteArray() }.asSequence())

    private fun frame(event: String, json: String) = "event: $event\ndata: $json"

    /** A stream only dispatches on a blank line; the postfix is the frame terminator, not decoration. */
    private fun sse(frames: List<String>) = frames.joinToString(separator = "\n\n", postfix = "\n\n")

    /**
     * The shape of `model-runtime`'s proven-green `responses/tool-call-completed.sse`, with the call id/item id kept so
     * the probe's verdict can be checked field by field.
     */
    private val TOOL_CALL_FRAMES = listOf(
        frame(
            "response.created",
            """{"type":"response.created","sequence_number":8,"response":{"id":"resp_probe_1","status":"in_progress"}}""",
        ),
        frame(
            "response.output_item.added",
            """{"type":"response.output_item.added","sequence_number":10,"output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note"}}""",
        ),
        frame(
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","sequence_number":13,"output_index":0,"item_id":"fc_1","delta":"{\"title\":\"回电话\"}"}""",
        ),
        frame(
            "response.output_item.done",
            """{"type":"response.output_item.done","sequence_number":15,"output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note","arguments":"{\"title\":\"回电话\"}"}}""",
        ),
        frame(
            "response.completed",
            """{"type":"response.completed","sequence_number":21,"response":{"id":"resp_probe_1","status":"completed","output":[{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note","arguments":"{\"title\":\"回电话\"}"}],"usage":{"input_tokens":11,"output_tokens":7}}}""",
        ),
    )

    /** Text-only, mirroring `responses/text-completed.sse`: two deltas, a matching done, then a validated terminal. */
    private val TEXT_FRAMES = listOf(
        frame(
            "response.created",
            """{"type":"response.created","sequence_number":8,"response":{"id":"resp_probe_2","status":"in_progress"}}""",
        ),
        frame(
            "response.output_item.added",
            """{"type":"response.output_item.added","sequence_number":10,"output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[]}}""",
        ),
        frame(
            "response.content_part.added",
            """{"type":"response.content_part.added","sequence_number":12,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}""",
        ),
        frame(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","sequence_number":15,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"提示"}""",
        ),
        frame(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","sequence_number":18,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"😀"}""",
        ),
        frame(
            "response.output_text.done",
            """{"type":"response.output_text.done","sequence_number":19,"item_id":"msg_1","output_index":0,"content_index":0,"text":"提示😀"}""",
        ),
        frame(
            "response.completed",
            """{"type":"response.completed","sequence_number":21,"response":{"id":"resp_probe_2","status":"completed","output":[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"提示😀"}]}],"usage":{"input_tokens":12,"output_tokens":4}}}""",
        ),
    )
}
