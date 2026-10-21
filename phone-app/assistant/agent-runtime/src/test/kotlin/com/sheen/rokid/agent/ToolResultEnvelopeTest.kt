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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the canonical model-facing tool result envelope: exactly `{execution, observation, data}`.
 *
 * The expected strings are written out in full (not assembled by the production serializer) so the contract is the
 * test, not the implementation: key order, every execution field, the tri-state nulls, and the lowercase wire tokens.
 */
class ToolResultEnvelopeTest {

    /** The exact literal every all-default `done` execution produces, up to the `data` member's value. */
    private val donePrefix =
        """{"execution":{"outcome":"done","code":null,"message":null,"recoverable":false,"accepted":null,""" +
            """"applied":null,"verified":null,"changed":null,"uncertain":false,"partial":false},"observation":null,""" +
            """"data":"""

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
    fun `valid json scalar data is embedded as that scalar not as a string`() {
        // A scalar is valid JSON, so the strict parser must keep its type. The rule is pinned deliberately: object and
        // array payloads are the normal tool output, but a bare `42`/`true`/`null` must not silently become text.
        val cases = mapOf(
            "42" to """${donePrefix}42}""",
            "true" to """${donePrefix}true}""",
            "null" to """${donePrefix}null}""",
        )
        cases.forEach { (data, expected) ->
            val root = Json.parseToJsonElement(
                ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Done), observation = null, data),
            ).jsonObject
            assertFalse(root.getValue("data").jsonPrimitive.isString, "data <$data> must stay a JSON scalar")
            assertEquals(expected, ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Done), null, data))
        }
    }

    @Test
    fun `an empty observation is still a real observation with every field`() {
        // An attempted capture with no screen/foreground text is not the same fact as "no observation": the object must
        // exist and every field must appear, so the model never infers a field from absence.
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(outcome = ToolOutcome.Done),
            observation = ToolObservation(),
            data = "{}",
        )

        assertEquals(
            """{"execution":{"outcome":"done","code":null,"message":null,"recoverable":false,"accepted":null,""" +
                """"applied":null,"verified":null,"changed":null,"uncertain":false,"partial":false},""" +
                """"observation":{"snapshotId":null,"capturedAtEpochMillis":null,"foregroundPackage":null,""" +
                """"foregroundActivity":null,"rootPackage":null,"coverage":null,"screen":null,"truncated":false,""" +
                """"vision":"not_requested","visionReason":null},"data":{}}""",
            encoded,
        )
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(listOf("execution", "observation", "data"), root.keys.toList())
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
        assertEquals("not_requested", root.getValue("observation").jsonObject.getValue("vision").jsonPrimitive.content)
    }

    @Test
    fun `quotes backslashes newlines and tabs are escaped in data and message`() {
        val data = "line \"one\"\nline\\two\tend"
        val message = "say \"no\"\nnow"
        val encoded = ToolResultEnvelope.encode(
            execution = ToolExecution(outcome = ToolOutcome.Failed, message = message),
            observation = null,
            data = data,
        )

        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(data, root.getValue("data").jsonPrimitive.content, "the escaped string must round-trip exactly")
        assertEquals(
            message,
            root.getValue("execution").jsonObject.getValue("message").jsonPrimitive.content,
        )
        assertFalse(encoded.contains('\n'), "a raw newline must never reach the wire: $encoded")
        assertFalse(encoded.contains('\t'), "a raw tab must never reach the wire: $encoded")
        assertTrue(encoded.contains("\\n"), "the newline must be escaped: $encoded")
        assertTrue(encoded.contains("\\t"), "the tab must be escaped: $encoded")
        assertTrue(encoded.contains("\\\""), "a quote must be escaped: $encoded")
        assertTrue(encoded.contains("\\\\"), "a backslash must be escaped: $encoded")
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

    // ---------------------------------------------------------------- Phase 3 Task 4: content-free shape digest

    /**
     * P3-9 (spec §7.2): the digest describes the envelope **shape**, never its content.
     *
     * Two payloads whose string and number values differ, including a nested array, must hash identically; a changed
     * boolean is a different shape (booleans are preserved, not masked). This is what lets the device
     * `P3_TOOLRESULT shape=` line prove the runtime payload is an envelope without logging one byte of its values.
     */
    @Test
    fun `shape digest ignores string and number values but keeps booleans nulls and keys`() {
        val first = """{"name":"one","count":1,"ratio":1.5,"enabled":true,"absent":null,"items":[{"label":"a","n":2}]}"""
        val second = """{"name":"two","count":999,"ratio":-0.25,"enabled":true,"absent":null,"items":[{"label":"b","n":0}]}"""
        val booleanChanged = first.replace(""""enabled":true""", """"enabled":false""")

        val digest = ToolResultEnvelope.shapeDigest(first)
        assertNotNull(digest)
        assertEquals(digest, ToolResultEnvelope.shapeDigest(second), "only values differ, so the shapes are identical")
        assertNotEquals(digest, ToolResultEnvelope.shapeDigest(booleanChanged), "booleans are shape, not content")
    }

    @Test
    fun `shape digest differs for different keys and nullness`() {
        val base = ToolResultEnvelope.shapeDigest("""{"a":"x","b":null}""")
        assertNotNull(base)
        assertNotEquals(base, ToolResultEnvelope.shapeDigest("""{"a":"x","c":null}"""), "a renamed key is a new shape")
        assertNotEquals(base, ToolResultEnvelope.shapeDigest("""{"a":"x","b":false}"""), "null -> false is a new shape")
        assertNotEquals(base, ToolResultEnvelope.shapeDigest("""{"b":null,"a":"x"}"""), "key order is part of the shape")
    }

    @Test
    fun `shape digest is null for a payload that is not json`() {
        assertNull(ToolResultEnvelope.shapeDigest("state unchanged"))
        assertNull(ToolResultEnvelope.shapeDigest("{not json"))
        assertNull(ToolResultEnvelope.shapeDigest(""))
    }

    @Test
    fun `shape digest is the first eight lowercase hex characters of sha256`() {
        val digest = requireNotNull(ToolResultEnvelope.shapeDigest("""{"a":1}"""))

        assertEquals(8, digest.length)
        assertTrue(Regex("[0-9a-f]{8}").matches(digest), digest)
    }

    /**
     * The four envelope shapes the device acceptance compares against (spec §7.2): done, failed, uncertain and a pure
     * tool. The constants were computed independently from the masked payload (all strings -> `""`, all numbers -> `0`,
     * booleans/null kept, compact JSON), so they pin the algorithm rather than echoing the implementation.
     */
    @Test
    fun `shape digest pins the done failed uncertain and pure tool shapes`() {
        assertEquals(
            "e63adc62",
            ToolResultEnvelope.shapeDigest(
                ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Done), observation = null, data = "{}"),
            ),
        )
        assertEquals(
            "494536a3",
            ToolResultEnvelope.shapeDigest(
                ToolResultEnvelope.encode(
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
                ),
            ),
        )
        assertEquals(
            "7726d5a3",
            ToolResultEnvelope.shapeDigest(
                ToolResultEnvelope.encode(
                    ToolExecution(outcome = ToolOutcome.Uncertain, uncertain = true),
                    observation = null,
                    data = "{}",
                ),
            ),
        )
        assertEquals(
            "18719ed7",
            ToolResultEnvelope.shapeDigest(
                ToolResultEnvelope.encode(
                    ToolExecution(
                        outcome = ToolOutcome.Done,
                        accepted = true,
                        applied = true,
                        changed = false,
                    ),
                    observation = null,
                    data = """{"delivered":true}""",
                ),
            ),
        )
    }
}
