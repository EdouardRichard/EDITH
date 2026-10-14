package edith.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
