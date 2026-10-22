package com.sheen.rokid.output

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T18 versioned hint wire (wire version 1).
 *
 * Commands are `HELLO/PING/PONG/SYNC/SHOW/CLEAR/ACK/SAID/BUSY`, plus 0.23 P5's glasses-to-phone `STOP` and 0.24's
 * official-conversation commands (`STATE/IDLE/ASR/ANSWER/TOOL/CLOSE/PIN/PANEL_OPEN/PANEL_SHOWN/UI_READY/UI_CLOSED`).
 * The phone and glasses clock domains are distinguished explicitly in the field names (`phoneSentNanos` is a phone
 * instant, `validUntilGlassNanos` is a glasses instant) so a raw phone nanosecond can never be read as a glasses clock.
 *
 * The codec is deliberately strict on input: it is the boundary that a hostile or buggy peer reaches, and the
 * SDK promises nothing about message order, size or duplication.
 */
class HintWireCodecTest {
    @Test fun everyCommandRoundTripsThroughTheWire() {
        val commands = listOf(
            HintCommand.Hello(sessionId = "s-1", generation = 1),
            HintCommand.Ping(syncId = "sync-1", p0 = 100),
            HintCommand.Pong(syncId = "sync-1", p0 = 100, g1 = 200, g2 = 300),
            HintCommand.Sync(syncId = "sync-1", offsetLowerNanos = -5, offsetUpperNanos = 7,
                validUntilGlassNanos = 9_000),
            HintCommand.Show(sessionId = "s-1", generation = 1, sequence = 4, hintId = "h-9", revision = 2,
                syncId = "sync-1", phoneSentNanos = 1_000, remainingTtlMillis = 1_500, text = "先讲结论"),
            HintCommand.Clear(sessionId = "s-1", generation = 1, sequence = 5),
            HintCommand.Ack(sessionId = "s-1", generation = 1, sequence = 4, hintId = "h-9", revision = 2,
                status = com.sheen.rokid.contracts.OutputStatus.Displayed),
            HintCommand.Said(sessionId = "s-1", generation = 1, sequence = 6,
                route = com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, entryId = "e-1", text = "我待会儿要开会"),
            HintCommand.Busy(sessionId = "s-1", generation = 1,
                route = com.sheen.rokid.contracts.AssistantRoute.WAKE, busy = true),
            HintCommand.Stop(sessionId = "s-1", generation = 7),
        )
        for (command in commands) {
            val json = HintWireCodec.encode(command)
            assertEquals(command, HintWireCodec.decode(json), "round trip failed for ${command::class.simpleName}")
        }
    }

    @Test fun theWireVersionIsPresentAndChecked() {
        val json = HintWireCodec.encode(HintCommand.Hello("s", 1))
        assertTrue(json.contains("\"wireVersion\":1"), "wire version must be explicit: $json")
        assertNull(HintWireCodec.decode(json.replace("\"wireVersion\":1", "\"wireVersion\":2")),
            "a different wire version must be refused, not guessed")
    }

