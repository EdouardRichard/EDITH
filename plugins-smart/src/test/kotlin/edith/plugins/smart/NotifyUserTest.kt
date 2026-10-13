package edith.plugins.smart

import edith.agent.SCOPE_TERMINAL
import edith.plugin.ActionOutcome
import edith.plugin.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
