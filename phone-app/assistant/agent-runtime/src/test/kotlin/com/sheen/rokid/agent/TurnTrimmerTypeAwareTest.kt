package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ImageBudget
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 Task 13 / R68–R69: the type-aware cut ⓪ inserted before the fixed D97 order.
 *
 * The five behaviours the spec fixes:
 * - an old UI tree becomes a header (refs and a summary survive, the full text does not);
 * - only the latest K = 3 images stay whole, older ones become a text summary;
 * - a long non-last tool result is head+tail truncated and marked `truncated: true`;
 * - the last tool result is never trimmed by the context (the tool's own 16 KiB cap still applies);
 * - `Segment.images` cost is charged in the same unit `ContextBudgeter` uses.
 */
class TurnTrimmerTypeAwareTest {

    private fun sys(text: String = "sys") = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, text))
    private fun frame(vararg flow: Segment) = RequestFrame(sys(), flow.toList(), emptyList())
    private fun userTurn(text: String) = Segment(SegmentOrigin.TRANSCRIPT, true, text, SegmentRole.USER)
    private fun assistant(text: String) = Segment(SegmentOrigin.TRANSCRIPT, true, text, SegmentRole.ASSISTANT)
    private fun user(text: String) = Segment(SegmentOrigin.USER_INPUT, true, text)
    private fun runtime(text: String = "foreground=com.x revision=4") =
        Segment(SegmentOrigin.RUNTIME_STATE, false, text)

    private fun imageRef(n: Int) = ImageRef("img-$n", "image/jpeg", 100, 50, "sha-$n")

    private fun imageElement(n: Int) = buildJsonObject {
        put("artifactId", "img-$n")
        put("mime", "image/jpeg")
        put("width", 100)
        put("height", 50)
        put("sha256", "sha-$n")
    }

    private fun toolCall(callId: String = "c"): Segment = Segment(
        SegmentOrigin.TOOL_ROUND,
        true,
        buildJsonArray {
            add(buildJsonObject {
                put("type", "call")
                put("callId", callId)
                put("itemId", "item-$callId")
                put("name", "device")
                put("argsJson", "{}")
            })
        }.toString(),
    )

    private fun toolOutput(output: String, images: List<Int> = emptyList(), callId: String = "c"): Segment =
        Segment(
            SegmentOrigin.TOOL_ROUND,
            true,
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "output")
                    put("callId", callId)
                    put("output", output)
                    if (images.isNotEmpty()) {
                        put("images", buildJsonArray { images.forEach { add(imageElement(it)) } })
                    }
                })
            }.toString(),
        )

    private fun outputOf(segment: Segment): JsonObject =
        (Json.parseToJsonElement(segment.text) as JsonArray)[0] as JsonObject

    private fun outputText(segment: Segment): String =
        outputOf(segment).getValue("output").jsonPrimitive.content

    /** The envelope an output entry carries after the type-aware cut, parsed for member-level assertions. */
    private fun outputEnvelope(segment: Segment): JsonObject =
        Json.parseToJsonElement(outputText(segment)).jsonObject

    /** The compact screen a kept output still carries (the elided header when cut ⓪ rewrote it). */
    private fun observationScreen(segment: Segment): String? =
        ((outputEnvelope(segment)["observation"] as? JsonObject)?.get("screen") as? JsonPrimitive)?.content

    private fun outputImages(segment: Segment): JsonArray =
        outputOf(segment)["images"] as? JsonArray ?: JsonArray(emptyList())

    /**
     * The shape the loop really sends after Task 1/8: the unified `{execution, observation, data}` envelope, built by
     * the production encoder. The screen tree lives in `observation.screen`; `data` carries only tool-specific facts
     * (here the action/revision/app metadata the header may still name).
     */
    private fun screenObservation(rev: Int, vararg lines: String): String = ToolResultEnvelope.encode(
        ToolExecution(outcome = ToolOutcome.Done),
        ToolObservation(
            snapshotId = "snap-$rev",
            capturedAtEpochMillis = rev.toLong(),
            foregroundPackage = "com.example.app",
            screen = lines.joinToString("\n"),
            vision = ToolVision.NotRequested,
        ),
        """{"action":"observe","rev":$rev,"app":"com.example.app"}""",
    )

    @Test
    fun `a real encoded envelope is elided from observation screen even without data metadata`() {
        // The Task 8 contract: the screen tree is `observation.screen` (a string), not the pre-0.3 `data.screen` object.
        // The payload here is produced by the real encoder and its `data` deliberately names no action/rev/app, so only
        // the observation can make the elision fire.
        val payload = ToolResultEnvelope.encode(
            ToolExecution(outcome = ToolOutcome.Done),
            ToolObservation(
                foregroundPackage = "com.example.app",
                screen = "#n1 button Wi-Fi\n#n2 text hi",
                vision = ToolVision.NotRequested,
            ),
            """{"apps":[]}""",
        )
        val f = frame(
            userTurn("u"),
            assistant("a"),
            toolOutput(payload, callId = "c1"),
            toolOutput("fresh", callId = "c2"),
            user("now"),
        )
        val original = Json.parseToJsonElement(payload).jsonObject

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(1, report.elidedUiTrees)
        val kept = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }
        val header = observationScreen(kept[0]).orEmpty()
        assertTrue(header.startsWith("[screen observation elided:"), header)
        assertTrue(header.contains("#n1"), "the refs survive: $header")
        assertTrue(header.contains("lines=2"), "the header keeps the tree's line count: $header")
        assertFalse(header.contains("button Wi-Fi"), "the full tree does not survive: $header")
        // Task 8 fix review: ⓪ replaces only `observation.screen`; the rest of the envelope is still the tool's result.
        assertEquals(original["data"], outputEnvelope(kept[0])["data"], "tool data survives the screen elision")
        assertEquals(
            original["execution"],
            outputEnvelope(kept[0])["execution"],
            "execution facts survive the screen elision",
        )
        assertEquals("fresh", outputText(kept[1]), "the newest output is still untouched")
    }

    @Test
    fun `eliding an old screen preserves the execution facts and non-empty tool data`() {
        // Task 8 fix review: the pre-fix cut replaced the whole output with a header, so a shell/open result lost its
        // exit code / stdout / candidate list and its `execution` block. Only `observation.screen` may change.
        val payload = ToolResultEnvelope.encode(
            ToolExecution(
                outcome = ToolOutcome.Failed,
                code = "STATE_UNCHANGED",
                message = "the launch command succeeded but the request is not verified in front",
                recoverable = true,
                accepted = true,
                applied = null,
                verified = false,
            ),
            ToolObservation(
                foregroundPackage = "com.example.app",
                foregroundActivity = ".MainActivity",
                rootPackage = "com.example.app",
                coverage = "screen=ok",
                screen = "#n1 button \"旧\"\n#n2 text old",
                vision = ToolVision.Unavailable,
                visionReason = "no_source",
            ),
            """{"target":"com.example","component":"com.example/.Main","candidates":[{"label":"文件","packageName":"com.android.fileexplorer"}]}""",
        )
        val original = Json.parseToJsonElement(payload).jsonObject
        val f = frame(
            userTurn("u"),
            assistant("a"),
            toolOutput(payload, callId = "c1"),
            toolOutput("fresh", callId = "c2"),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(1, report.elidedUiTrees)
        val kept = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }.first()
        val keptEnvelope = outputEnvelope(kept)
        assertEquals(original["execution"], keptEnvelope["execution"], "execution facts are not collateral damage")
        assertEquals(original["data"], keptEnvelope["data"], "tool-specific data is not collateral damage")
        val observation = keptEnvelope["observation"] as JsonObject
        val originalObservation = original["observation"] as JsonObject
        assertEquals(originalObservation["foregroundPackage"], observation["foregroundPackage"])
        assertEquals(originalObservation["vision"], observation["vision"])
        val screen = observation.getValue("screen").jsonPrimitive.content
        assertTrue(screen.startsWith("[screen observation elided:"), screen)
        assertFalse(screen.contains("button \"旧\""), "only the tree is gone, not the envelope: $screen")
    }

    @Test
    fun `an old data screen object without an observation is not elided`() {
        // The documented non-production exception: the pre-0.3 `data.screen` shape exists only in a composition without a
        // perception port (production always injects one). It is deliberately not a UI tree this cut elides - the
        // production device tree lives in `observation.screen` and is covered above - so ⓪ stays effective where it counts.
        val legacy = buildJsonObject {
            put("execution", buildJsonObject { put("outcome", "done") })
            put("observation", kotlinx.serialization.json.JsonNull)
            put(
                "data",
                buildJsonObject {
                    put("screen", buildJsonObject { put("text", "#n1 button Wi-Fi") })
                },
            )
        }.toString()
        val f = frame(
            userTurn("u"),
            assistant("a"),
            toolOutput(legacy, callId = "c1"),
            toolOutput("fresh", callId = "c2"),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(0, report.elidedUiTrees)
        val kept = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }
        assertEquals(legacy, outputText(kept[0]), "a non-observation data screen is not a UI tree to elide")
    }

    @Test
    fun `only the newest of five screen observations stays whole and the four old ones become headers`() {
        val rounds = (1..5).map { toolOutput(screenObservation(it, "#n1 button Wi-Fi", "#n2 text hi"), callId = "c$it") }
        val f = frame(userTurn("u"), assistant("a"), *rounds.toTypedArray(), user("now"))

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(4, report.elidedUiTrees, "the four non-last observations are the type-aware cut")
        val keptOutputs = report.kept.conversationFlow
            .filter { it.origin == SegmentOrigin.TOOL_ROUND }
        assertEquals(5, keptOutputs.size)
        (0 until 4).forEach { index ->
            val screen = observationScreen(keptOutputs[index]).orEmpty()
            assertTrue(
                screen.startsWith("[screen observation elided:"),
                "old tree $index must be a header, was: $screen",
            )
            assertTrue(screen.contains("已压缩"), "header marks the compression: $screen")
            assertTrue(screen.contains("#n1"), "the refs survive the compression: $screen")
            assertTrue(!screen.contains("button Wi-Fi"), "the full text does not survive")
            // The envelope around the elided screen is still the device result: execution and data are untouched.
            assertEquals(
                """{"action":"observe","rev":${index + 1},"app":"com.example.app"}""",
                (outputEnvelope(keptOutputs[index])["data"] as JsonObject).toString(),
                "the tool data of old tree $index survives",
            )
        }
        assertEquals(
            screenObservation(5, "#n1 button Wi-Fi", "#n2 text hi"),
            outputText(keptOutputs[4]),
            "the newest observation is untouched",
        )
    }

    @Test
    fun `failure and uncertain images follow the same newest three rule`() {
        // Task 8: a failed/uncertain result's picture is an ordinary image reference on the tool output, so the budget
        // rule cannot special-case it out of retention.
        val rounds = (1..4).map { index ->
            val payload = ToolResultEnvelope.encode(
                ToolExecution(
                    outcome = if (index % 2 == 0) ToolOutcome.Failed else ToolOutcome.Uncertain,
                    code = if (index % 2 == 0) "STATE_UNCHANGED" else "ACTION_TIMEOUT_UNCERTAIN",
                    uncertain = index % 2 != 0,
                ),
                ToolObservation(vision = ToolVision.Unavailable, visionReason = "SCREENSHOT_FAILED"),
                """{"detail":"failed $index"}""",
            )
            toolOutput(payload, images = listOf(index), callId = "c$index")
        }
        val f = frame(
            userTurn("u"),
            assistant("a"),
            *rounds.toTypedArray(),
            toolOutput("fresh", callId = "c5"),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(1, report.elidedImages, "four failure pictures: the newest three stay whole")
        val kept = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }
        assertEquals(0, outputImages(kept[0]).size, "the oldest failure image is elided from the wire")
        assertTrue(
            outputText(kept[0]).contains("image img-1 image/jpeg 100x50 (elided)"),
            "the elided failure image stays visible as a count/summary: ${outputText(kept[0])}",
        )
        (1..3).forEach { index -> assertEquals(1, outputImages(kept[index]).size, "image ${index + 1} stays") }
    }

    @Test
    fun `five images keep the newest three whole and summarise the older two as text`() {
        val rounds = (1..5).map { toolOutput("shot $it", images = listOf(it), callId = "c$it") }
        val f = frame(userTurn("u"), assistant("a"), *rounds.toTypedArray(), user("now"))

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(2, report.elidedImages)
        val kept = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }
        for (index in 0 until 2) {
            assertEquals(0, outputImages(kept[index]).size, "image $index is no longer sent as bytes")
            assertTrue(
                outputText(kept[index]).contains("image img-${index + 1} image/jpeg 100x50 (elided)"),
                "the elided image must stay visible as text: ${outputText(kept[index])}",
            )
        }
        for (index in 2 until 5) {
            assertEquals(1, outputImages(kept[index]).size, "image ${index + 1} is one of the newest K = 3")
        }
    }

    @Test
    fun `the last tool result is never head and tail trimmed even when it is long`() {
        val long = "x".repeat(20_000)
        val f = frame(
            userTurn("u"),
            assistant("a"),
            toolOutput("short", callId = "c1"),
            toolOutput(long, callId = "c2"),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        val outputs = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }
        assertEquals(long, outputText(outputs.last()), "the newest tool result is exempt from the type-aware cut")
        assertEquals(0, report.elidedToolResults)
    }

    @Test
    fun `a long non-last tool result keeps its head and tail and is marked truncated`() {
        val long = "HEAD" + "x".repeat(20_000) + "TAIL"
        val f = frame(
            userTurn("u"),
            assistant("a"),
            toolOutput(long, callId = "c1"),
            toolOutput("fresh", callId = "c2"),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(1, report.elidedToolResults)
        val outputs = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.TOOL_ROUND }
        val trimmed = outputText(outputs[0])
        assertTrue(trimmed.length < long.length, "the old result must shrink: ${trimmed.length}")
        assertTrue(trimmed.startsWith("HEAD"), "the head survives: ${trimmed.take(40)}")
        assertTrue(trimmed.endsWith("TAIL"), "the tail survives: ${trimmed.takeLast(40)}")
        assertTrue(trimmed.contains("truncated"), "the marker is visible: $trimmed")
        assertEquals(JsonPrimitive(true), outputOf(outputs[0])["truncated"])
        assertEquals("fresh", outputText(outputs[1]), "the last result may not be touched")
    }

    @Test
    fun `segment images are charged at ImageBudget units against the same total`() {
        val withImages = frame(
            Segment(SegmentOrigin.PERCEPTION, false, "p", images = listOf(imageRef(1), imageRef(2))),
            user("now"),
        )
        val expected = FIXED_OVERHEAD +
            tokenUnitsPlusOverhead(withImages.systemText) +
            withImages.conversationFlow.sumOf { segment ->
                tokenUnitsPlusOverhead(segment.text) +
                    (segment.images.size * ImageBudget.UNITS_PER_IMAGE).toInt()
            }

        // Token-unit price: 32(fixed) + (1 + 32)(sys "sys") + (1 + 32)("p") + (2 × 1024)(two images) + (1 + 32)("now")
        // = **2179**; the limit below is that exact price, so the image units are really part of the total.
        assertEquals(expected, 32 + 33 + 33 + 2 * 1024 + 33, "text cost in token units, images priced at 1024 units each")
        assertEquals(false, TurnTrimmer().trim(withImages, expected).compressionOwed)
        assertEquals(
            true,
            TurnTrimmer().trim(withImages, expected - 1).compressionOwed,
            "the image units are really part of the total, not decoration",
        )
    }

    @Test
    fun `runtime state is still counted but never evicted by the type-aware pass`() {
        val f = frame(
            userTurn("u1"),
            assistant("a1"),
            toolOutput(screenObservation(1, "#n1 button")),
            runtime(),
            user("now"),
        )
        // One unit below the frame's own price forces cut ①: the whole turn group (u1, a1, the tool round) leaves while
        // the request-scoped `RUNTIME_STATE` is protected. The price is computed, not written as a literal, so a payload
        // shape change (Task 8's real envelope) cannot silently move the boundary being tested.
        val total = FIXED_OVERHEAD +
            tokenUnitsPlusOverhead(f.systemText) +
            f.conversationFlow.sumOf { tokenUnitsPlusOverhead(it.text) }
        val report = TurnTrimmer().trim(f, limitTokens = total - 1)

        assertEquals(1, report.droppedTurns)
        assertTrue(
            report.kept.conversationFlow.any { it.origin == SegmentOrigin.RUNTIME_STATE },
            "runtime state must survive turn eviction: ${report.kept.conversationFlow}",
        )
        assertEquals(SegmentOrigin.USER_INPUT, report.kept.conversationFlow.last().origin)
        val keptTotal = FIXED_OVERHEAD +
            tokenUnitsPlusOverhead(report.kept.systemText) +
            report.kept.conversationFlow.sumOf { tokenUnitsPlusOverhead(it.text) }
        assertEquals(
            FIXED_OVERHEAD + tokenUnitsPlusOverhead("sys") + tokenUnitsPlusOverhead(runtime().text) +
                tokenUnitsPlusOverhead("now"),
            keptTotal,
            "the kept frame is the system slot, the protected runtime line and the current input",
        )
        assertFalse(report.compressionOwed, "the kept frame fits the limit")
    }

    @Test
    fun `a CJK only transcript is trimmed against token units, not byte size`() {
        val cjk = "你好".repeat(100)
        val f = frame(userTurn(cjk), assistant("好的"), user("现在"))
        // Token price: 32 + (1 + 32) + (200 + 32) + (2 + 32) + (2 + 32) = **365**, inside 400.
        // Byte price:  32 +    35 +   (600 + 32) +   (6 + 32) +   (6 + 32) = **775**, over 400.
        // A byte-priced trimmer therefore evicts the whole turn here and reports droppedTurns = 1; the token-priced
        // one keeps it. This is the discriminating assertion for the unit migration.
        val report = TurnTrimmer().trim(f, limitTokens = 400)

        assertEquals(0, report.droppedTurns)
        assertTrue(report.kept.conversationFlow.any { it.text == cjk }, "the CJK turn fits in token units")
        assertTrue(report.kept.conversationFlow.any { it.origin == SegmentOrigin.USER_INPUT })
        assertEquals(false, report.compressionOwed)
    }

    @Test
    fun `the type-aware counts are exposed without any body text`() {
        val f = frame(
            userTurn("u"),
            assistant("a"),
            toolOutput(screenObservation(1, "#n1 button")),
            toolOutput("x".repeat(20_000)),
            toolOutput(longImageOutput(), images = (1..4).toList(), callId = "c4"),
            user("now"),
        )

        val counts = TurnTrimmer().trim(f, limitTokens = 1_000_000).counts

        assertEquals(1, counts.elidedUiTrees)
        assertEquals(1, counts.elidedImages, "four images: the newest three stay whole")
        assertEquals(1, counts.elidedToolResults)
        // `TrimCounts.toString()` contains counts only, never a surviving body.
        assertTrue(!counts.toString().contains("button"), counts.toString())
    }

    private fun longImageOutput(): String = "captured"
}
