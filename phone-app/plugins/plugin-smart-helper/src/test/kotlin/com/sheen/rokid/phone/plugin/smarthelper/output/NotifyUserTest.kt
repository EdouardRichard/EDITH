package com.sheen.rokid.phone.plugin.smarthelper.output

import com.sheen.rokid.agent.SCOPE_TERMINAL
import com.sheen.rokid.agent.ToolCall
import com.sheen.rokid.agent.ToolRegistry
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §6.1/§6.2/§6.3. `notify_user` is the only channel through which the model reaches the wearer — the system
 * does not display its reply — so its channel mapping and its fallback are the interaction contract.
 */
class NotifyUserTest {

    private val tool = NotifyUserTool(onNotify = { _, _ -> })

    @Test
    fun `the descriptor is L0 and not terminal`() {
        val descriptor = tool.descriptors().single()

        assertEquals("notify_user", descriptor.name)
        assertEquals(RiskLevel.L0, descriptor.risk)
        assertEquals(false, descriptor.requiresConfirmation)
        // A mid-task notification that ended the run would make §6.3's "the task continues" impossible.
        assertTrue(descriptor.scope != SCOPE_TERMINAL, "notify_user must not end the loop")
        // The text is the user's and the audit line carries no argument; this is what guarantees it.
        assertNull(descriptor.auditTargetArgument)
    }

    @Test
    fun `the schema asks for a text and a channel from the four the spec names`() {
        val schema = tool.descriptors().single().parametersJson

        assertTrue(schema.contains("\"text\""), schema)
        assertTrue(schema.contains("\"channel\""), schema)
        listOf("screen", "voice", "both", "silent").forEach { channel ->
            assertTrue(schema.contains(channel), "the schema must offer $channel: $schema")
        }
    }

