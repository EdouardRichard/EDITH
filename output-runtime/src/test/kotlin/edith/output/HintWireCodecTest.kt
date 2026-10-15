package edith.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T18 versioned hint wire (wire version 1).
 *
 * Commands are `HELLO/PING/PONG/SYNC/SHOW/CLEAR/ACK`. The phone and glasses clock domains are distinguished
 * explicitly in the field names (`phoneSentNanos` is a phone instant, `validUntilGlassNanos` is a glasses
 * instant) so a raw phone nanosecond can never be read as a glasses clock.
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
                status = edith.contracts.OutputStatus.Displayed),
            HintCommand.Said(sessionId = "s-1", generation = 1, sequence = 6,
                route = edith.contracts.AssistantRoute.PROACTIVE, entryId = "e-1", text = "我待会儿要开会"),
            HintCommand.Busy(sessionId = "s-1", generation = 1,
                route = edith.contracts.AssistantRoute.WAKE, busy = true),
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
        for (status in edith.contracts.OutputStatus.entries) {
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

    @Test fun theShowCarriesItsRouteAndTheOldShapeStillReadsAsWake() {
        val command = HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, "t",
            route = edith.contracts.AssistantRoute.PROACTIVE)
        val json = HintWireCodec.encode(command)
        assertTrue(json.contains("\"route\":\"PROACTIVE\""), "the route must travel with the hint: $json")
        assertEquals(command, HintWireCodec.decode(json))

        // A peer built before the route existed sends no such field. That message is a wake hint — reading it as
        // anything else would move a pre-Phase-C hint into a conversation the wearer may not be looking at.
        val withoutRoute = json.replace(",\"route\":\"PROACTIVE\"", "")
        assertEquals(edith.contracts.AssistantRoute.WAKE,
            (HintWireCodec.decode(withoutRoute) as HintCommand.Show).route)
    }

    @Test fun aShowWithARouteThisBuildDoesNotKnowIsRefused() {
        val json = HintWireCodec.encode(HintCommand.Show("s", 1, 1, "h", 1, "sync", 1_000, 1_500, "t"))
            .replace("\"route\":\"WAKE\"", "\"route\":\"ELSEWHERE\"")
        assertNull(HintWireCodec.decode(json), "an unknown route must be refused, not defaulted")
    }

    @Test fun aSaidRoundTripsAndCarriesNoClockFields() {
        val command = HintCommand.Said("s", 2, 7, edith.contracts.AssistantRoute.PROACTIVE, "e-1", "帮我看下日程")
        val json = HintWireCodec.encode(command)
        assertEquals(command, HintWireCodec.decode(json))
        assertTrue(json.contains("\"route\":\"PROACTIVE\""), json)
        // An utterance has no deadline: it is a record of what was said, and the glasses must not expire it.
        assertFalse(json.contains("syncId"), "SAID must not carry clock fields: $json")
        assertFalse(json.contains("remainingTtlMillis"), "SAID must not carry a lifetime: $json")
    }

    @Test fun aBusyRoundTripsWithAnIntegerFlag() {
        // The flat reader carries strings and integers only, so the flag is 1/0 and never `true`/`false`.
        val json = HintWireCodec.encode(HintCommand.Busy("s", 2, edith.contracts.AssistantRoute.WAKE, busy = true))
        assertTrue(json.contains("\"busy\":1"), "the busy flag must be an integer: $json")
        assertEquals(true, (HintWireCodec.decode(json) as HintCommand.Busy).busy)
        val off = HintWireCodec.encode(HintCommand.Busy("s", 2, edith.contracts.AssistantRoute.WAKE, busy = false))
        assertEquals(false, (HintWireCodec.decode(off) as HintCommand.Busy).busy)
    }

    @Test fun theNewCommandsAreBoundedLikeTheOldOnes() {
        val overBound = HintCommand.Said("s", 2, 1, edith.contracts.AssistantRoute.WAKE, "e-1",
            "字".repeat(HintWireCodec.MAX_TEXT_CODEPOINTS + 1))
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(overBound) }
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(HintCommand.Busy(" ", 2, edith.contracts.AssistantRoute.WAKE, true)) }
        assertFailsWith<IllegalArgumentException> { HintWireCodec.encode(HintCommand.Said("s", 2, 1, edith.contracts.AssistantRoute.WAKE, " ", "文本")) }
    }
}
