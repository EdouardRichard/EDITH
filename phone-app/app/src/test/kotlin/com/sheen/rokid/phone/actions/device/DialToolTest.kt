package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43/-4.12 R-03: `dial` is an accepted-only action -?it never waits for a screen observation, it
 * never falls back to `ACTION_DIAL`, and it refuses without `CALL_PHONE`.
 */
class DialToolTest {

    private val json = Json

    @Test
    fun `without CALL_PHONE the dial is refused and the executor is never called`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { false }.execute("""{"number":"10086"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CALL_PERMISSION_MISSING", errorCode(result))
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `a successful dial is accepted-only and carries no number`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { true }.execute("""{"number":"10086"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val body = body(result)
        assertEquals(true, body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["accepted"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, body["applied"])
        assertEquals("intent", body["channel"]!!.jsonPrimitive.content)
        assertEquals(listOf("dial" to """{"number":"10086"}"""), executor.calls)
        assertFalse("the number must not be repeated into the result", result.detail.contains("10086"))
    }

    @Test
    fun `an invalid number is ARGUMENTS_INVALID`() {
        val executor = FakeExecutor(ActionResult(ActionOutcome.Refused, "ARGUMENTS_INVALID"))

        val result = DialTool(executor) { true }.execute("""{"number":"not-a-number"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", errorCode(result))
    }

    @Test
    fun `there is no ACTION_DIAL fallback`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { true }.execute("""{"number":"10086"}""")

        assertEquals(listOf("dial"), executor.calls.map { it.first })
        assertFalse(result.detail.contains("ACTION_DIAL"))
        assertFalse(executor.calls.any { (_, args) -> args.contains("DIAL") })
    }

    @Test
    fun `a missing number is refused without calling the executor`() {
        val executor = FakeExecutor()

        val result = DialTool(executor) { true }.execute("{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", errorCode(result))
        assertTrue(executor.calls.isEmpty())
    }

    private fun body(result: ActionResult): JsonObject =
        json.parseToJsonElement(result.detail).jsonObject

    private fun errorCode(result: ActionResult): String =
        body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private class FakeExecutor(
        private val result: ActionResult = ActionResult(ActionOutcome.Done, "dialing"),
    ) : ActionExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override fun descriptors(): List<ActionDescriptor> = emptyList()

        override fun execute(name: String, argsJson: String): ActionResult {
            calls += name to argsJson
            return result
        }

        override fun cancel() = Unit
    }
}
