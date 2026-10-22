package edith.probe.toolcall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parse that decides T4, pinned before any network call.
 *
 * The failure this guards against is a *false negative read as a finding*: an endpoint that returns 200 with a plain
 * message, an auth error, or an HTML gateway page must be reported as exactly that — never as "the model does not
 * support tool calls". P2's most expensive lesson was that a self-inflicted payload or parsing mistake was recorded as
 * a vendor limitation; the `safeCode` values here exist so that cannot happen again.
 */
class ToolCallOutcomeTest {

    @Test
    fun outcomeReportsAToolCallWhenTheModelReturnsOne() {
        val body = """{"output":[{"type":"reasoning"},{"type":"function_call","call_id":"c1","name":"record_note","arguments":"{\"title\":\"a\",\"body\":\"b\"}"}]}"""
        val outcome = outcomeOf(200, body)
        assertTrue(outcome.sawToolCall)
        assertEquals("record_note", outcome.toolName)
        assertEquals("OK", outcome.safeCode)
    }

    @Test
    fun outcomeReportsNoToolCallWhenOnlyAMessageComesBack() {
        val body = """{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""
        val outcome = outcomeOf(200, body)
        assertFalse(outcome.sawToolCall)
        assertNull(outcome.toolName)
        assertEquals("NO_TOOL_CALL", outcome.safeCode)
    }

    @Test
    fun outcomeReportsAnHttpFailureWithoutPretendingItIsAModelAnswer() {
        val outcome = outcomeOf(401, """{"error":{"code":"invalid_api_key"}}""")
        assertFalse(outcome.sawToolCall)
        assertEquals("HTTP_401", outcome.safeCode)
    }

    @Test
    fun outcomeMarksUnparsableArgumentsRatherThanCallingThemValid() {
        val body = """{"output":[{"type":"function_call","name":"record_note","arguments":"{not json"}]}"""
        val outcome = outcomeOf(200, body)
        assertTrue(outcome.sawToolCall)
        assertEquals("ARGUMENTS_INVALID_JSON", outcome.safeCode)
    }

    @Test
    fun outcomeHandlesAnUnparsableBodyWithoutThrowing() {
        val outcome = outcomeOf(200, "<html>gateway</html>")
        assertFalse(outcome.sawToolCall)
        assertEquals("BODY_UNPARSABLE", outcome.safeCode)
    }

    @Test
    fun outcomeHandlesAnEmptyOutputListAsNoToolCall() {
        assertEquals("NO_TOOL_CALL", outcomeOf(200, """{"output":[]}""").safeCode)
        assertEquals("NO_TOOL_CALL", outcomeOf(200, """{"id":"r1"}""").safeCode)
    }
}
