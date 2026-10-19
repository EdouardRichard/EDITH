package com.sheen.rokid.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R51's two additive wire commands.
 *
 * `CONFIRM_STATE` (phone -> glasses) is a **level**: it says whether a confirmation question is up, carries no
 * sequence, and a repeat is therefore idempotent. `CONFIRM_DECISION` (glasses -> phone) is an **event**: it carries no
 * acknowledgement, because it settles a question that is already addressed by `requestId`.
 *
 * Both are additive on wire version 1. An older decoder must answer `null` for a command it does not know (safe
 * failure), and this decoder must answer `null` for a command from a newer build rather than guessing.
 */
class HintWireCodecConfirmTest {

    @Test
    fun `both confirmation commands round trip through the wire`() {
        val commands = listOf(
            HintCommand.ConfirmState(sessionId = "s-1", generation = 7, requestId = "c-1", active = true),
            HintCommand.ConfirmState(sessionId = "s-1", generation = 7, requestId = "c-1", active = false),
            HintCommand.ConfirmDecision(sessionId = "s-1", generation = 7, requestId = "c-1", approved = true),
            HintCommand.ConfirmDecision(sessionId = "s-1", generation = 7, requestId = "c-1", approved = false),
        )

        commands.forEach { command ->
            assertEquals(command, HintWireCodec.decode(HintWireCodec.encode(command)),
                "round trip failed for $command")
        }
    }

    @Test
    fun `the wire spelling and field order match the existing command shapes`() {
        val state = HintWireCodec.encode(HintCommand.ConfirmState("s-1", 7, "c-1", true))
        assertTrue(state.startsWith("{\"wireVersion\":1,\"command\":\"CONFIRM_STATE\","), state)
        assertTrue("\"active\":1" in state, "a boolean travels as 1/0, like BUSY: $state")
        assertTrue("\"sequence\"" !in state, "a confirmation level has no sequence: $state")
        val stateKeys = listOf("wireVersion", "command", "sessionId", "generation", "requestId", "active")
            .map { state.indexOf("\"$it\"") }
        assertEquals(stateKeys.sorted(), stateKeys, "the field order is part of the wire shape: $state")

        val decision = HintWireCodec.encode(HintCommand.ConfirmDecision("s-1", 7, "c-1", false))
        assertTrue(decision.startsWith("{\"wireVersion\":1,\"command\":\"CONFIRM_DECISION\","), decision)
        assertTrue("\"approved\":0" in decision, decision)
        assertTrue("\"sequence\"" !in decision, "a decision is an event with no sequence: $decision")
        assertTrue("\"ack\"" !in decision, "a decision is never acknowledged: $decision")
        val decisionKeys = listOf("wireVersion", "command", "sessionId", "generation", "requestId", "approved")
            .map { decision.indexOf("\"$it\"") }
        assertEquals(decisionKeys.sorted(), decisionKeys, "the field order is part of the wire shape: $decision")
    }

    @Test
    fun `an older decoder sees an unknown command and safely answers null`() {
        // This is exactly what a glasses build that predates this task does with CONFIRM_STATE.
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"SOME_FUTURE_COMMAND","sessionId":"s"}"""))
        // And a message this build cannot fully understand is refused rather than half-applied.
        assertNull(HintWireCodec.decode(
            """{"wireVersion":1,"command":"CONFIRM_STATE","sessionId":"s","generation":1,"requestId":"c"}""",
        ), "a CONFIRM_STATE without active is not a level")
        assertNull(HintWireCodec.decode(
            """{"wireVersion":1,"command":"CONFIRM_DECISION","sessionId":"s","generation":1,"requestId":"c"}""",
        ), "a CONFIRM_DECISION without approved is not an event")
        assertNull(HintWireCodec.decode("not json"))
        assertNull(HintWireCodec.decode(""))
    }

    @Test
    fun `the decision flag is strict and never guessed from a string or another number`() {
        fun decodeDecision(approved: String) = HintWireCodec.decode(
            """{"wireVersion":1,"command":"CONFIRM_DECISION","sessionId":"s","generation":1,"requestId":"c","approved":$approved}""",
        )
        fun decodeState(active: String) = HintWireCodec.decode(
            """{"wireVersion":1,"command":"CONFIRM_STATE","sessionId":"s","generation":1,"requestId":"c","active":$active}""",
        )

        assertEquals(HintCommand.ConfirmDecision("s", 1, "c", true), decodeDecision("1"))
        assertEquals(HintCommand.ConfirmDecision("s", 1, "c", false), decodeDecision("0"))
        assertNull(decodeDecision("2"))
        assertNull(decodeDecision("\"1\""))
        assertNull(decodeState("2"))
        assertNull(decodeState("\"true\""))
        assertNull(HintWireCodec.decode(
            """{"wireVersion":1,"command":"CONFIRM_STATE","sessionId":"","generation":1,"requestId":"c","active":1}""",
        ), "an empty session id is not a command that can be addressed")
        assertNull(HintWireCodec.decode(
            """{"wireVersion":1,"command":"CONFIRM_STATE","sessionId":"s","generation":1,"requestId":"  ","active":1}""",
        ), "a blank request id cannot address a question")
    }

    @Test
    fun `encoding refuses a blank identity instead of putting it on the wire`() {
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.ConfirmState("", 1, "c", true))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.ConfirmDecision("s", 1, " ", true))
        }
    }
}
