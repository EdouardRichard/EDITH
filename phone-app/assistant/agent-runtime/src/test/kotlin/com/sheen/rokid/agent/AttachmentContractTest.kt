package com.sheen.rokid.agent

import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 Task 1: the attachment contract's shape and its JSON backward compatibility.
 *
 * Images travel in two places: a tool result ([ModelHistoryItem.ToolOutput.images]) and a request-flow segment
 * ([Segment.images]). The tool-round JSON vocabulary is the one persisted/rendered representation either has, so a
 * round trip through it is what proves an image reference survives from a tool result to a model request — and that a
 * tool round stored before 0.25 (no `images` member) still decodes, with no images rather than a failed turn.
 */
class AttachmentContractTest {

    private val reference = ImageRef(
        artifactId = "img-20261019-0001",
        mime = "image/jpeg",
        width = 768,
        height = 1664,
        sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    )

    // ------------------------------------------------------------------ 1. contract defaults

    @Test fun `a tool output and a segment default to no images`() {
        val output = ModelHistoryItem.ToolOutput("call-1", "done")
        assertEquals(emptyList(), output.images)

        val segment = Segment(SegmentOrigin.TOOL_ROUND, trusted = true, text = "round")
        assertEquals(emptyList(), segment.images)
    }

    @Test fun `images ride on the tool output and the segment`() {
        val output = ModelHistoryItem.ToolOutput("call-1", "done", listOf(reference))
        assertEquals(listOf(reference), output.images)

        val segment = Segment(SegmentOrigin.TOOL_ROUND, trusted = true, text = "round", images = listOf(reference))
        assertEquals(listOf(reference), segment.images)
    }

    @Test fun `an action defaults to writable and can declare itself read only`() {
        val action = actionDescriptor()
        assertFalse(action.readOnly)

        assertTrue(action.copy(readOnly = true).readOnly)
    }

    @Test fun `an action still validates its existing fields`() {
        val failure = runCatching { actionDescriptor(name = " ") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "the existing require must still fire: $failure")
    }

    @Test fun `a tool output with images stays redacted in diagnostics`() {
        val output = ModelHistoryItem.ToolOutput("call-1", "private-observation", listOf(reference))
        val rendered = output.toString()
        assertFalse(rendered.contains("private-observation"))
        assertFalse(rendered.contains(reference.artifactId))
        assertFalse(rendered.contains(reference.sha256))
    }

    // ------------------------------------------------------------------ 2. tool-round JSON

    @Test fun `a tool output image survives the tool round json round trip`() {
        val round = outputJson(
            buildJsonObject {
                put("type", "output")
                put("callId", "call-1")
                put("output", "captured the screen")
                put(
                    "images",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("artifactId", reference.artifactId)
                                put("mime", reference.mime)
                                put("width", reference.width)
                                put("height", reference.height)
                                put("sha256", reference.sha256)
                            },
                        )
                    },
                )
            },
        )

        val sent = sendFrame(round)

        assertEquals(
            ModelHistoryItem.ToolOutput("call-1", "captured the screen", listOf(reference)),
            sent.history.last(),
        )
    }

    @Test fun `a tool round stored before the image contract decodes with no images`() {
        val round = outputJson(
            buildJsonObject {
                put("type", "output")
                put("callId", "call-1")
                put("output", "captured the screen")
            },
        )

        val sent = sendFrame(round)

        assertEquals(
            ModelHistoryItem.ToolOutput("call-1", "captured the screen"),
            sent.history.last(),
            "an old tool round must still decode, with an empty image list",
        )
        assertEquals(emptyList(), (sent.history.last() as ModelHistoryItem.ToolOutput).images)
    }

    // ------------------------------------------------------------------ helpers

    private fun actionDescriptor(name: String = "device.observe", readOnly: Boolean = false) = ActionDescriptor(
        name = name,
        title = "Observe",
        parametersJson = """{"type":"object"}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = true,
        reversible = true,
        scope = "device",
        readOnly = readOnly,
    )

    private fun outputJson(output: JsonObject): String =
        buildJsonArray { add(output) }.toString()

    /**
     * Sends [toolRound] through the production frame path and returns the request the gateway assembled.
     *
     * The flow mirrors a stored two-turn exchange: the older turn, then the tool round, then the pending user input.
     * `limitTokens` is raised so the trimmer cannot remove the round under test.
     */
    private fun sendFrame(toolRound: String): ModelRequest {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val profile = gatewayProfile()
        val client = FakeModelClient { _, listener ->
            listener(ModelStreamEvent.Terminal(TURN_ID, ModelEnd.COMPLETED, null, null))
        }
        val gateway = ModelClientGateway(
            client = client,
            profileLookup = { profile },
            clock = clock,
            scheduler = scheduler,
            envelopeFor = { requestId, start, end ->
                EventEnvelope(SessionId("session"), Generation(1), requestId, start, end)
            },
        )
        val flow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = "question", role = SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = "answer", role = SegmentRole.ASSISTANT),
            Segment(SegmentOrigin.TOOL_ROUND, trusted = true, text = toolRound),
            Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "question"),
        )
        val frame = RequestFrame(
            listOf(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "You are Edith.")),
            flow,
            emptyList(),
        )
        gateway.turn(
            GatewayTurn(
                turnId = TURN_ID,
                profileId = profile.id,
                instructions = "You are Edith.",
                history = emptyList(),
                tools = emptyList(),
                deadlineNanos = 1_000_000_000L,
                limitTokens = 1_000_000,
                frame = frame,
            ),
        )
        return client.requests.single()
    }

    private companion object {
        const val TURN_ID = "turn-7"
    }
}
