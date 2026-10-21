package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the canonical model-facing tool result envelope: exactly `{execution, observation, data}`.
 *
 * The expected strings are written out in full (not assembled by the production serializer) so the contract is the
 * test, not the implementation: key order, every execution field, the tri-state nulls, and the lowercase wire tokens.
 */
class ToolResultEnvelopeTest {

    @Test
    fun `done result with observation and object data is encoded exactly`() {
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(
                outcome = ToolOutcome.Done,
                code = "OK",
                message = "opened",
                recoverable = false,
                accepted = true,
                applied = true,
                verified = true,
                changed = true,
            ),
            observation = ToolObservation(
                snapshotId = "snap-1",
                capturedAtEpochMillis = 1_700_000_000_000,
                foregroundPackage = "com.example.app",
                foregroundActivity = ".MainActivity",
                rootPackage = "com.example.app",
                coverage = "screen=ok vision=ok",
                screen = "#1 Button",
                truncated = false,
                vision = ToolVision.Ok,
            ),
            data = """{"apps":[]}""",
        )

        assertEquals(
            """{"execution":{"outcome":"done","code":"OK","message":"opened","recoverable":false,"accepted":true,"applied":true,"verified":true,"changed":true,"uncertain":false,"partial":false},"observation":{"snapshotId":"snap-1","capturedAtEpochMillis":1700000000000,"foregroundPackage":"com.example.app","foregroundActivity":".MainActivity","rootPackage":"com.example.app","coverage":"screen=ok vision=ok","screen":"#1 Button","truncated":false,"vision":"ok","visionReason":null},"data":{"apps":[]}}""",
            encoded,
        )
    }

    @Test
    fun `failed result keeps unknown tri-state values as json null`() {
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(
                outcome = ToolOutcome.Failed,
                code = "STATE_UNCHANGED",
                message = "launch not verified",
                recoverable = true,
                accepted = true,
                applied = null,
                verified = false,
                changed = null,
                uncertain = true,
            ),
            observation = null,
            data = """{"detail":"foreground still launcher"}""",
        )

        assertEquals(
            """{"execution":{"outcome":"failed","code":"STATE_UNCHANGED","message":"launch not verified","recoverable":true,"accepted":true,"applied":null,"verified":false,"changed":null,"uncertain":true,"partial":false},"observation":null,"data":{"detail":"foreground still launcher"}}""",
            encoded,
        )
    }

    @Test
    fun `pure tool result keeps observation null`() {
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(
                outcome = ToolOutcome.Done,
                accepted = true,
                applied = true,
                changed = false,
            ),
            observation = null,
            data = """{"delivered":true}""",
        )

        assertEquals(
            """{"execution":{"outcome":"done","code":null,"message":null,"recoverable":false,"accepted":true,"applied":true,"verified":null,"changed":false,"uncertain":false,"partial":false},"observation":null,"data":{"delivered":true}}""",
            encoded,
        )
    }

    @Test
    fun `non-json data is encoded as a json string`() {
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(outcome = ToolOutcome.Failed),
            observation = null,
            data = "state unchanged",
        )

        assertEquals(
            """{"execution":{"outcome":"failed","code":null,"message":null,"recoverable":false,"accepted":null,"applied":null,"verified":null,"changed":null,"uncertain":false,"partial":false},"observation":null,"data":"state unchanged"}""",
            encoded,
        )
    }

    @Test
    fun `malformed json-looking data is encoded as a json string`() {
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(outcome = ToolOutcome.Failed),
            observation = null,
            data = "{not json",
        )

        assertEquals(
            """{"execution":{"outcome":"failed","code":null,"message":null,"recoverable":false,"accepted":null,"applied":null,"verified":null,"changed":null,"uncertain":false,"partial":false},"observation":null,"data":"{not json"}""",
            encoded,
        )
    }

    @Test
    fun `top-level keys and every field order are stable across calls`() {
        val execution = ToolExecution(outcome = ToolOutcome.Uncertain, uncertain = true)
        val observation = ToolObservation(vision = ToolVision.Unavailable, visionReason = "vision_disabled")

        val first = ToolResultEnvelope.encode(execution, observation, """{"x":1}""")
        val second = ToolResultEnvelope.encode(execution, observation, """{"x":1}""")

        assertEquals(first, second)
        val root = Json.parseToJsonElement(first) as JsonObject
        assertEquals(listOf("execution", "observation", "data"), root.keys.toList())
        assertEquals(
            listOf(
                "outcome",
                "code",
                "message",
                "recoverable",
                "accepted",
                "applied",
                "verified",
                "changed",
                "uncertain",
                "partial",
            ),
            root.getValue("execution").jsonObject.keys.toList(),
        )
        assertEquals(
            listOf(
                "snapshotId",
                "capturedAtEpochMillis",
                "foregroundPackage",
                "foregroundActivity",
                "rootPackage",
                "coverage",
                "screen",
                "truncated",
                "vision",
                "visionReason",
            ),
            root.getValue("observation").jsonObject.keys.toList(),
        )
    }

    @Test
    fun `outcome and vision use the lowercase wire tokens`() {
        val outcomes = ToolOutcome.entries.map { outcome ->
            val root = Json.parseToJsonElement(
                ToolResultEnvelope.encode(ToolExecution(outcome), observation = null, data = "{}"),
            ) as JsonObject
            root.getValue("execution").jsonObject.getValue("outcome").jsonPrimitive.content
        }
        val visions = ToolVision.entries.map { vision ->
            val root = Json.parseToJsonElement(
                ToolResultEnvelope.encode(
                    ToolExecution(ToolOutcome.Done),
                    observation = ToolObservation(vision = vision),
                    data = "{}",
                ),
            ) as JsonObject
            root.getValue("observation").jsonObject.getValue("vision").jsonPrimitive.content
        }

        assertEquals(listOf("done", "refused", "failed", "timeout", "uncertain", "cancelled"), outcomes)
        assertEquals(listOf("ok", "disabled", "unavailable", "not_requested"), visions)
    }
}