    @Test fun clockDomainsAreNamedSoTheyCannotBeConfused() {
        val show = HintWireCodec.encode(HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, "t"))
        assertTrue(show.contains("\"phoneSentNanos\""), "the phone instant must be name-distinguished: $show")
        val sync = HintWireCodec.encode(HintCommand.Sync("sync", -5, 7, 9_000))
        assertTrue(sync.contains("\"validUntilGlassNanos\""), "the glasses instant must be name-distinguished: $sync")
    }

    @Test fun malformedInputIsRefusedRatherThanThrowing() {
        assertNull(HintWireCodec.decode(""))
        assertNull(HintWireCodec.decode("not json"))
        assertNull(HintWireCodec.decode("{}"))
        assertNull(HintWireCodec.decode("""{"wireVersion":1}"""))
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"NOPE"}"""))
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"SHOW","sessionId":"s"}"""),
            "a SHOW missing required fields must not become a partial command")
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"SHOW","sessionId":"s","generation":"x"}"""),
            "a wrongly typed field must be refused")
    }

    @Test fun anOversizedMessageIsRefusedBeforeParsing() {
        val huge = "{\"wireVersion\":1,\"command\":\"HELLO\",\"sessionId\":\"" + "x".repeat(70_000) + "\",\"generation\":1}"
        assertTrue(huge.length > HintWireCodec.MAX_MESSAGE_CHARS)
        assertNull(HintWireCodec.decode(huge), "a message over the bound must be refused")
    }

    @Test fun showTextIsBoundedToTheControlLength() {
        val longest = "字".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS)
        val command = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, longest)
        assertEquals(command, HintWireCodec.decode(HintWireCodec.encode(command)))

        val tooLong = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500,
            "字".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS + 1))
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(tooLong) }
    }

    @Test fun surrogatePairsCountAsOneCodepointNotTwoCodeUnits() {
        // A run of emoji is one UTF-16 surrogate pair per character, so `MAX_TEXT_CODEPOINTS` of them are twice that
        // many code units.
        val text = "🙂".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS)
        assertEquals(HintWireCodec.MAX_TEXT_CODEPOINTS * 2, text.length,
            "the fixture must really be surrogate pairs or this test proves nothing")
        // Codepoint counting accepts exactly the bound; the mutation check below proves the bound is enforced.
        val command = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, text)
        assertEquals(command, HintWireCodec.decode(HintWireCodec.encode(command)))
    }

    @Test fun aPayloadOverTheControlLengthIsRefusedOnBothSides() {
        // The SHOW text is the only free-form field, so this is the injection point that matters.
        val overBound = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500,
            "🙂".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS + 1))
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(overBound) }
        // And a peer that skips its own validation cannot get it through ours either.
        val smuggled = HintWireCodec.encode(
            HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, "x".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS)))
            .replace("\"text\":\"" + "x".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS) + "\"",
                "\"text\":\"" + "x".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS + 1) + "\"")
        assertNull(HintWireCodec.decode(smuggled), "decode must apply the bound too, not trust the sender")
    }

    @Test fun statusIsCarriedAsItsContractName() {
        for (status in com.sheen.rokid.contracts.OutputStatus.entries) {
            val json = HintWireCodec.encode(HintCommand.Ack("s", 1, 1, "h", 1, status))
            assertTrue(json.contains("\"status\":\"$status\""), "expected the contract name for $status: $json")
            assertEquals(status, (HintWireCodec.decode(json) as HintCommand.Ack).status)
        }
    }

    @Test fun numericIdentifiersSurviveTheWireExactly() {
        // Long identifiers and instants must not be truncated or turned into doubles.
        val command = HintCommand.Sync("s", Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, Long.MAX_VALUE)
        assertEquals(command, HintWireCodec.decode(HintWireCodec.encode(command)))
    }

    @Test fun textWithQuotesAndNewlinesSurvivesUnchanged() {
        val tricky = "a\"b\\c\nd\te"
        val command = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, tricky)
        assertEquals(tricky, (HintWireCodec.decode(HintWireCodec.encode(command)) as HintCommand.Show).text)
    }

    @Test fun aBlankSessionIdIsRefused() {
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(HintCommand.Hello(" ", 1)) }
    }

    // ---------------------------------------------------------------- Phase C: route, SAID, BUSY

    @Test fun stopRoundTripsOnWireVersionOne() {
        // 0.23 P5 (U11, D134): the STOP command is additive; wireVersion stays 1 and there is no sequence/ACK.
        val json = HintWireCodec.encode(HintCommand.Stop("s-1", 7))
        assertEquals("""{"wireVersion":1,"command":"STOP","sessionId":"s-1","generation":7}""", json)
        assertEquals(HintCommand.Stop("s-1", 7), HintWireCodec.decode(json))
    }

    @Test fun aStopMissingItsGenerationIsRefused() {
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"STOP","sessionId":"s-1"}"""))
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"STOP","generation":7}"""))
    }

    @Test fun aStopWithABlankSessionIsRefusedOnEncode() {
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(HintCommand.Stop("  ", 7)) }
    }

    @Test fun anUnknownCommandStaysNull() {
        // The mixed-build safety net: an old peer must not throw on a command it does not know, and a new peer must
        // not guess at one it was never taught.
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"END","sessionId":"s","generation":1}"""))
        assertNull(HintWireCodec.decode("""{"wireVersion":1,"command":"STOPP","sessionId":"s","generation":1}"""))
    }

    @Test fun theShowCarriesItsRouteAndTheOldShapeStillReadsAsWake() {
        val command = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, "t",
            route = com.sheen.rokid.contracts.AssistantRoute.PROACTIVE)
        val json = HintWireCodec.encode(command)
        assertTrue(json.contains("\"route\":\"PROACTIVE\""), "the route must travel with the hint: $json")
        assertEquals(command, HintWireCodec.decode(json))

        // A peer built before the route existed sends no such field. That message is a wake hint — reading it as
        // anything else would move a pre-Phase-C hint into a conversation the wearer may not be looking at.
        val withoutRoute = json.replace(",\"route\":\"PROACTIVE\"", "")
        assertEquals(com.sheen.rokid.contracts.AssistantRoute.WAKE,
            (HintWireCodec.decode(withoutRoute) as HintCommand.Show).route)
    }

    @Test fun aShowWithARouteThisBuildDoesNotKnowIsRefused() {
        val json = HintWireCodec.encode(HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, "t"))
            .replace("\"route\":\"WAKE\"", "\"route\":\"ELSEWHERE\"")
        assertNull(HintWireCodec.decode(json), "an unknown route must be refused, not defaulted")
    }

    @Test fun aSaidRoundTripsAndCarriesNoClockFields() {
        val command = HintCommand.Said("s", 2, 7, com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, "e-1", "帮我看下日程")
        val json = HintWireCodec.encode(command)
        assertEquals(command, HintWireCodec.decode(json))
        assertTrue(json.contains("\"route\":\"PROACTIVE\""), json)
        // An utterance has no deadline: it is a record of what was said, and the glasses must not expire it.
        assertFalse(json.contains("syncId"), "SAID must not carry clock fields: $json")
        assertFalse(json.contains("remainingTtlMillis"), "SAID must not carry a lifetime: $json")
    }

    @Test fun aBusyRoundTripsWithAnIntegerFlag() {
        // The flat reader carries strings and integers only, so the flag is 1/0 and never `true`/`false`.
        val json = HintWireCodec.encode(HintCommand.Busy("s", 2, com.sheen.rokid.contracts.AssistantRoute.WAKE, busy = true))
        assertTrue(json.contains("\"busy\":1"), "the busy flag must be an integer: $json")
        assertEquals(true, (HintWireCodec.decode(json) as HintCommand.Busy).busy)
        val off = HintWireCodec.encode(HintCommand.Busy("s", 2, com.sheen.rokid.contracts.AssistantRoute.WAKE, busy = false))
        assertEquals(false, (HintWireCodec.decode(off) as HintCommand.Busy).busy)
    }

    @Test fun theNewCommandsAreBoundedLikeTheOldOnes() {
        val overBound = HintCommand.Said("s", 2, 1, com.sheen.rokid.contracts.AssistantRoute.WAKE, "e-1",
            "字".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS + 1))
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(overBound) }
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(HintCommand.Busy(" ", 2, com.sheen.rokid.contracts.AssistantRoute.WAKE, true)) }
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(HintCommand.Said("s", 2, 1, com.sheen.rokid.contracts.AssistantRoute.WAKE, " ", "文本")) }
    }

    // ------------------------------------------------ 0.24 official conversation commands (spec §5.4)

    private val officialConversationCommands: List<HintCommand> = listOf(
        HintCommand.State("s-1", 3, AssistantRoute.PROACTIVE, "t-1", status = 3, sequence = 9),
        HintCommand.Idle("s-1", 3, AssistantRoute.WAKE, sequence = 9),
        HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", revision = 2, text = "你好"),
        HintCommand.Answer("s-1", 3, AssistantRoute.PROACTIVE, "t-1", hintId = "h-1", revision = 4, text = "答案"),
        HintCommand.Tool("s-1", 3, AssistantRoute.WAKE, "t-1", ToolPhase.START, "search"),
        HintCommand.Close("s-1", 3, AssistantRoute.WAKE),
        HintCommand.Pin("s-1", 3, AssistantRoute.PROACTIVE),
        HintCommand.PanelOpen("s-1", 3, AssistantRoute.WAKE),
        HintCommand.PanelShown("s-1", 3, AssistantRoute.WAKE),
        HintCommand.UiReady("s-1", 3),
        HintCommand.UiClosed("s-1", 3, AssistantRoute.PROACTIVE),
    )

    /** The ten commands 0.24's peer knows; anything else makes its `decode` return null. */
    private val legacyCommandNames = setOf(
        "HELLO", "PING", "PONG", "SYNC", "SHOW", "CLEAR", "ACK", "SAID", "BUSY", "STOP",
    )

    private fun legacyDecoder(json: String): String? {
        val command = Regex("\"command\":\"([A-Z_]+)\"").find(json)?.groupValues?.get(1) ?: return null
        return command.takeIf { it in legacyCommandNames }
    }

    @Test fun everyOfficialConversationCommandRoundTrips() {
        for (command in officialConversationCommands) {
            val json = HintWireCodec.encode(command)
            assertEquals(command, HintWireCodec.decode(json),
                "round trip failed for ${command::class.simpleName}: $json")
        }
    }

    @Test fun theOfficialCommandsEncodeInTheSpecifiedFieldOrder() {
        val cases = listOf(
            HintWireCodec.encode(HintCommand.State("s-1", 3, AssistantRoute.PROACTIVE, "t-1", 3, 9)) to
                """{"wireVersion":1,"command":"STATE","sessionId":"s-1","generation":3,"route":"PROACTIVE","turnId":"t-1","status":3,"sequence":9}""",
            HintWireCodec.encode(HintCommand.Idle("s-1", 3, AssistantRoute.WAKE, 9)) to
                """{"wireVersion":1,"command":"IDLE","sessionId":"s-1","generation":3,"route":"WAKE","sequence":9}""",
            HintWireCodec.encode(HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", 2, "你好")) to
                """{"wireVersion":1,"command":"ASR","sessionId":"s-1","generation":3,"route":"WAKE","turnId":"t-1","revision":2,"text":"你好"}""",
            HintWireCodec.encode(HintCommand.Answer("s-1", 3, AssistantRoute.PROACTIVE, "t-1", "h-1", 4, "答案")) to
                """{"wireVersion":1,"command":"ANSWER","sessionId":"s-1","generation":3,"route":"PROACTIVE","turnId":"t-1","hintId":"h-1","revision":4,"text":"答案"}""",
            HintWireCodec.encode(HintCommand.Tool("s-1", 3, AssistantRoute.WAKE, "t-1", ToolPhase.START, "search")) to
                """{"wireVersion":1,"command":"TOOL","sessionId":"s-1","generation":3,"route":"WAKE","turnId":"t-1","phase":"start","label":"search"}""",
            HintWireCodec.encode(HintCommand.Tool("s-1", 3, AssistantRoute.WAKE, "t-1", ToolPhase.END, "search")) to
                """{"wireVersion":1,"command":"TOOL","sessionId":"s-1","generation":3,"route":"WAKE","turnId":"t-1","phase":"end","label":"search"}""",
            HintWireCodec.encode(HintCommand.Close("s-1", 3, AssistantRoute.WAKE)) to
                """{"wireVersion":1,"command":"CLOSE","sessionId":"s-1","generation":3,"route":"WAKE"}""",
            HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.PROACTIVE)) to
                """{"wireVersion":1,"command":"PIN","sessionId":"s-1","generation":3,"route":"PROACTIVE"}""",
            HintWireCodec.encode(HintCommand.PanelOpen("s-1", 3, AssistantRoute.WAKE)) to
                """{"wireVersion":1,"command":"PANEL_OPEN","sessionId":"s-1","generation":3,"route":"WAKE"}""",
            HintWireCodec.encode(HintCommand.PanelShown("s-1", 3, AssistantRoute.WAKE)) to
                """{"wireVersion":1,"command":"PANEL_SHOWN","sessionId":"s-1","generation":3,"route":"WAKE"}""",
            HintWireCodec.encode(HintCommand.UiReady("s-1", 3)) to
                """{"wireVersion":1,"command":"UI_READY","sessionId":"s-1","generation":3}""",
            HintWireCodec.encode(HintCommand.UiClosed("s-1", 3, AssistantRoute.PROACTIVE)) to
                """{"wireVersion":1,"command":"UI_CLOSED","sessionId":"s-1","generation":3,"route":"PROACTIVE"}""",
        )
        for ((json, expected) in cases) assertEquals(expected, json, "field order/bytes: $json")
    }

    @Test fun eachOfficialConversationCommandIsUnknownToTheOldDecoder() {
        // A 0.24 glasses talking to a pre-0.24 phone (or the reverse) must fail closed: the old decoder's dispatch has
        // no branch for these names, so it answers null instead of guessing.
        for (command in officialConversationCommands) {
            val json = HintWireCodec.encode(command)
            assertNull(legacyDecoder(json), "a pre-0.24 decoder must not understand: $json")
            assertNotNull(HintWireCodec.decode(json), "this build must read its own command: $json")
        }
    }

    @Test fun theOldShowAndSaidStayByteIdentical() {
        // New commands are additive. These exact strings are the pre-0.24 bytes for the same values.
        val show = HintWireCodec.encode(
            HintCommand.Show("s-1", 2, 7, "h-9", 3, "sync-1", 1_000, 1_500, "先讲结论"))
        assertEquals(
            """{"wireVersion":1,"command":"SHOW","sessionId":"s-1","generation":2,"route":"WAKE","sequence":7,"hintId":"h-9","revision":3,"syncId":"sync-1","phoneSentNanos":1000,"remainingTtlMillis":1500,"text":"先讲结论"}""",
            show)
        val said = HintWireCodec.encode(
            HintCommand.Said("s-1", 2, 7, AssistantRoute.PROACTIVE, "e-1", "我待会儿要开会"))
        assertEquals(
            """{"wireVersion":1,"command":"SAID","sessionId":"s-1","generation":2,"sequence":7,"route":"PROACTIVE","entryId":"e-1","text":"我待会儿要开会"}""",
            said)
    }

    @Test fun stateStatusIsAnIntegerFromZeroThroughSeven() {
        for (status in 0..7) {
            val command = HintCommand.State("s-1", 3, AssistantRoute.WAKE, "t-1", status, 9)
            val json = HintWireCodec.encode(command)
            assertTrue(json.contains("\"status\":$status,"), "status must travel as an integer: $json")
            assertEquals(command, HintWireCodec.decode(json))
        }
        val base = HintWireCodec.encode(HintCommand.State("s-1", 3, AssistantRoute.WAKE, "t-1", 3, 9))
        assertNull(HintWireCodec.decode(base.replace("\"status\":3", "\"status\":8")))
        assertNull(HintWireCodec.decode(base.replace("\"status\":3", "\"status\":-1")))
        assertNull(HintWireCodec.decode(base.replace("\"status\":3", "\"status\":\"3\"")),
            "a quoted integer is a mistyped field, not a status")
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.State("s-1", 3, AssistantRoute.WAKE, "t-1", 8, 9))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.State("s-1", 3, AssistantRoute.WAKE, "t-1", -1, 9))
        }
    }

    @Test fun toolPhaseTravelsLowercaseAndDecodesToTheSharedEnum() {
        val start = HintCommand.Tool("s-1", 3, AssistantRoute.WAKE, "t-1", ToolPhase.START, "search")
        assertTrue(HintWireCodec.encode(start).contains("\"phase\":\"start\""))
        assertEquals(start, HintWireCodec.decode(HintWireCodec.encode(start)))
        val end = start.copy(phase = ToolPhase.END)
        assertTrue(HintWireCodec.encode(end).contains("\"phase\":\"end\""))
        assertEquals(ToolPhase.END, (HintWireCodec.decode(HintWireCodec.encode(end)) as HintCommand.Tool).phase)
        assertNull(HintWireCodec.decode(HintWireCodec.encode(start)
            .replace("\"phase\":\"start\"", "\"phase\":\"START\"")),
            "the shared enum is not the wire spelling")
    }

    @Test fun closeUsesAllForTheWholePanelAndDecodesItBackToNoRoute() {
        val all = HintWireCodec.encode(HintCommand.Close("s-1", 3, null))
        assertEquals(
            """{"wireVersion":1,"command":"CLOSE","sessionId":"s-1","generation":3,"route":"ALL"}""",
            all)
        assertEquals(HintCommand.Close("s-1", 3, null), HintWireCodec.decode(all),
            "ALL must come back as the nullable route, not an enum value")
        val wake = HintCommand.Close("s-1", 3, AssistantRoute.WAKE)
        assertEquals(wake, HintWireCodec.decode(HintWireCodec.encode(wake)))
        assertNull(HintWireCodec.decode(all.replace("\"route\":\"ALL\"", "\"route\":\"ELSEWHERE\"")))
        assertNull(HintWireCodec.decode(all.replace(",\"route\":\"ALL\"", "")))
    }

    @Test fun asrAndAnswerTextIsTheLast400CodePointsWithoutSplittingSurrogates() {
        val emoji = "🙂"
        assertEquals(2, emoji.length, "the fixture must really be a surrogate pair")
        val overBound = "a" + emoji.repeat(HintWireCodec.MAX_TEXT_CODEPOINTS)
        assertEquals(HintWireCodec.MAX_TEXT_CODEPOINTS + 1, overBound.codePointCount(0, overBound.length))

        val asr = HintWireCodec.decode(HintWireCodec.encode(
            HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", 1, overBound))) as HintCommand.Asr
        assertEquals(emoji.repeat(HintWireCodec.MAX_TEXT_CODEPOINTS), asr.text,
            "the display increment keeps its tail, not its head")
        assertEquals(HintWireCodec.MAX_TEXT_CODEPOINTS * 2, asr.text.length,
            "the kept text must be whole surrogate pairs")
        assertTrue(asr.text[0].isHighSurrogate(), "truncation must not start on a lone low surrogate")
        assertTrue(asr.text.last().isLowSurrogate(), "truncation must not end on a lone high surrogate")

        val answer = HintWireCodec.decode(HintWireCodec.encode(
            HintCommand.Answer("s-1", 3, AssistantRoute.PROACTIVE, "t-1", "h-1", 1, overBound))) as HintCommand.Answer
        assertEquals(asr.text, answer.text)

        val exactlyBounded = emoji.repeat(HintWireCodec.MAX_TEXT_CODEPOINTS)
        val kept = HintWireCodec.decode(HintWireCodec.encode(
            HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", 1, exactlyBounded))) as HintCommand.Asr
        assertEquals(exactlyBounded, kept.text, "text at the bound is not truncated")

        // The glasses must not re-apply the encoder's display bound: spec §5.4 says the phone trims and the glasses
        // accept the increment, so an over-bound text from a peer is read, not silently dropped.
        val overLimitJson = """{"wireVersion":1,"command":"ASR","sessionId":"s-1","generation":3,"route":"WAKE","turnId":"t-1","revision":1,"text":"""" +
            "x".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS + 1) + "\"}"
        assertEquals(HintWireCodec.MAX_TEXT_CODEPOINTS + 1,
            (HintWireCodec.decode(overLimitJson) as HintCommand.Asr).text.length)
    }

    @Test fun pinCarriesNoBooleanAndUiReadyCarriesNoRoute() {
        val pin = HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.WAKE))
        assertFalse(pin.contains("\"pinned\""), "PIN is one-way true, not a boolean: $pin")
        assertFalse(pin.contains("\"pinned\":true"), pin)

        val ready = HintWireCodec.encode(HintCommand.UiReady("s-1", 3))
        assertFalse(ready.contains("\"route\""), "UI_READY is service-scoped, not route-scoped: $ready")
    }

    @Test fun malformedOfficialConversationCommandsAreRefusedRatherThanThrowing() {
        val state = HintWireCodec.encode(
            HintCommand.State("s-1", 3, AssistantRoute.PROACTIVE, "t-1", 3, 9))
        assertNull(HintWireCodec.decode(state.replace("\"wireVersion\":1", "\"wireVersion\":2")))
        assertNull(HintWireCodec.decode(state.replace(",\"status\":3", "")), "missing status")
        assertNull(HintWireCodec.decode(state.replace("\"status\":3", "\"status\":\"3\"")), "mistyped status")
        assertNull(HintWireCodec.decode(state.replace("\"sequence\":9", "\"sequence\":-1")), "negative sequence")
        assertNull(HintWireCodec.decode(state.replace("\"route\":\"PROACTIVE\"", "\"route\":\"ELSEWHERE\"")), "unknown route")
        assertNull(HintWireCodec.decode(state.replace("\"turnId\":\"t-1\"", "\"turnId\":\" \"")), "blank turn id")
        assertNull(HintWireCodec.decode(state.replace("\"turnId\":\"t-1\"", "\"turnId\":5")), "mistyped turn id")
        assertNull(HintWireCodec.decode(state.replace("\"sessionId\":\"s-1\"", "\"sessionId\":\"\"")), "blank session id")
        assertNull(HintWireCodec.decode(state.replace(",\"sequence\":9", ",\"sequence\":9,\"sequence\":10")),
            "a duplicated field is ambiguous")

        val asr = HintWireCodec.encode(HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", 2, "你好"))
        assertNull(HintWireCodec.decode(asr.replace("\"revision\":2", "\"revision\":-2")), "negative revision")
        assertNull(HintWireCodec.decode(asr.replace(",\"revision\":2", "")), "missing revision")
        assertNull(HintWireCodec.decode(asr.replace(",\"text\":\"你好\"", "")), "missing text")

        val answer = HintWireCodec.encode(
            HintCommand.Answer("s-1", 3, AssistantRoute.WAKE, "t-1", "h-1", 2, "答案"))
        assertNull(HintWireCodec.decode(answer.replace(",\"hintId\":\"h-1\"", "")), "missing hint id")

        val tool = HintWireCodec.encode(
            HintCommand.Tool("s-1", 3, AssistantRoute.WAKE, "t-1", ToolPhase.START, "search"))
        assertNull(HintWireCodec.decode(tool.replace(",\"label\":\"search\"", "")), "missing label")

        val ready = HintWireCodec.encode(HintCommand.UiReady("s-1", 3))
        assertNull(HintWireCodec.decode(ready.replace(",\"generation\":3", "")), "missing generation")
    }

    @Test fun officialCommandsRefuseNegativeSequenceAndRevisionOnEncode() {
        val commands = listOf(
            HintCommand.State("s-1", 3, AssistantRoute.WAKE, "t-1", 1, -1),
            HintCommand.Idle("s-1", 3, AssistantRoute.WAKE, -1),
            HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", -1, "你好"),
            HintCommand.Answer("s-1", 3, AssistantRoute.WAKE, "t-1", "h-1", -1, "答案"),
        )
        for (command in commands) {
            assertFailsWith<IllegalArgumentException>("negative counters must not be sent: $command") {
                HintWireCodec.encode(command)
            }
        }
    }

    @Test fun officialCommandsRefuseBlankIdentifiersOnEncode() {
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.State(" ", 3, AssistantRoute.WAKE, "t-1", 1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.State("s-1", 3, AssistantRoute.WAKE, " ", 1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.Idle("", 3, AssistantRoute.WAKE, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, " ", 1, "你好"))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.Answer("s-1", 3, AssistantRoute.WAKE, "t-1", " ", 1, "答案"))
        }
        assertFailsWith<IllegalArgumentException> {
            HintWireCodec.encode(HintCommand.Pin(" ", 3, AssistantRoute.WAKE))
        }
    }
}
