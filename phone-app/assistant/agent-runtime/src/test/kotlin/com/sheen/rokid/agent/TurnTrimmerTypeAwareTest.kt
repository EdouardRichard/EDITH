package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ImageBudget
import com.sheen.rokid.plugin.ImageRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun outputImages(segment: Segment): JsonArray =
        outputOf(segment)["images"] as? JsonArray ?: JsonArray(emptyList())

    /** The shape `DeviceTool` actually returns: a device envelope whose `data.screen` is the rendered tree. */
    private fun screenObservation(rev: Int, vararg lines: String): String = buildJsonObject {
        put("ok", true)
        put("tool", "device")
        put("action", "observe")
        put("data", buildJsonObject {
            put("screen", buildJsonObject {
                put("rev", rev)
                put("app", "com.example.app")
                put("ime", false)
                put("truncated", false)
                put("changes", buildJsonObject {
                    put("mode", "full")
                    put("added", buildJsonArray { })
                    put("updated", buildJsonArray { })
                    put("removed", buildJsonArray { })
                })
                put("text", lines.joinToString("\n"))
            })
        })
    }.toString()

    @Test
    fun `only the newest of five screen observations stays whole and the four old ones become headers`() {
        val rounds = (1..5).map { toolOutput(screenObservation(it, "#n1 button Wi-Fi", "#n2 text hi"), callId = "c$it") }
        val f = frame(userTurn("u"), assistant("a"), *rounds.toTypedArray(), user("now"))

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000)

        assertEquals(4, report.elidedUiTrees, "the four non-last observations are the type-aware cut")
        val keptOutputs = report.kept.conversationFlow
            .filter { it.origin == SegmentOrigin.TOOL_ROUND }
            .map(::outputText)
        assertEquals(5, keptOutputs.size)
        (0 until 4).forEach { index ->
            assertTrue(
                keptOutputs[index].startsWith("[screen observation elided:"),
                "old tree $index must be a header, was: ${keptOutputs[index]}",
            )
            assertTrue(keptOutputs[index].contains("已压缩"), "header marks the compression: ${keptOutputs[index]}")
            assertTrue(keptOutputs[index].contains("#n1"), "the refs survive the compression: ${keptOutputs[index]}")
            assertTrue(!keptOutputs[index].contains("button Wi-Fi"), "the full text does not survive")
        }
        assertEquals(
            screenObservation(5, "#n1 button Wi-Fi", "#n2 text hi"),
            keptOutputs[4],
            "the newest observation is untouched",
        )
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
        // Token-unit total = 32(fixed) + 33(sys) + 33(u1) + 33(a1) + 106(the observation JSON: 74 tokens + 32)
        // + 39(runtime: 7 tokens + 32) + 33(now) = **309**. The limit is below that, so cut ① removes the turn
        // (66 units) and leaves 243 > 220; the runtime line is request metadata and stays regardless.
        val report = TurnTrimmer().trim(f, limitTokens = 220)

        assertEquals(1, report.droppedTurns)
        assertTrue(
            report.kept.conversationFlow.any { it.origin == SegmentOrigin.RUNTIME_STATE },
            "runtime state must survive turn eviction: ${report.kept.conversationFlow}",
        )
        assertEquals(SegmentOrigin.USER_INPUT, report.kept.conversationFlow.last().origin)
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