    @Test
    fun `a call reaches the callback with its channel and text`() {
        val seen = mutableListOf<Pair<NotifyChannel, String>>()
        val recorder = NotifyUserTool(onNotify = { channel, text -> seen += channel to text })

        val result = recorder.execute("notify_user", """{"text":"已发好了","channel":"both"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf(NotifyChannel.BOTH to "已发好了"), seen)
    }

    @Test
    fun `a successful call states the done facts it knows and reports no observation`() {
        // 0.3 Phase 2.2 Task 7: a pure plugin tool has no screen to observe, so observation stays null. The call was
        // accepted by the tool, and the notification was handed to the output path. Delivery, independent verification
        // and a screen change are unknown to this tool, so those tri-state facts stay null rather than being guessed.
        val result = tool.execute("notify_user", """{"text":"x","channel":"screen"}""")

        assertEquals(ToolExecution(outcome = ToolOutcome.Done, accepted = true), result.execution)
        assertNull(result.execution?.applied)
        assertNull(result.execution?.verified)
        assertNull(result.execution?.changed)
        assertNull(result.observation)
        assertEquals("notified:1", result.detail, "the tool-specific data stays the envelope's data")
    }

    @Test
    fun `the model sees the envelope with a null observation and no legacy prefix`() {
        val registry = ToolRegistry(listOf(tool))

        val result = registry.execute(
            ToolCall("c1", "notify_user", """{"text":"x","channel":"screen"}"""),
        )

        val payload = Json.parseToJsonElement(result.payload).jsonObject
        assertEquals(setOf("execution", "observation", "data"), payload.keys)
        val execution = payload.getValue("execution").jsonObject
        assertEquals("done", execution.getValue("outcome").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), execution.getValue("accepted"))
        assertEquals(JsonNull, payload.getValue("observation"))
        assertEquals("notified:1", payload.getValue("data").jsonPrimitive.content)
        assertFalse("Failed: " in result.payload, "no legacy prefix: ${result.payload}")
        assertFalse("Refused: " in result.payload, "no legacy prefix: ${result.payload}")
    }

    @Test
    fun `a refused call reaches the model with explicit refusal facts`() {
        // 0.3 Phase 2.2 Task 7 fix review: a bare `ActionResult(Refused, code)` let the registry project
        // code=null/accepted=null/applied=null/recoverable=false. That last value is a false fact - invalid arguments are
        // exactly what the model can correct, and nothing was dispatched. The tool must state the facts itself.
        val registry = ToolRegistry(listOf(tool))

        val result = registry.execute(
            ToolCall("c1", "notify_user", """{"text":"x","channel":"telepathy"}"""),
        )

        assertFalse(result.ok)
        assertEquals(ToolOutcome.Refused, result.execution.outcome)
        assertEquals("NOTIFY_ARGUMENTS_INVALID", result.execution.code)
        assertEquals(false, result.execution.accepted)
        assertEquals(false, result.execution.applied)
        assertNull(result.execution.verified)
        assertNull(result.execution.changed)
        assertTrue(result.execution.recoverable)
        assertNull(result.observation)
        assertEquals("NOTIFY_ARGUMENTS_INVALID", result.data)
        assertFalse("Refused: " in result.payload, "no legacy prefix: ${result.payload}")

        // The facts must reach the model on the wire too, not only the projected `ToolResult`.
        val payload = Json.parseToJsonElement(result.payload).jsonObject
        assertEquals(JsonNull, payload.getValue("observation"))
        val execution = payload.getValue("execution").jsonObject
        assertEquals("refused", execution.getValue("outcome").jsonPrimitive.content)
        assertEquals("NOTIFY_ARGUMENTS_INVALID", execution.getValue("code").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), execution.getValue("accepted"))
        assertEquals(JsonPrimitive(false), execution.getValue("applied"))
        assertEquals(JsonPrimitive(true), execution.getValue("recoverable"))
        assertEquals(JsonNull, execution.getValue("verified"))
        assertEquals(JsonNull, execution.getValue("changed"))
    }

    @Test
    fun `the result detail carries a sequence number and never the text`() {
        // The progress guard trips on three identical consecutive fingerprints, and the model may legitimately send
        // the same sentence twice. A per-call counter keeps the fingerprints distinct without carrying content.
        val recorder = NotifyUserTool(onNotify = { _, _ -> })

        val first = recorder.execute("notify_user", """{"text":"同一句","channel":"screen"}""")
        val second = recorder.execute("notify_user", """{"text":"同一句","channel":"screen"}""")

        assertEquals("notified:1", first.detail)
        assertEquals("notified:2", second.detail)
        assertTrue("同一句" !in first.detail)
    }

    @Test
    fun `screen asks for no speech and both asks for speech`() {
        assertEquals(null, (decideChannel(NotifyChannel.SCREEN) as NotifyDecision.Show).optionalSpeech)
        assertTrue((decideChannel(NotifyChannel.BOTH) as NotifyDecision.Show).optionalSpeech != null)
    }

    @Test
    fun `silent submits nothing at all`() {
        // §6.2: `silent` is not "displayed quietly", it is a decision not to notify - and it must be distinguishable
        // from "the model forgot", or the fallback would immediately contradict it.
        assertEquals(NotifyDecision.Suppress, decideChannel(NotifyChannel.SILENT))
    }

    @Test
    fun `voice asks for speech and records that the voice only path does not exist`() {
        // `OutputArbiter` always calls `device.show` for an accepted hint; there is no voice-without-screen path in
        // `core-contracts`, so `voice` behaves as `both` and the exposure is stated rather than hidden.
        val decision = decideChannel(NotifyChannel.VOICE) as NotifyDecision.Show

        assertTrue(decision.optionalSpeech != null)
        assertEquals("voice", NotifyChannel.VOICE.wireName)
    }

    @Test
    fun `a call with an unknown channel is refused rather than guessed at`() {
        val result = tool.execute("notify_user", """{"text":"x","channel":"telepathy"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
    }

    @Test
    fun `a call with no text is refused`() {
        assertEquals(ActionOutcome.Refused, tool.execute("notify_user", """{"channel":"screen"}""").outcome)
        assertEquals(ActionOutcome.Refused, tool.execute("notify_user", """{"text":"  ","channel":"screen"}""").outcome)
    }

    @Test
    fun `malformed arguments are refused rather than throwing into the loop`() {
        assertEquals(ActionOutcome.Refused, tool.execute("notify_user", "{ not json").outcome)
    }

    @Test
    fun `a notification is recorded whatever its channel`() {
        // Spec §6.1's own description: every notification is recorded to the context regardless of channel.
        val seen = mutableListOf<Pair<NotifyChannel, String>>()
        NotifyUserTool(onNotify = { channel, text -> seen += channel to text })
            .execute("notify_user", """{"text":"安静地说","channel":"silent"}""")

        assertEquals(1, seen.size)
        assertEquals(NotifyChannel.SILENT, seen.single().first)
    }

    @Test
    fun `the four channels are the four the spec names`() {
        assertEquals(
            listOf("screen", "voice", "both", "silent"),
            NotifyChannel.entries.map { it.wireName },
        )
    }

    @Test
    fun `DESCRIPTOR includes final parameter for task completion signal`() {
        val schema = tool.descriptors().single().parametersJson
        assertTrue(schema.contains("\"final\""), "schema must contain final field: $schema")
        assertTrue(schema.contains("\"boolean\""), "final must be boolean type: $schema")
        // final is optional (not in required), so existing callers are unaffected
        assertTrue(!schema.contains("\"required\":[\"text\",\"channel\",\"final\"]"),
            "final must not be required: $schema")
    }

    @Test
    fun `the description states final has no tool-side effect in this version`() {
        // execute() reads only text and channel. final is the model's own end-of-task declaration; the tool neither
        // changes the notification nor ends the run because of it, so the prose must not sell it as doing so.
        val description = tool.descriptors().single().description

        assertTrue(description.contains("final"), "the description must document final: $description")
        assertTrue(
            description.contains("不改变通知行为"),
            "final must not be described as changing the notification: $description",
        )
    }
}
